package org.samesame.notes.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.backup.BACKUP_ONCE_WORK
import org.samesame.notes.core.data.backup.BackupInfo
import org.samesame.notes.core.data.backup.BackupRepository
import org.samesame.notes.core.data.backup.BackupScheduler
import org.samesame.notes.core.data.backup.KEY_PROGRESS_DONE
import org.samesame.notes.core.data.backup.KEY_PROGRESS_NAME
import org.samesame.notes.core.data.backup.KEY_PROGRESS_TOTAL
import org.samesame.notes.core.data.backup.RestoreProgress
import org.samesame.notes.core.data.backup.RestoreResult
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.BackupSchedule
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

internal data class BackupRunUi(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val name: String = "",
)

internal data class RestoreRunUi(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val name: String = "",
    val result: RestoreResult? = null,
    val error: String? = null,
)

@HiltViewModel
internal class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepo: AppSettingsRepository,
    private val backupRepo: BackupRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val backupRun: StateFlow<BackupRunUi> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(BACKUP_ONCE_WORK)
        .map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished } ?: infos.maxByOrNull { it.id.toString() }
            if (info == null || info.state.isFinished) BackupRunUi()
            else BackupRunUi(
                running = true,
                done = info.progress.getInt(KEY_PROGRESS_DONE, 0),
                total = info.progress.getInt(KEY_PROGRESS_TOTAL, 0),
                name = info.progress.getString(KEY_PROGRESS_NAME).orEmpty(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupRunUi())

    private val _restore = MutableStateFlow(RestoreRunUi())
    val restore: StateFlow<RestoreRunUi> = _restore.asStateFlow()

    fun setFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch {
            settingsRepo.update { it.copy(backupFolderUri = uri.toString(), backupLastError = null) }
            BackupScheduler.reschedule(context, settingsRepo)
        }
    }

    fun clearFolderError() {
        viewModelScope.launch { settingsRepo.update { it.copy(backupLastError = null) } }
    }

    fun setSchedule(schedule: BackupSchedule) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(backupSchedule = schedule) }
            BackupScheduler.reschedule(context, settingsRepo)
        }
    }

    fun setOnlyWhileCharging(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(backupOnlyWhileCharging = enabled) }
            BackupScheduler.reschedule(context, settingsRepo)
        }
    }

    fun setKeepCount(count: Int) {
        viewModelScope.launch { settingsRepo.update { it.copy(backupKeepCount = count.coerceIn(1, 10)) } }
    }

    fun backupNow() {
        if (settings.value.backupFolderUri.isNullOrBlank()) return
        BackupScheduler.enqueueNow(context)
    }

    suspend fun listBackups(): List<BackupInfo> = withContext(Dispatchers.IO) {
        val folder = backupRepo.folderFromSettingsUri(settings.value.backupFolderUri) ?: return@withContext emptyList()
        runCatching { backupRepo.listBackups(folder) }.getOrDefault(emptyList())
    }

    fun folderDisplayName(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return backupRepo.folderFromSettingsUri(uriString)?.displayName
    }

    fun restore(backupFolderName: String) {
        val current = _restore.value
        if (current.running) return
        viewModelScope.launch {
            _restore.value = RestoreRunUi(running = true)
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRepo.restoreFromSettingsBackup(backupFolderName) { p ->
                        _restore.value = RestoreRunUi(running = true, done = p.done, total = p.total, name = p.name)
                    }
                }
                _restore.value = RestoreRunUi(result = result)
            } catch (e: Exception) {
                _restore.value = RestoreRunUi(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismissRestoreResult() {
        if (!_restore.value.running) _restore.value = RestoreRunUi()
    }
}

@Composable
internal fun BackupPage(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backupRun by viewModel.backupRun.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scheduleMenu by rememberSaveable { mutableStateOf(false) }
    var keepMenu by rememberSaveable { mutableStateOf(false) }
    var showBackups by rememberSaveable { mutableStateOf(false) }
    var backups by remember { mutableStateOf<List<BackupInfo>?>(null) }
    var confirmBackup by remember { mutableStateOf<BackupInfo?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setFolder(uri)
    }

    LaunchedEffect(showBackups, settings.backupFolderUri) {
        if (showBackups) {
            backups = null
            backups = viewModel.listBackups()
        }
    }

    SettingsScaffold(stringResource(R.string.feature_settings_backup), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.feature_settings_backup_folder))
            GroupCard {
                val folderLabel = viewModel.folderDisplayName(settings.backupFolderUri)
                    ?: stringResource(R.string.feature_settings_backup_folder_none)
                SettingsRow(
                    stringResource(R.string.feature_settings_backup_folder),
                    summary = folderLabel,
                    onClick = { folderPicker.launch(null) },
                )
                RowDivider()
                val canRun = !settings.backupFolderUri.isNullOrBlank() && !backupRun.running
                SettingsRow(
                    stringResource(R.string.feature_settings_backup_now),
                    summary = if (backupRun.running) backupProgressText(backupRun.done, backupRun.total, backupRun.name) else null,
                    onClick = if (canRun) ({ viewModel.backupNow() }) else null,
                )
            }

            SectionHeader(stringResource(R.string.feature_settings_backup_automatic))
            GroupCard {
                Box {
                    SettingsRow(
                        stringResource(R.string.feature_settings_backup_automatic),
                        summary = scheduleLabel(settings.backupSchedule),
                        onClick = { scheduleMenu = true },
                    )
                    AppDropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                        BackupSchedule.entries.forEach { s ->
                            AppMenuItem(scheduleLabel(s), { scheduleMenu = false; viewModel.setSchedule(s) }, checked = settings.backupSchedule == s)
                        }
                    }
                }
                RowDivider()
                SettingsRow(
                    stringResource(R.string.feature_settings_backup_only_charging),
                    checked = settings.backupOnlyWhileCharging,
                    onChecked = { viewModel.setOnlyWhileCharging(it) },
                )
                RowDivider()
                Box {
                    SettingsRow(
                        stringResource(R.string.feature_settings_backup_keep_last),
                        summary = pluralStringResource(R.plurals.feature_settings_backup_keep_count, settings.backupKeepCount.coerceIn(1, 10), settings.backupKeepCount.coerceIn(1, 10)),
                        onClick = { keepMenu = true },
                    )
                    AppDropdownMenu(expanded = keepMenu, onDismissRequest = { keepMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                        listOf(3, 5, 10).forEach { n ->
                            AppMenuItem(
                                pluralStringResource(R.plurals.feature_settings_backup_keep_count, n, n),
                                { keepMenu = false; viewModel.setKeepCount(n) },
                                checked = settings.backupKeepCount.coerceIn(1, 10) == n,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            GroupCard {
                SettingsRow(lastBackupSummary(settings, context))
                RowDivider()
                SettingsRow(
                    stringResource(R.string.feature_settings_backup_restore),
                    onClick = if (!settings.backupFolderUri.isNullOrBlank()) ({ showBackups = true }) else null,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showBackups) {
        val list = backups
        AlertDialog(
            onDismissRequest = { showBackups = false },
            title = { Text(stringResource(R.string.feature_settings_backup_restore_pick)) },
            text = {
                if (list == null) {
                    Text(stringResource(R.string.feature_settings_backup_restore_progress))
                } else if (list.isEmpty()) {
                    Text(stringResource(R.string.feature_settings_backup_restore_none))
                } else {
                    Column {
                        list.forEach { info ->
                            Text(
                                backupInfoLabel(info, context),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    showBackups = false
                                    confirmBackup = info
                                }.padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBackups = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    val pending = confirmBackup
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmBackup = null },
            title = { Text(stringResource(R.string.feature_settings_backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.feature_settings_backup_restore_confirm_message) + "\n\n" + backupInfoLabel(pending, context)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBackup = null
                    viewModel.restore(pending.folderName)
                }) { Text(stringResource(R.string.feature_settings_backup_restore_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBackup = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    if (restore.running) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.feature_settings_backup_restore_progress)) },
            text = { Text(backupProgressText(restore.done, restore.total, restore.name)) },
            confirmButton = {},
        )
    }

    val result = restore.result
    if (result != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreResult() },
            title = { Text(stringResource(R.string.feature_settings_backup_restore)) },
            text = {
                val text = stringResource(R.string.feature_settings_backup_restore_result_detail, result.restoredNotes, result.skippedNotes)
                val problems = result.problems.take(5).joinToString("\n").takeIf { it.isNotBlank() }
                Text(if (problems == null) text else "$text\n\n$problems")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRestoreResult() }) { Text(stringResource(R.string.feature_settings_ok)) }
            },
        )
    }

    val err = restore.error
    if (err != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreResult() },
            title = { Text(stringResource(R.string.feature_settings_backup_restore)) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRestoreResult() }) { Text(stringResource(R.string.feature_settings_ok)) }
            },
        )
    }
}

@Composable
private fun scheduleLabel(schedule: BackupSchedule): String = stringResource(
    when (schedule) {
        BackupSchedule.Off -> R.string.feature_settings_backup_schedule_off
        BackupSchedule.Daily -> R.string.feature_settings_backup_schedule_daily
        BackupSchedule.Weekly -> R.string.feature_settings_backup_schedule_weekly
    },
)

private fun backupProgressText(done: Int, total: Int, name: String): String = buildString {
    if (total > 0) append("$done/$total")
    if (name.isNotBlank()) {
        if (isNotEmpty()) append(" · ")
        append(name)
    }
}

private fun backupInfoLabel(info: BackupInfo, context: Context): String {
    val time = if (info.createdTime > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info.createdTime)) else info.folderName
    return "$time (${info.noteCount})"
}

@Composable
private fun lastBackupSummary(settings: AppSettings, context: Context): String {
    val err = settings.backupLastError
    val lastTime = settings.backupLastTime
    return when {
        err == "FOLDER_UNAVAILABLE" -> stringResource(R.string.feature_settings_backup_folder_unavailable)
        !err.isNullOrBlank() -> stringResource(R.string.feature_settings_backup_last_error, err)
        lastTime == null -> stringResource(R.string.feature_settings_backup_last_never)
        else -> {
            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastTime))
            val count = settings.backupLastNoteCount ?: 0
            stringResource(R.string.feature_settings_backup_last_ok, time, count)
        }
    }
}
