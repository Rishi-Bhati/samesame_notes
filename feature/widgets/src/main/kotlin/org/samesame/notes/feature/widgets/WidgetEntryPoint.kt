package org.samesame.notes.feature.widgets

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.WidgetPinStore

/** Receivers, Glance widgets and configure activities are not Hilt components: they reach the singletons through this. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun notes(): NoteRepository
    fun folders(): FolderRepository
    fun pins(): WidgetPinStore
    fun settings(): AppSettingsRepository
    fun updater(): WidgetUpdater

    companion object {
        fun get(context: Context): WidgetEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
    }
}
