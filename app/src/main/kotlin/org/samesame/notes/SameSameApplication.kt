package org.samesame.notes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.samesame.notes.core.data.StartupMaintenance
import org.samesame.notes.feature.composer.voice.VoiceRecordingHub
import javax.inject.Inject

@HiltAndroidApp
class SameSameApplication : Application() {
    @Inject lateinit var startup: StartupMaintenance

    override fun onCreate() {
        super.onCreate()
        enableStrictModeIfAsked()
        val started = System.currentTimeMillis()
        // Leftovers of earlier runs (share exports, decrypted working copies, orphaned recordings); off the main thread.
        startup.run { VoiceRecordingHub.discardOrphans(this, started) }
    }

    /**
     * Debug builds only, and only when `files/strictmode` exists (`adb shell run-as <package> touch files/strictmode`):
     * logs main-thread disk and network access and slow calls under the `StrictMode` tag, for performance work. Off by
     * default so everyday debug use is not slowed down by the stack traces it collects.
     */
    private fun enableStrictModeIfAsked() {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!java.io.File(filesDir, "strictmode").exists()) return
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                .detectCustomSlowCalls().penaltyLog().build(),
        )
    }
}
