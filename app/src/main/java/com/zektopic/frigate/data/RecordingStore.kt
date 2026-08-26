package com.zektopic.frigate.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One clip or snapshot on disk, wherever "disk" happens to be. */
data class StoredRecording(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** Opaque handle: an absolute file path, or a `content://` document URI. */
    val ref: String
)

/**
 * Resolves *where* recordings are written, and reads them back from wherever they landed.
 *
 * The destination is either the app's own external files dir (the default, and what every
 * build before this one used) or any folder the user picked through the Storage Access
 * Framework — an SD card, a USB stick, a cloud provider. SAF is the only API that can
 * write to removable media on API 26+, so custom destinations are document trees rather
 * than paths, and the refs stored in [EventEntity] are `content://` URIs there.
 *
 * Clips are always *encoded* into [stagingDir] and copied into place by [publish] once
 * finalized. MediaMuxer needs a seekable fd and not every document provider hands one out
 * on "rw", so encoding straight into a SAF destination would work on an SD card and fail
 * on Drive — the staging hop makes the destination irrelevant to the encoder. Staging sits
 * on the same volume as [defaultDir], so publishing to the default destination is a rename
 * rather than a copy.
 */
class RecordingStore(private val context: Context) {

    private val tag = "RecordingStore"
    private val resolver get() = context.contentResolver

    /**
     * Backed by SharedPreferences rather than the DataStore in [AppPreferences]: the
     * destination must be readable synchronously from ClipRecorder on the recording path,
     * and by NvrService at boot before any coroutine has run.
     */
    private val prefs = context.getSharedPreferences("frigate_recording_location", Context.MODE_PRIVATE)

    /** Memoized `frigate_recordings` folder inside the picked tree; invalidated when the tree changes. */
    @Volatile private var cachedDestDir: Uri? = null
    @Volatile private var cachedDestDirForTree: Uri? = null

    companion object {
        const val DIR_NAME = "frigate_recordings"
        private const val STAGING_DIR_NAME = "frigate_staging"
        private const val KEY_TREE_URI = "tree_uri"

        const val MIME_MP4 = "video/mp4"
        const val MIME_JPEG = "image/jpeg"

        /** Intent that opens the system folder picker — SD cards and all. */
        fun pickerIntent(): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

        /**
         * Turn a stored ref into something a player or decoder accepts. Rows written
         * before this feature hold absolute paths; rows written to a custom destination
         * hold document URIs. Both keep working.
         */
        fun toPlayableUri(ref: String): Uri =
            if (ref.startsWith("content://")) Uri.parse(ref) else Uri.fromFile(File(ref))
    }

    // -----------------------------------------------------------------------
    // Destination selection
    // -----------------------------------------------------------------------

    /** The user's chosen destination tree, or null when recording to the default location. */
    val treeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let { Uri.parse(it) }

    val isCustomDestination: Boolean get() = treeUri != null

    /**
     * Persist [tree] as the destination and take a permission grant that survives reboot.
     * Returns false — changing nothing — if the grant cannot be held.
     */
    fun setDestination(tree: Uri): Boolean {
        return try {
            resolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_TREE_URI, tree.toString()).apply()
            cachedDestDir = null
            cachedDestDirForTree = null
            Log.i(tag, "Recording destination set to $tree")
            true
        } catch (e: SecurityException) {
            Log.e(tag, "Could not persist permission for $tree", e)
            false
        }
    }

    /** Revert to the app's own external files dir. Existing recordings stay where they are. */
    fun clearDestination() {
        treeUri?.let { tree ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
        cachedDestDir = null
        cachedDestDirForTree = null
    }

    /**
     * True if the destination can actually be written to right now. A persisted grant
     * outlives the card: an SD card unmounted at boot, or reformatted, leaves a tree URI
     * that resolves to nothing. Callers surface this rather than discovering it as one
     * swallowed exception per motion event.
     */
    fun isAvailable(): Boolean {
        val tree = treeUri ?: return true
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) return false
        return destinationDirUri() != null
    }

    /** Human-readable description of where clips are going, for Settings and About. */
    fun displayLocation(): String {
        val tree = treeUri ?: return defaultDir.absolutePath
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return tree.toString()
        // ExternalStorageProvider ids read "XXXX-XXXX:Movies/cams", "primary:DCIM", or
        // "XXXX-XXXX:" for the volume root — which must not render a doubled slash.
        val volume = docId.substringBefore(':', "")
        val path = docId.substringAfter(':', docId).trim('/')
        val volumeLabel = when {
            volume.equals("primary", ignoreCase = true) -> "Internal storage"
            volume.isEmpty() -> tree.authority ?: "External"
            else -> "SD card ($volume)"
        }
        val relative = if (path.isEmpty()) DIR_NAME else "$path/$DIR_NAME"
        return "$volumeLabel / $relative"
    }

    // -----------------------------------------------------------------------
    // Locations
    // -----------------------------------------------------------------------

    /** External app files dir, falling back to internal storage on devices without one. */
    private val externalBase: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * Where recordings live when no custom destination is set.
     *
     * Lazy, not a `get()`: this is read inside `ClipRecorder.onMotion`'s monitor, and an
     * `exists()`/`mkdirs()` per motion frame is disk I/O on the frame path.
     */
    val defaultDir: File by lazy {
        File(externalBase, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /**
     * Scratch space for in-progress encodes. Deliberately not `cacheDir` — the OS may
     * reclaim that mid-encode, truncating a clip that is still being written.
     */
    val stagingDir: File by lazy {
        File(externalBase, STAGING_DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /**
     * Free bytes on the volume [stagingDir] sits on, or null if it cannot be measured.
     *
     * Distinct from [volumeStats], which measures the *destination*. Every clip is encoded
     * into staging first whatever the destination is, so a full app volume stops recording
     * even when a chosen SD card has tens of gigabytes free.
     */
    fun freeBytesOnStagingVolume(): Long? = try {
        android.os.StatFs(stagingDir.absolutePath).availableBytes
    } catch (e: Exception) {
        Log.w(tag, "Could not measure staging volume", e)
        null
    }

    /** Resolve — creating if needed — the `frigate_recordings` folder inside the picked tree. */
    private fun destinationDirUri(): Uri? {
        val tree = treeUri ?: return null
        cachedDestDir?.let { if (cachedDestDirForTree == tree) return it }

        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(tree)
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)

            var found: Uri? = null
            val cursor = resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            ) ?: return null // query failed outright: card gone, provider dead
            cursor.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == DIR_NAME &&
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    ) {
                        found = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        break
                    }
                }
            }

            // The user may well have picked a folder already called frigate_recordings.
            if (found == null && treeDocId.substringAfterLast('/') == DIR_NAME) found = treeDocUri

            val dir = found ?: DocumentsContract.createDocument(
                resolver, treeDocUri, DocumentsContract.Document.MIME_TYPE_DIR, DIR_NAME
            )
            if (dir != null) {
                cachedDestDir = dir
                cachedDestDirForTree = tree
            }
            dir
        } catch (e: Exception) {
            Log.e(tag, "Destination tree unusable: $tree", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /**
     * Move a finalized staging file into the destination and return its ref, or null if
     * even the fallback failed. A custom destination that refuses the write does not lose
     * the clip: it lands in [defaultDir] instead, which [list] reads anyway.
     */
    fun publish(staged: File, mimeType: String = MIME_MP4): String? {
        val dir = destinationDirUri()
        if (dir != null) {
            var created: Uri? = null
            try {
                created = DocumentsContract.createDocument(resolver, dir, mimeType, staged.name)
                    ?: throw IOException("createDocument returned null for ${staged.name}")
                resolver.openOutputStream(created, "w").use { out ->
                    if (out == null) throw IOException("No output stream for $created")
                    FileInputStream(staged).use { it.copyTo(out) }
                }
                staged.delete()
                return created.toString()
            } catch (e: Exception) {
                // A half-written document is worse than none — it would be listed and played.
                created?.let { doc -> runCatching { DocumentsContract.deleteDocument(resolver, doc) } }
                Log.e(tag, "Publish to custom destination failed; keeping ${staged.name} locally", e)
            }
        }
        return moveIntoDefaultDir(staged)
    }

    /**
     * Park a finalized clip in [defaultDir] without touching the custom destination.
     *
     * For callers that must not block — service teardown and `onTrimMemory` both run on
     * the main thread, where copying megabytes to an SD card is an ANR. Same volume as
     * staging, so this is a rename. The clip stays visible: [list] reads [defaultDir] too.
     */
    fun stashLocally(staged: File): String? = moveIntoDefaultDir(staged)

    private fun moveIntoDefaultDir(staged: File): String? {
        val dest = File(defaultDir, staged.name)
        if (staged.renameTo(dest)) return dest.absolutePath
        return try {
            staged.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            staged.delete()
            dest.absolutePath
        } catch (e: IOException) {
            Log.e(tag, "Could not move ${staged.name} into ${defaultDir.absolutePath}", e)
            null
        }
    }

    /**
     * Write a small file (snapshots) straight to the destination — nothing seeks, so no
     * staging hop is needed. Falls back to [defaultDir] exactly like [publish].
     */
    fun write(name: String, mimeType: String, body: (OutputStream) -> Unit): String? {
        val dir = destinationDirUri()
        if (dir != null) {
            var created: Uri? = null
            try {
                created = DocumentsContract.createDocument(resolver, dir, mimeType, name)
                    ?: throw IOException("createDocument returned null for $name")
                resolver.openOutputStream(created, "w").use { out ->
                    if (out == null) throw IOException("No output stream for $created")
                    body(out)
                }
                return created.toString()
            } catch (e: Exception) {
                created?.let { doc -> runCatching { DocumentsContract.deleteDocument(resolver, doc) } }
                Log.e(tag, "Write of $name to custom destination failed; falling back", e)
            }
        }
        val dest = File(defaultDir, name)
        return try {
            dest.outputStream().use(body)
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Could not write $name to ${defaultDir.absolutePath}", e)
            dest.delete()
            null
        }
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    fun openInput(ref: String): InputStream? = try {
        if (ref.startsWith("content://")) resolver.openInputStream(Uri.parse(ref))
        else File(ref).takeIf { it.exists() }?.inputStream()
    } catch (e: Exception) {
        Log.w(tag, "Could not open $ref", e)
        null
    }

    /**
     * Every recording the app can see: the active destination plus [defaultDir], because
     * clips recorded before the destination changed are still there and still play.
     */
    fun list(): List<StoredRecording> {
        val out = ArrayList<StoredRecording>()
        defaultDir.listFiles()?.forEach {
            if (it.isFile) out += StoredRecording(it.name, it.length(), it.lastModified(), it.absolutePath)
        }
        val tree = treeUri ?: return out
        val dir = destinationDirUri() ?: return out
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(dir)
            )
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = c.getString(1) ?: continue
                    out += StoredRecording(
                        name = name,
                        sizeBytes = if (c.isNull(2)) 0L else c.getLong(2),
                        lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                        ref = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Could not list custom destination", e)
        }
        return out
    }

    fun delete(ref: String): Boolean = try {
        if (ref.startsWith("content://")) DocumentsContract.deleteDocument(resolver, Uri.parse(ref))
        else File(ref).delete()
    } catch (e: Exception) {
        Log.w(tag, "Could not delete $ref", e)
        false
    }

    /** Deletes every recording in every location the app knows about; returns how many went. */
    fun deleteAll(): Int = list().count { delete(it.ref) }

    /**
     * (totalBytes, availableBytes) for the volume recordings land on, or null when the
     * destination is a provider whose free space Android will not report — a cloud or
     * network tree. The UI shows nothing there rather than internal storage's numbers.
     */
    fun volumeStats(): Pair<Long, Long>? {
        val dir = resolveDestinationVolumeDir() ?: return null
        return try {
            val stat = android.os.StatFs(dir.absolutePath)
            stat.totalBytes to stat.availableBytes
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Map the destination tree back to a real directory on the same physical volume so
     * StatFs has something to measure. Covers the volumes Android enumerates — internal
     * shared storage and SD cards; anything else returns null.
     */
    private fun resolveDestinationVolumeDir(): File? {
        val tree = treeUri ?: return externalBase
        val volumeId = runCatching {
            DocumentsContract.getTreeDocumentId(tree).substringBefore(':', "")
        }.getOrNull().orEmpty()
        if (volumeId.isEmpty()) return null
        if (volumeId.equals("primary", ignoreCase = true)) return externalBase

        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
        return context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { dir ->
                runCatching { sm.getStorageVolume(dir)?.uuid }.getOrNull()
                    ?.equals(volumeId, ignoreCase = true) == true
            }
    }
}
