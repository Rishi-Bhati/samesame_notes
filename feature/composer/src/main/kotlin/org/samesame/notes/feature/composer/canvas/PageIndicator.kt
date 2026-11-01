package org.samesame.notes.feature.composer.canvas

import org.samesame.notes.core.designsystem.theme.fixedSp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "1/1" (12 sp light, #4E4E4E in dark; #26: glyphs 991..1033 px, 24 px tall) at the bottom right of the page area (#26, #61); announced as "Page x of y". */
@Composable
fun PageIndicator(current: Int, total: Int, dark: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = "$current/$total",
        color = if (dark) Color(0xFF4E4E4E) else Color(0xFF8A8A8A),
        fontSize = fixedSp(11.8f), // fixed size (no font-scale): measured 11.8 dp
        fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .semantics { contentDescription = "Page $current of $total" },
    )
}
