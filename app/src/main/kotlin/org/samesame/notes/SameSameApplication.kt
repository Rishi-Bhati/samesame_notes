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
        val started = System.currentTimeMillis()
        // Leftovers of earlier runs (share exports, decrypted working copies, orphaned recordings); off the main thread.
        startup.run { VoiceRecordingHub.discardOrphans(this, started) }
    }
}
