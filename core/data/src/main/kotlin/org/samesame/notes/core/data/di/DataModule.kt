package org.samesame.notes.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.samesame.notes.core.data.Clock
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.DataStoreAppSettingsRepository
import org.samesame.notes.core.data.repository.DefaultFolderRepository
import org.samesame.notes.core.data.repository.DefaultNoteRepository
import org.samesame.notes.core.data.repository.DataStoreEditorPreferencesRepository
import org.samesame.notes.core.data.repository.EditorPreferencesRepository
import org.samesame.notes.core.data.repository.FolderRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.DefaultLockRepository
import org.samesame.notes.core.data.repository.LockRepository
import org.samesame.notes.core.data.lock.AndroidBiometricKeyStore
import org.samesame.notes.core.data.lock.FileLockConfigStorage
import org.samesame.notes.core.security.BiometricKeyStore
import org.samesame.notes.core.security.LockVault
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.samesame.notes.core.data.repository.DataStoreUserPreferencesRepository
import org.samesame.notes.core.data.repository.UserPreferencesRepository
import org.samesame.notes.core.data.repository.DataStoreSearchHistoryRepository
import org.samesame.notes.core.data.repository.SearchHistoryRepository
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that outlives screens, for work that must finish (e.g. the final autosave). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds abstract fun noteRepository(impl: DefaultNoteRepository): NoteRepository
    @Binds abstract fun folderRepository(impl: DefaultFolderRepository): FolderRepository
    @Binds abstract fun userPreferences(impl: DataStoreUserPreferencesRepository): UserPreferencesRepository

    @Binds abstract fun searchHistory(impl: DataStoreSearchHistoryRepository): SearchHistoryRepository

    @Binds abstract fun lockRepository(impl: DefaultLockRepository): LockRepository

    @Binds abstract fun biometricKeyStore(impl: AndroidBiometricKeyStore): BiometricKeyStore

    @Binds abstract fun appSettings(impl: DataStoreAppSettingsRepository): AppSettingsRepository

    @Binds abstract fun editorPreferences(impl: DataStoreEditorPreferencesRepository): EditorPreferencesRepository

    companion object {
        @Provides @Singleton fun noteExporter(): org.samesame.notes.core.export.NoteExporter =
            org.samesame.notes.core.export.NoteExporter().apply {
                register(
                    org.samesame.notes.core.export.ExportFormat.SDOCX,
                    org.samesame.notes.core.export.FormatExporter { source, _, out ->
                        org.samesame.notes.core.data.repository.SdocxExport.exportNative(source, out)
                        org.samesame.notes.core.export.ExportTarget(
                            org.samesame.notes.core.data.repository.SdocxExport.EXTENSION,
                            org.samesame.notes.core.data.repository.SdocxExport.MIME,
                        )
                    },
                )
            }

        @Provides @Singleton fun lockVault(@ApplicationContext context: Context): LockVault =
            LockVault(
                FileLockConfigStorage(context),
                deviceSealer = org.samesame.notes.core.data.lock.AndroidDeviceKeySealer(),
                attempts = org.samesame.notes.core.security.AttemptLimiter(
                    FileLockConfigStorage(java.io.File(java.io.File(context.noBackupFilesDir, "lock"), "attempts.bin")),
                ),
            )

        @Provides @Singleton fun previewFailures(@ApplicationContext context: Context): org.samesame.notes.core.data.repository.PreviewFailureStore =
            org.samesame.notes.core.data.repository.PreviewFailureStore(java.io.File(context.noBackupFilesDir, "preview-failures"))

        @Provides @Singleton fun previewDiskCache(@ApplicationContext context: Context): org.samesame.notes.core.data.repository.PreviewDiskCache =
            org.samesame.notes.core.data.repository.PreviewDiskCache(context.cacheDir)

        @Provides fun clock(): Clock = Clock.System

        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
