package com.zektopic.frigate.data

import android.util.Log

/**
 * Keeps recording alive by making room for it.
 *
 * Two sweeps, both driven from NvrService's janitor loop and never from the frame path:
 *
 *  - **Age** — drop recordings past their camera's retention window.
 *  - **Space** — when a volume the app writes to falls below [FLOOR_BYTES] free, delete
 *    oldest-first until it is back above [TARGET_BYTES].
 *
 * Recording must never stop for lack of disk, so when the two are in conflict old footage
 * loses to new footage.
 *
 * **Two volumes matter, not one.** Clips are always encoded into [RecordingStore.stagingDir]
 * on the app's own volume before being published, so a full internal partition stops
 * recording even when a chosen SD card has tens of gigabytes free — and deleting files on
 * the card frees nothing on internal. Each volume is therefore measured and evicted against
 * its own free space, with the ref as the discriminator: `content://` refs live in the
 * destination tree, plain paths live in [RecordingStore.defaultDir] beside staging.
 */
class RecordingJanitor(
    private val store: RecordingStore,
    private val nvrDao: NvrDao
) {
    private val tag = "RecordingJanitor"

    companion object {
        /** Below this much free on a volume, recordings start being evicted from it. */
        const val FLOOR_BYTES = 1_000_000_000L

        /**
         * Evict up to this much free rather than just back over the floor, so a low-disk
         * episode costs one directory listing instead of one per clip recorded.
         */
        const val TARGET_BYTES = 2_000_000_000L

        private const val AGE_SWEEP_INTERVAL_MS = 15 * 60_000L

        /**
         * Ceilings on one eviction pass. Deleting a SAF document is an IPC each, so
         * reclaiming several gigabytes one file at a time would run for minutes; the next
         * sweep picks up where this one stopped.
         */
        private const val MAX_DELETES_PER_PASS = 400
        private const val MAX_PASS_MS = 20_000L

        /**
         * When a volume is below the floor and there is nothing left of ours to delete,
         * the space is someone else's. Stop re-listing the directory every minute.
         */
        private const val GIVE_UP_BACKOFF_MS = 15 * 60_000L
        private const val RETRY_ON_CHANGE_BYTES = 256L * 1024 * 1024

        /**
         * Recordings whose camera is no longer in the config match no retention prefix.
         * Without a fallback they would be exempt forever, which is one of the ways a
         * disk quietly fills up.
         */
        private const val ORPHAN_MAX_AGE_DAYS = 30

        private const val KEY_APP = "app"
        private const val KEY_DESTINATION = "destination"
    }

    /** A volume the app writes to, its free space, and which recordings sit on it. */
    private data class Volume(
        val key: String,
        val label: String,
        val freeBytes: Long,
        val holds: (StoredRecording) -> Boolean
    )

    private var lastAgeSweepAt = 0L

    /** Volume key -> (when we gave up, free bytes at the time). */
    private val gaveUp = HashMap<String, Pair<Long, Long>>()

    @Volatile private var sweeping = false

    /**
     * Run whichever sweeps are due. Safe to call on a timer; overlapping calls are dropped
     * rather than queued, because a pass that is still deleting has not gone stale.
     */
    suspend fun sweep() {
        if (sweeping) return
        sweeping = true
        try {
            runSweep()
        } catch (e: Exception) {
            Log.e(tag, "Sweep failed", e)
        } finally {
            sweeping = false
        }
    }

    private suspend fun runSweep() {
        val now = System.currentTimeMillis()
        val ageDue = now - lastAgeSweepAt >= AGE_SWEEP_INTERVAL_MS
        val low = volumes().filter { it.freeBytes < FLOOR_BYTES && !backingOff(it, now) }
        if (!ageDue && low.isEmpty()) return

        // One listing feeds both sweeps. On a full destination this is a cursor over every
        // document in the folder, so it must never run per-camera or per-clip.
        val entries = store.list()

        var gone = emptySet<String>()
        if (ageDue) {
            lastAgeSweepAt = now
            gone = purgeExpired(entries, now)
        }
        if (low.isNotEmpty()) {
            // Refs the age sweep already removed would fail to delete and read as
            // "nothing left to evict", tripping the backoff for no reason.
            evict(low, entries.filter { it.ref !in gone })
        }
    }

    // -----------------------------------------------------------------------
    // Volumes
    // -----------------------------------------------------------------------

    private fun volumes(): List<Volume> = buildList {
        store.freeBytesOnStagingVolume()?.let { free ->
            add(Volume(KEY_APP, "app storage", free) { !it.ref.startsWith("content://") })
        }
        // Only when the destination is a picked tree: otherwise it *is* the app volume,
        // and a second entry would evict the same files against the same free space twice.
        if (store.isCustomDestination) {
            store.volumeStats()?.second?.let { free ->
                add(Volume(KEY_DESTINATION, store.displayLocation(), free) { it.ref.startsWith("content://") })
            }
        }
    }

    private fun currentFreeBytes(key: String): Long? =
        if (key == KEY_APP) store.freeBytesOnStagingVolume() else store.volumeStats()?.second

    private fun backingOff(volume: Volume, now: Long): Boolean {
        val (whenGaveUp, freeThen) = gaveUp[volume.key] ?: return false
        if (now - whenGaveUp >= GIVE_UP_BACKOFF_MS) return false
        // Something else released or took a chunk of the volume: worth another look early.
        return Math.abs(volume.freeBytes - freeThen) < RETRY_ON_CHANGE_BYTES
    }

    // -----------------------------------------------------------------------
    // Sweeps
    // -----------------------------------------------------------------------

    private suspend fun evict(low: List<Volume>, entries: List<StoredRecording>) {
        val deadline = System.currentTimeMillis() + MAX_PASS_MS
        for (volume in low) {
            // Re-read rather than trusting the snapshot: a picked folder can sit on the
            // same volume as staging, in which case the previous iteration may already
            // have freed everything this one was about to delete for.
            val free = currentFreeBytes(volume.key) ?: continue
            if (free >= TARGET_BYTES) continue

            var shortfall = TARGET_BYTES - free
            val removed = ArrayList<String>()
            for (victim in entries.filter(volume.holds).sortedBy(::effectiveTimestamp)) {
                if (shortfall <= 0 || removed.size >= MAX_DELETES_PER_PASS) break
                if (System.currentTimeMillis() > deadline) break
                if (store.delete(victim.ref)) {
                    shortfall -= victim.sizeBytes
                    removed += victim.ref
                }
            }
            detach(removed)

            if (removed.isEmpty()) {
                gaveUp[volume.key] = System.currentTimeMillis() to free
                Log.w(
                    tag,
                    "${volume.label} has ${free / 1_000_000}MB free and nothing of ours left " +
                        "to evict; backing off for ${GIVE_UP_BACKOFF_MS / 60_000} minutes"
                )
            } else {
                gaveUp.remove(volume.key)
                Log.i(tag, "Evicted ${removed.size} oldest recording(s) from ${volume.label} to keep recording")
            }
        }
    }

    /** Delete recordings past their camera's retention window; returns the refs that went. */
    private suspend fun purgeExpired(entries: List<StoredRecording>, now: Long): Set<String> {
        val cutoffs = try {
            nvrDao.getAllCameraConfigs().map { camera ->
                "${camera.id}_" to now - (camera.recordingRetentionDays * 24 * 60 * 60 * 1000).toLong()
            }
        } catch (e: Exception) {
            Log.e(tag, "Could not read camera retention settings", e)
            return emptySet()
        }
        val orphanCutoff = now - ORPHAN_MAX_AGE_DAYS * 24L * 60L * 60L * 1000L

        val removed = ArrayList<String>()
        for (entry in entries) {
            val cutoff = cutoffs.firstOrNull { entry.name.startsWith(it.first) }?.second ?: orphanCutoff
            if (effectiveTimestamp(entry) < cutoff && store.delete(entry.ref)) removed += entry.ref
        }
        detach(removed)
        if (removed.isNotEmpty()) Log.i(tag, "Retention removed ${removed.size} expired recording(s)")
        return removed.toSet()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * When a recording was made.
     *
     * [StoredRecording.lastModified] is authoritative, but a document provider may not
     * report it at all, and `list` maps that absence to 0 — which would sort a clip
     * recorded seconds ago to the front of the eviction queue. The epoch millis in the
     * filename are the fallback; a SAF collision rename ("cam_1724.mp4" -> "cam_1724 (1).mp4")
     * is why they are not the primary source. Unknown counts as newest, so an unreadable
     * timestamp costs a file nothing.
     */
    private fun effectiveTimestamp(entry: StoredRecording): Long =
        if (entry.lastModified > 0) entry.lastModified
        else NAME_MILLIS.find(entry.name)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE

    private suspend fun detach(refs: List<String>) {
        if (refs.isEmpty()) return
        try {
            for (chunk in refs.chunked(200)) {
                nvrDao.clearEventVideoPaths(chunk)
                nvrDao.clearEventSnapshotPaths(chunk)
            }
            nvrDao.deleteEventsWithoutMedia()
        } catch (e: Exception) {
            Log.e(tag, "Could not detach ${refs.size} deleted recording(s) from their events", e)
        }
    }
}

/** Trailing epoch millis in "camera_1787727346022.mp4", tolerating a provider's " (1)" suffix. */
private val NAME_MILLIS = Regex("_(\\d{12,})(?: \\(\\d+\\))?\\.[A-Za-z0-9]+$")
