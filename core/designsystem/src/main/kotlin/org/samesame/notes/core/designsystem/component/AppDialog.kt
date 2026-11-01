package org.samesame.notes.core.designsystem.component

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.samesame.notes.core.designsystem.R
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme

/**
 * One UI style dialog: rounded dark sheet, optional bold title, and a row of borderless bold
 * buttons separated by a thin divider. With [atBottom] the sheet sits at the bottom of the screen.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    dismissLabel: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    atBottom: Boolean = false,
    confirmEnabled: Boolean = true,
    /** The dismiss button's action; by default the same as [onDismissRequest] (outside tap and system Back). */
    onDismissClick: () -> Unit = onDismissRequest,
    content: @Composable () -> Unit = {},
) {
    val colors = SameSameTheme.colors
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(R.style.SameSameDialogWindowAnimation)
            if (atBottom) window?.setGravity(Gravity.BOTTOM)
        }
        Column(
            modifier
                .padding(horizontal = 10.dp, vertical = 16.dp)
                .then(if (atBottom) Modifier.navigationBarsPadding() else Modifier)
                .fillMaxWidth()
                .background(colors.dialog, RoundedCornerShape(28.dp))
                .padding(start = 24.dp, end = 24.dp, top = 26.5.dp, bottom = 13.5.dp),
        ) {
            if (title != null) {
                Text(title, style = SameSameText.Button, color = colors.textPrimary, modifier = Modifier.padding(bottom = 23.2.dp))
            }
            content()
            // Ref #14/#23: like a weighted wrap_content LinearLayout, each button gets its text width plus an
            // equal share of the leftover width; divider 1dp x 16dp #4C4C4C.
            DialogButtonRow(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                DialogButton(dismissLabel, onDismissClick, Modifier)
                Box(Modifier.width(1.dp).height(16.dp).background(Color(0xFF4C4C4C)))
                DialogButton(confirmLabel, onConfirm, Modifier, enabled = confirmEnabled)
            }
        }
    }
}

@Composable
private fun DialogButtonRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val total = constraints.maxWidth
        val (left, divider, right) = measurables
        val dv = divider.measure(Constraints())
        val lw = left.maxIntrinsicWidth(Int.MAX_VALUE)
        val rw = right.maxIntrinsicWidth(Int.MAX_VALUE)
        val extra = ((total - dv.width - lw - rw) / 2).coerceAtLeast(0)
        val w1 = if (lw + extra + rw + extra + dv.width <= total) lw + extra else (total - dv.width) / 2
        val w2 = total - dv.width - w1
        val h = 48.dp.roundToPx()
        val lp = left.measure(Constraints.fixed(w1, h))
        val rp = right.measure(Constraints.fixed(w2, h))
        layout(total, h) {
            lp.placeRelative(0, 0)
            dv.placeRelative(w1, (h - dv.height) / 2)
            rp.placeRelative(w1 + dv.width, 0)
        }
    }
}

@Composable
private fun DialogButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    Box(modifier.padding(horizontal = 4.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = SameSameText.Button.copy(fontSize = 18.sp),
            color = if (enabled) SameSameTheme.colors.textPrimary else SameSameTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
