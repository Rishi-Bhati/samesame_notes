package org.samesame.notes.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.cappedSp

/** Centred empty-list message: a primary line and an optional hint. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, hint: String? = null) {
    val colors = SameSameTheme.colors
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, fontSize = cappedSp(17f), color = colors.icon, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, fontSize = cappedSp(13f), color = colors.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp))
        }
    }
}
