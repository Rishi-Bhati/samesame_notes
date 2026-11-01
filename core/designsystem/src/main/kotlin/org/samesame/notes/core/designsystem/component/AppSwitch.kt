package org.samesame.notes.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.theme.SameSameTheme

/**
 * One UI switch. Measured on ref #65: track 36.6 x 22dp pill (accent when on, #666660 when off),
 * white 20dp thumb with a 1.1dp inset.
 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) SameSameTheme.colors.accent else Color(0xFF666660), label = "track")
    val thumbX by animateDpAsState(if (checked) 15.5.dp else 1.1.dp, label = "thumb")
    Box(
        modifier
            .size(width = 36.6.dp, height = 22.dp)
            .background(track, CircleShape)
            .then(if (onCheckedChange != null) Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = thumbX).size(19.8.dp).background(Color(0xFFFCFCFC), CircleShape))
    }
}
