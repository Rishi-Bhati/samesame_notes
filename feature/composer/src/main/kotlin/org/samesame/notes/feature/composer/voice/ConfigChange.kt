package org.samesame.notes.feature.composer.voice

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Whether the activity behind this context is being torn down only to be recreated (rotation, font scale, language...).
 * The player and the recorder live in process-wide hubs, so the editor going away for a recreation must not stop,
 * pause or rewind them (the new editor picks the same controller up again).
 */
internal tailrec fun Context.isChangingConfigurations(): Boolean = when (this) {
    is Activity -> isChangingConfigurations
    is ContextWrapper -> baseContext.isChangingConfigurations()
    else -> false
}

/** The editor went to the background (`ON_STOP`): playback pauses, except during a configuration change (it keeps playing). */
fun PlaybackController.onEditorStopped(changingConfigurations: Boolean) {
    if (!changingConfigurations) pause()
}

/** The editor composition left: the recordings are unregistered and playback closed, except during a configuration change. */
fun PlaybackController.onEditorDisposed(changingConfigurations: Boolean) {
    if (changingConfigurations) return
    close()
    setTracks(emptyList())
}
