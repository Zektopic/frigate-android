package com.zektopic.frigate.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.zektopic.frigate.data.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded event snapshots, so scrolling a review grid back over items it has already
 * shown does not decode them again. Bounded by bytes, not entries: snapshots are
 * detect-resolution, but that can be 1080p.
 */
private object SnapshotCache : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/**
 * Loads a stored snapshot off the composition thread. The ref is a file path for the
 * default location and a document URI when recordings go to a user-chosen folder; the
 * latter is a provider round-trip, far too slow to do inline while a grid scrolls.
 */
@Composable
fun rememberStoredBitmap(ref: String?): Bitmap? {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState(initialValue = ref?.let { SnapshotCache.get(it) }, ref) {
        if (ref == null) {
            value = null
            return@produceState
        }
        SnapshotCache.get(ref)?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                RecordingStore(context).openInput(ref)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }?.also { SnapshotCache.put(ref, it) }
    }
    return bitmap
}
