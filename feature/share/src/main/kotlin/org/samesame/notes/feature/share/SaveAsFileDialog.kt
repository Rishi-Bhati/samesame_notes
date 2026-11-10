package org.samesame.notes.feature.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.core.export.ExportFormat

private val SaveFormats = listOf(
    ExportFormat.SSNOTE to R.string.feature_share_format_ssnote,
    ExportFormat.SDOCX to R.string.feature_share_format_sdocx,
    ExportFormat.PDF to R.string.feature_share_format_pdf,
    ExportFormat.IMAGES to R.string.feature_share_format_images,
    ExportFormat.TXT to R.string.feature_share_format_txt,
    ExportFormat.MARKDOWN to R.string.feature_share_format_md,
    ExportFormat.HTML to R.string.feature_share_format_html,
    ExportFormat.DOCX to R.string.feature_share_format_docx,
    ExportFormat.PPTX to R.string.feature_share_format_pptx,
)

/** Save as file for a page selection: every format except the whole-note containers (.ssnote, .sdocx). */
val PageSaveFormats: Set<ExportFormat> = setOf(
    ExportFormat.PDF, ExportFormat.IMAGES, ExportFormat.TXT, ExportFormat.MARKDOWN, ExportFormat.HTML, ExportFormat.DOCX, ExportFormat.PPTX,
)

/**
 * "Save as file" (editor `#58`, and the notes list's More menu): choose the target format. Shared by the editor and the
 * list so both look the same: a 300.6 dp card, 26 dp corners, bold 19.8 sp title, radio-style rows, Cancel | Done.
 */
@Composable
fun SaveAsFileDialog(
    onDismiss: () -> Unit,
    onConfirm: (ExportFormat) -> Unit,
    /** The rows offered, in [SaveFormats] order (a page selection leaves out the whole-note containers). */
    formats: Set<ExportFormat> = SaveFormats.mapTo(LinkedHashSet()) { it.first },
    /** Size of the library files the notes link to; above 0 the .ssnote choice offers "Include linked files (size)". */
    linkedFilesBytes: Long = 0L,
    /** Called with the checkbox state (default on) whenever it changes. */
    onIncludeLinkedFilesChange: (Boolean) -> Unit = {},
) {
    var includeFiles by remember { mutableStateOf(true) }
    val rows = SaveFormats.filter { it.first in formats }
    var selected by remember { mutableStateOf(rows.firstOrNull()?.first ?: ExportFormat.SSNOTE) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 300.6.dp).fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = SameSameTheme.colors.dialog,
            border = if (SameSameTheme.colors.isDark) null else BorderStroke(0.5.dp, Color(0xFFC7C7C7)),
            shadowElevation = 2.dp,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = 16.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.feature_share_save_as_file),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = fixedSp(19.8f), fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                    )
                }
                Column(Modifier.heightIn(max = 420.dp).weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    rows.forEach { (format, label) ->
                        Row(
                            Modifier.fillMaxWidth().height(52.dp)
                                .clickable(role = Role.RadioButton) { selected = format }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(label), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (selected == format) Icon(AppIcons.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (selected == ExportFormat.SSNOTE && linkedFilesBytes > 0) {
                    val size = android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, linkedFilesBytes)
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp)
                            .clickable(role = Role.Checkbox) { includeFiles = !includeFiles; onIncludeLinkedFilesChange(includeFiles) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.feature_share_include_linked_files, size), color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        if (includeFiles) Icon(AppIcons.Check, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                DialogActions(onDismiss, { onConfirm(selected) }, Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Cancel | Done row of the editor's floating dialogs (#43: 1 x 16 dp divider, 56 dp tall, bold 19.8 sp). */
@Composable
internal fun DialogActions(onCancel: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, doneLabel: String? = null) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionText(stringResource(R.string.feature_share_cancel), onCancel, Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(16.dp).background(MaterialTheme.colorScheme.onSurface))
        ActionText(doneLabel ?: stringResource(R.string.feature_share_done), onDone, Modifier.weight(1f))
    }
}

@Composable
private fun ActionText(text: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(56.dp).clickable(onClick = onClick, role = Role.Button), contentAlignment = Alignment.Center) {
        Text(text, fontSize = fixedSp(19.8f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}
