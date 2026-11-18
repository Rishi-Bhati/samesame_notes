package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import dev.oneuiproject.oneui.R as Oui

/** The widget id a configure activity was started for, or [AppWidgetManager.INVALID_APPWIDGET_ID]. */
internal fun ComponentActivity.requestedWidgetId(): Int =
    intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

internal fun configureIntent(context: android.content.Context, cls: Class<*>, widgetId: Int): Intent =
    Intent(context, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

/** The result the launcher expects: the id, with OK once configured or CANCELED to drop a widget that was just added. */
internal fun ComponentActivity.setConfigureResult(widgetId: Int, ok: Boolean) {
    setResult(if (ok) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
}

/**
 * The size the launcher shows [widgetId] at in portrait (dp), or [fallback] before it is placed: the settings preview draws
 * the layout the widget really has.
 */
internal fun ComponentActivity.widgetSize(widgetId: Int, fallback: DpSize): DpSize {
    val options = runCatching { AppWidgetManager.getInstance(this).getAppWidgetOptions(widgetId) }.getOrNull() ?: return fallback
    val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    return if (w > 0 && h > 0) DpSize(w.dp, h.dp) else fallback
}

/** Themed (app theme setting, else the system one), edge-to-edge host for a configure screen. */
internal fun ComponentActivity.setConfigContent(content: @Composable () -> Unit) {
    enableEdgeToEdge()
    val settingsFlow = WidgetEntryPoint.get(this).settings().settings
    setContent {
        val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
        SameSameTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme()), font = settings.font) {
            Box(Modifier.fillMaxSize().background(SameSameTheme.colors.background).systemBarsPadding()) { content() }
        }
    }
}

/**
 * The frame of every configure screen: a top bar (close + title), the scrolling [content], and, when [onSave] is given, a
 * bottom bar with Cancel and Save.
 */
@Composable
internal fun ConfigScaffold(
    title: String,
    onClose: () -> Unit,
    onSave: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = SameSameTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(Oui.drawable.ic_oui_close), contentDescription = stringResource(R.string.widget_close), tint = c.icon, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(title, color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().weight(1f), content = content)
        if (onSave != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillButton(stringResource(R.string.widget_cancel), filled = false, onClick = onClose, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.widget_save), filled = true, onClick = onSave, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun PillButton(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SameSameTheme.colors
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (filled) c.accent else c.pill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** A rounded group of settings with a small heading above it. */
@Composable
internal fun SectionCard(heading: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = SameSameTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (heading != null) {
            Text(
                heading, color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card), content = content)
    }
}

/** One choice row of a picker card: optional leading icon, title, optional subtitle, selected tick or trailing slot. */
@Composable
internal fun ChoiceRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Int? = null,
    iconTint: Color? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    indent: Dp = 0.dp,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = SameSameTheme.colors
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = (iconTint ?: c.icon).copy(alpha = alpha), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary.copy(alpha = alpha), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = c.textSecondary.copy(alpha = alpha), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            // Radio-style mark: an accent disc with a white centre.
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

@Composable
internal fun CardDivider(start: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start, end = 16.dp).height(1.dp).background(SameSameTheme.colors.divider))
}
