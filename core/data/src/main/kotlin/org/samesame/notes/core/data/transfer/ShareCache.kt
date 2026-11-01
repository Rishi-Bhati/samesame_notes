package org.samesame.notes.core.data.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `cache/share`: plaintext exports handed to other apps (Share, Save as file, voice share). They must not outlive
 * their use (security review L3): a note shared and then locked or deleted would otherwise stay readable there.
 * Swept at app start and whenever notes are locked; Save batches are removed once copied.
 * `camera/` is kept: it holds a capture the camera app may still be writing (or that a restarted process inserts).
 */
@Singleton
class ShareCache internal constructor(val root: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "share"))

    /** Deletes everything except a pending camera capture younger than [CAMERA_MAX_AGE_MS]. */
    fun sweep(now: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach { f ->
            if (f.name == CAMERA_DIR) {
                f.listFiles()?.forEach { if (now - it.lastModified() > CAMERA_MAX_AGE_MS) it.deleteRecursively() }
            } else {
                f.deleteRecursively()
            }
        }
    }

    internal companion object {
        const val CAMERA_DIR = "camera"
        const val CAMERA_MAX_AGE_MS = 24 * 60 * 60_000L
    }
}
