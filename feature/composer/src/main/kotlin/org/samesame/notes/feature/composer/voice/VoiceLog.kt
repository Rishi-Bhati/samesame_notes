package org.samesame.notes.feature.composer.voice

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Voice playback / recording trace for device checks: `adb logcat -s SameSameVoice`. Only debuggable builds log;
 * [init] reads that from the app (feature modules have no BuildConfig).
 */
object VoiceLog {
    const val TAG = "SameSameVoice"

    @Volatile var enabled = false
        private set

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(message: String) {
        if (enabled) Log.d(TAG, message)
    }
}
