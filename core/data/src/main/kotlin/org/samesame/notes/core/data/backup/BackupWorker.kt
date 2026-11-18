package org.samesame.notes.core.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.model.BackupSchedule
import java.util.concurrent.TimeUnit

const val BACKUP_PERIODIC_WORK = "samesame_backup_periodic"
const val BACKUP_ONCE_WORK = "samesame_backup_once"

const val KEY_PROGRESS_DONE = "progress_done"
const val KEY_PROGRESS_TOTAL = "progress_total"
const val KEY_PROGRESS_NAME = "progress_name"
const val KEY_RESULT_NOTES = "result_notes"
const val KEY_RESULT_FOLDER = "result_folder"
const val KEY_RESULT_ERROR = "result_error"
const val KEY_FOLDER_UNAVAILABLE = "folder_unavailable"

class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun backupRepository(): BackupRepository
        fun appSettingsRepository(): AppSettingsRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val repo = deps.backupRepository()
        val settings = deps.appSettingsRepository().settings.first()
        val folder = repo.folderFromSettingsUri(settings.backupFolderUri)
            ?: return Result.failure(workDataOf(KEY_FOLDER_UNAVAILABLE to true, KEY_RESULT_ERROR to "FOLDER_UNAVAILABLE"))
        val result = repo.runBackup(folder) { p ->
            setProgressAsync(workDataOf(KEY_PROGRESS_DONE to p.done, KEY_PROGRESS_TOTAL to p.total, KEY_PROGRESS_NAME to p.name))
        }
        return when (result) {
            is BackupRunResult.Success -> Result.success(
                workDataOf(KEY_RESULT_NOTES to result.noteCount, KEY_RESULT_FOLDER to result.folderName)
            )
            is BackupRunResult.Failure -> Result.failure(
                workDataOf(
                    KEY_RESULT_ERROR to result.error,
                    KEY_FOLDER_UNAVAILABLE to (result.error == "FOLDER_UNAVAILABLE"),
                )
            )
        }
    }
}

object BackupScheduler {
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BACKUP_ONCE_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BACKUP_ONCE_WORK, ExistingWorkPolicy.KEEP, req)
    }

    suspend fun reschedule(context: Context, settingsRepo: AppSettingsRepository) {
        val s = settingsRepo.settings.first()
        reschedule(context, s.backupSchedule, s.backupOnlyWhileCharging, s.backupFolderUri)
    }

    fun reschedule(context: Context, schedule: BackupSchedule, onlyCharging: Boolean, folderUri: String?) {
        val wm = WorkManager.getInstance(context)
        if (schedule == BackupSchedule.Off || folderUri.isNullOrBlank()) {
            wm.cancelUniqueWork(BACKUP_PERIODIC_WORK)
            return
        }
        val interval = when (schedule) {
            BackupSchedule.Daily -> 1L
            BackupSchedule.Weekly -> 7L
            BackupSchedule.Off -> 1L
        }
        val constraints = Constraints.Builder()
            .setRequiresCharging(onlyCharging)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val req = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.DAYS)
            .setConstraints(constraints)
            .addTag(BACKUP_PERIODIC_WORK)
            .build()
        wm.enqueueUniquePeriodicWork(BACKUP_PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
