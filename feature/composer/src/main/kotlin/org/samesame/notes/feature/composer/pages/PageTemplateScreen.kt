package org.samesame.notes.feature.composer.pages

import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.canvas.TemplatePainter
import org.samesame.notes.feature.composer.panels.PanelSwitch

/**
 * Page template picker (`#55`, `#120`-`#123`): full-screen. Header 90.7 dp (title 21 sp bold centred 48.8 dp below the status bar,
 * back chevron centre 31 dp in), then edge-to-edge #171717 cards with a 28.6 dp radius, 21 dp apart: "Apply to all pages" (59 dp)
 * with the switch, "Default" (collapsible; title 17 sp, thumbnails 93.7 x 134.5 dp in 124.2 dp cells, rows 151.2 dp apart, the
 * selected one ringed 1.5 dp accent) and "Downloaded" (+, bin, chevron). A 61 dp Images / PDFs tab row sits at the bottom.
 * Applying a thumbnail sets the current page (or every page) template immediately. Own artwork via [TemplatePainter].
 */
@Composable
internal fun PageTemplateScreen(
    darkTheme: Boolean,
    selectedTemplate: PageTemplate,
    onApply: (PageTemplate, applyAll: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var applyAll by remember { mutableStateOf(false) }
    var selected by remember(selectedTemplate) { mutableStateOf(selectedTemplate) }
    var downloadedExpanded by remember { mutableStateOf(false) }
    var defaultExpanded by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }

    val colors = SameSameTheme.colors
    val cardShape = RoundedCornerShape(28.6.dp)
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(top = 24.8.dp, bottom = 18.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(start = 11.dp).size(40.dp).clip(CircleShape).clickable(onClick = onBack, role = Role.Button), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = colors.textPrimary)
                    }
                    Text(stringResource(R.string.feature_composer_page_template), color = colors.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 5.dp))
                }
                Row(
                    Modifier.fillMaxWidth().height(59.dp).clip(cardShape).background(colors.card).padding(start = 24.4.dp, end = 27.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.feature_composer_apply_all_pages), color = colors.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    PanelSwitch(applyAll) { applyAll = it }
                }
                Spacer(Modifier.height(21.dp))
                Column(Modifier.fillMaxWidth().clip(cardShape).background(colors.card)) {
                    Row(Modifier.fillMaxWidth().height(60.dp).clickable { defaultExpanded = !defaultExpanded }.padding(start = 24.4.dp, end = 37.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.feature_composer_default), color = colors.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(if (defaultExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown, null, Modifier.size(24.dp), tint = colors.textPrimary)
                    }
                    if (defaultExpanded) {
                        TemplateGrid(selected, darkTheme) { t -> selected = t; onApply(t, applyAll) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                Spacer(Modifier.height(21.dp))
                Column(Modifier.fillMaxWidth().clip(cardShape).background(colors.card)) {
                    Row(Modifier.fillMaxWidth().height(57.dp).clickable { downloadedExpanded = !downloadedExpanded }.padding(start = 24.4.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.feature_composer_downloaded), color = colors.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(AppIcons.Add, stringResource(R.string.feature_composer_add_page), Modifier.size(24.dp), tint = colors.textPrimary)
                        Spacer(Modifier.width(16.dp))
                        Icon(AppIcons.Trash, null, Modifier.size(24.dp), tint = colors.textSecondary)
                        Spacer(Modifier.width(16.dp))
                        Icon(if (downloadedExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown, null, Modifier.size(24.dp), tint = colors.textSecondary)
                    }
                    if (downloadedExpanded) {
                        Text(stringResource(R.string.feature_composer_coming_soon), color = colors.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 24.4.dp, bottom = 16.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().height(61.dp), verticalAlignment = Alignment.CenterVertically) {
                TabLabel(stringResource(R.string.feature_composer_tab_images), tab == 0, { tab = 0 }, Modifier.weight(1f).padding(start = 15.2.dp))
                TabLabel(stringResource(R.string.feature_composer_tab_pdfs), tab == 1, { tab = 1 }, Modifier.weight(1f).padding(end = 11.4.dp))
            }
        }
    }
}

/** Tab (#55): text 16.5 dp (bold, #FAFAFA, with a 2 dp underline as wide as the text when active; #A8A9A9 otherwise). */
@Composable
private fun TabLabel(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SameSameTheme.colors
    Box(modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Max)) {
            Text(text, color = if (active) colors.textPrimary else Color(0xFFA8A9A9), fontSize = fixedSp(16.5f), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.height(4.5.dp))
            Box(Modifier.height(2.dp).fillMaxWidth().background(if (active) colors.textPrimary else Color.Transparent))
        }
    }
}

@Composable
private fun TemplateGrid(selected: PageTemplate, darkTheme: Boolean, onPick: (PageTemplate) -> Unit) {
    val templates = PageTemplate.entries
    Column(Modifier.padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(16.7.dp)) {
        templates.chunked(3).forEach { row ->
            Row(Modifier.padding(horizontal = 19.4.dp)) {
                row.forEach { t ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        TemplateThumb(t, selected == t, darkTheme, Modifier.width(93.7.dp)) { onPick(t) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TemplateThumb(template: PageTemplate, selected: Boolean, darkTheme: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    val ratio = 1f / PageRatio.A4Portrait.heightOverWidth
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier.aspectRatio(ratio).clip(shape)
            .background(if (darkTheme) Color(0xFF010101) else Color.White)
            .then(if (selected) Modifier.border(1.5.dp, colors.accent, shape) else Modifier)
            .clickable(onClick = onClick, role = Role.Button),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pageW = Limits.PAGE_WIDTH_PU
            val pageH = pageW * PageRatio.A4Portrait.heightOverWidth
            val f = size.width / pageW
            val line = if (darkTheme) Color(0x66FFFFFF) else Color(0x33000000)
            withTransform({ scale(f, f, Offset.Zero) }) {
                TemplatePainter.draw(this, template, Size(pageW, pageH), line)
            }
        }
    }
}
