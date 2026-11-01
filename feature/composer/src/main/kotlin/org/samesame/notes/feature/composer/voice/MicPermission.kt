package org.samesame.notes.feature.composer.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.samesame.notes.feature.composer.R

/** Insert > Voice recording permission flow ([VoicePermissionPolicy]); call [begin], and show [Dialogs] in the composition. */
@Stable
internal class MicPermission(
    private val context: Context,
    private val request: (Array<String>) -> Unit,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit,
) {
    var requestedBefore by mutableStateOf(false)
    var rationaleOpen by mutableStateOf(false)

    private fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun shouldExplain(): Boolean {
        val activity = context.findActivity() ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    }

    fun begin() {
        when (VoicePermissionPolicy.next(granted(), shouldExplain(), requestedBefore)) {
            MicStep.Start -> onGranted()
            MicStep.Rationale -> rationaleOpen = true
            MicStep.Request -> ask()
            MicStep.Denied -> onDenied()
        }
    }

    fun ask() {
        requestedBefore = true
        // The notification permission is only for the recording notification; recording works without it.
        request(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO),
        )
    }

    fun onResult(recordAudio: Boolean) {
        if (recordAudio) onGranted() else onDenied()
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Composable
    fun Dialogs() {
        if (!rationaleOpen) return
        AlertDialog(
            onDismissRequest = { rationaleOpen = false },
            title = { Text(stringResource(R.string.feature_composer_voice_permission_title)) },
            text = { Text(stringResource(R.string.feature_composer_voice_permission_message)) },
            confirmButton = {
                TextButton({ rationaleOpen = false; ask() }) { Text(stringResource(R.string.feature_composer_voice_permission_allow)) }
            },
            dismissButton = {
                TextButton({ rationaleOpen = false }) { Text(stringResource(R.string.feature_composer_voice_cancel)) }
            },
        )
    }
}

@Composable
internal fun rememberMicPermission(onGranted: () -> Unit, onDenied: () -> Unit): MicPermission {
    val context = LocalContext.current
    val granted = rememberUpdatedState(onGranted)
    val denied = rememberUpdatedState(onDenied)
    var holder: MicPermission? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        holder?.onResult(result[Manifest.permission.RECORD_AUDIO] == true)
    }
    val saved = rememberSaveable { mutableStateOf(false) }
    val permission = remember(context) {
        MicPermission(context, { launcher.launch(it) }, { granted.value() }, { denied.value() })
    }
    holder = permission
    permission.requestedBefore = permission.requestedBefore || saved.value
    saved.value = permission.requestedBefore
    return permission
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
