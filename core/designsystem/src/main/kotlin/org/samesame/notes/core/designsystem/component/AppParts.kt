package org.samesame.notes.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme

/** Rounded card surface. */
@Composable
fun AppCard(modifier: Modifier = Modifier, cornerRadius: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable () -> Unit) {
    Box(modifier.background(SameSameTheme.colors.card, RoundedCornerShape(cornerRadius))) { content() }
}

/** Round selection indicator: outlined when off, accent-filled with a check when on. */
@Composable
fun SelectionCircle(checked: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 21.dp) {
    val colors = SameSameTheme.colors
    Box(
        modifier
            .size(size)
            .then(
                if (checked) Modifier.background(colors.accent, CircleShape)
                else Modifier.border(1.dp, colors.checkOutline, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(AppIcons.Check, null, tint = Color.Black, modifier = Modifier.size(size * 0.7f))
    }
}

class BottomBarItem(val icon: ImageVector, val label: String, val onClick: () -> Unit, val enabled: Boolean = true)

/** Selection-mode action bar: icon over label, evenly spaced. */
@Composable
fun BottomActionBar(items: List<BottomBarItem>, modifier: Modifier = Modifier) {
    val colors = SameSameTheme.colors
    Row(
        modifier.fillMaxWidth().background(colors.background).navigationBarsPadding().height(60.dp).padding(horizontal = 5.3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val enabled = item.enabled
            val iconTint = if (enabled) colors.icon else colors.icon.copy(alpha = 0.5f)
            val labelTint = if (enabled) colors.textSecondary else colors.textSecondary.copy(alpha = 0.5f)
            Column(
                Modifier.weight(1f).fillMaxSize().clickable(enabled = enabled, onClick = item.onClick).padding(top = 9.2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Ref #17: icon centre 63.6dp / label centre 40.4dp above the screen bottom, label 12sp.
                verticalArrangement = Arrangement.spacedBy(3.2.dp, Alignment.CenterVertically),
            ) {
                Icon(item.icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                Text(item.label, style = SameSameText.SectionSubtitle.copy(fontSize = 12.sp, lineHeight = 16.sp), color = labelTint, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Round FAB with an accent-coloured icon on the elevated surface colour. */
@Composable
fun AppFab(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = 24.dp, bottom = 24.dp)
            .size(56.dp)
            .background(SameSameTheme.colors.elevated, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = SameSameTheme.colors.accent, modifier = Modifier.size(24.dp))
    }
}

/** Secondary pill button (e.g. "Manage folders"). */
@Composable
fun AppPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(36.dp)
            .background(SameSameTheme.colors.pill, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SameSameText.Button.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)), color = SameSameTheme.colors.textPrimary)
    }
}
