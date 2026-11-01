package org.samesame.notes.feature.share

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.export.ExportFormat

/** What Share offers, in menu order (the format popup of the list's Share action and the editor's Share). */
val ShareFormats: List<ExportFormat> = listOf(
    ExportFormat.SSNOTE, ExportFormat.SDOCX, ExportFormat.PDF, ExportFormat.DOCX, ExportFormat.PPTX, ExportFormat.IMAGES, ExportFormat.TXT,
)

/** Share for a page selection: the whole-note containers (.ssnote, .sdocx) cannot hold a subset, so they are left out. */
val PageShareFormats: List<ExportFormat> = ShareFormats.filter { it != ExportFormat.SSNOTE && it != ExportFormat.SDOCX }

private fun label(format: ExportFormat): Int = when (format) {
    ExportFormat.SSNOTE -> R.string.feature_share_menu_note
    ExportFormat.SDOCX -> R.string.feature_share_menu_document
    ExportFormat.PDF -> R.string.feature_share_menu_pdf
    ExportFormat.DOCX -> R.string.feature_share_menu_word
    ExportFormat.PPTX -> R.string.feature_share_menu_powerpoint
    ExportFormat.IMAGES -> R.string.feature_share_menu_image
    ExportFormat.TXT -> R.string.feature_share_menu_text
    ExportFormat.MARKDOWN -> R.string.feature_share_format_md
    ExportFormat.HTML -> R.string.feature_share_format_html
}

/** Popup that asks for the file format to share in. Put it inside the box it should hang off. */
@Composable
fun ShareFormatMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (ExportFormat) -> Unit) {
    AppDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ShareFormats.forEach { format -> AppMenuItem(stringResource(label(format)), { onPick(format) }) }
    }
}

/** The same choice as a centred dialog, for screens with no bottom bar to hang a popup off (the editor). */
@Composable
fun ShareFormatDialog(onDismiss: () -> Unit, onPick: (ExportFormat) -> Unit, formats: List<ExportFormat> = ShareFormats) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
            color = org.samesame.notes.core.designsystem.theme.SameSameTheme.colors.elevated,
        ) {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(vertical = 8.dp)) {
                formats.forEach { format -> AppMenuItem(stringResource(label(format)), { onPick(format) }) }
            }
        }
    }
}
