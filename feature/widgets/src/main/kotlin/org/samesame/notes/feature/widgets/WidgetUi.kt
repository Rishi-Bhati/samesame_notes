package org.samesame.notes.feature.widgets

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColor
import dev.oneuiproject.oneui.R as Oui

/** Spacing of every widget: content inset from the widget's edge and the gap between blocks. */
internal object WidgetDims {
    val Inset = 12.dp
    val Gap = 8.dp
    val HeaderHeight = 32.dp
    val ActionSize = 32.dp
    /** Fallback radii where the system ones don't exist (before Android 12). */
    val OuterRadius = 24.dp
    val InnerRadius = 14.dp
    val ThumbRadius = 10.dp
}

/** [WidgetColorRoles] as Glance colour providers (day/night resolved by the system when the widget is drawn). */
internal class WidgetPalette(val roles: WidgetColorRoles) {
    val surface = roles.surface.glance()
    val tile = roles.tile.glance()
    val text = roles.text.glance()
    val textSecondary = roles.textSecondary.glance()
    val icon = roles.icon.glance()
    val accent = roles.accent.glance()
    val onAccent = roles.onAccent.glance()
    val accentTile = roles.accentTile.glance()
}

private fun RolePair.glance(): GlanceColor = ColorProvider(day = day, night = night)

internal fun widgetPalette(style: WidgetStyle, wallpaperDarkText: Boolean? = null) =
    WidgetPalette(WidgetTheme.roles(style, wallpaperDarkText))

/** Whether the home-screen wallpaper is light and wants dark text; null when the system can't tell. */
internal fun wallpaperPrefersDarkText(context: Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    val colors = runCatching { WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
        ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
    } else {
        Color(colors.primaryColor.toArgb()).luminance() > 0.5f
    }
}

internal fun isNight(context: Context): Boolean =
    (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

/** Today: the time; otherwise the date (abbreviated). */
internal fun widgetDate(context: Context, millis: Long): String =
    if (DateUtils.isToday(millis)) DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
    else DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)

private val sdk31 get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * A rounded [color] background. Android 12+ clips with the launcher's own widget radius ([outer]) or inner radius; older
 * systems get a tinted rounded shape (a tint can't carry alpha there, so [color]'s alpha goes into the image alpha).
 */
internal fun GlanceModifier.rounded(color: RolePair, outer: Boolean = false, radius: Dp? = null): GlanceModifier {
    if (sdk31) {
        val clipped = when {
            radius != null -> cornerRadius(radius)
            outer -> cornerRadius(android.R.dimen.system_app_widget_background_radius)
            else -> cornerRadius(android.R.dimen.system_app_widget_inner_radius)
        }
        return clipped.background(color.glance())
    }
    val shape = if (outer) R.drawable.widget_shape_outer else R.drawable.widget_shape_inner
    return background(
        ImageProvider(shape), color.day.alpha, ContentScale.FillBounds,
        ColorFilter.tint(RolePair(color.day.copy(alpha = 1f), color.night.copy(alpha = 1f)).glance()),
    )
}

/** Radius for images on Android 12+ (older systems show them square). */
internal fun GlanceModifier.roundedImage(radius: Dp? = null): GlanceModifier = when {
    !sdk31 -> this
    radius != null -> cornerRadius(radius)
    else -> cornerRadius(android.R.dimen.system_app_widget_inner_radius)
}

/** The widget's outer surface: background colour, opacity and the launcher's corner radius. */
@Composable
internal fun WidgetFrame(p: WidgetPalette, onClick: Action? = null, padding: Dp = WidgetDims.Inset, content: @Composable () -> Unit) {
    var modifier = GlanceModifier.fillMaxSize().rounded(p.roles.surface, outer = true)
    if (onClick != null) modifier = modifier.clickable(onClick)
    Box(modifier = modifier.padding(padding)) { content() }
}

/** Title row: a bold label (tap = [onTitle]) and an optional trailing action. */
@Composable
internal fun WidgetHeader(
    title: String,
    p: WidgetPalette,
    onTitle: Action? = null,
    leadingIcon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(WidgetDims.HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
        var titleModifier = GlanceModifier.defaultWeight().padding(start = 4.dp)
        if (onTitle != null) titleModifier = titleModifier.clickable(onTitle)
        Row(modifier = titleModifier, verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Image(ImageProvider(leadingIcon), contentDescription = null, modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(p.accent))
                Spacer(GlanceModifier.width(6.dp))
            }
            Text(title, maxLines = 1, style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        if (trailing != null) trailing()
    }
}

/** A round icon button; [primary] fills it with the accent. */
@Composable
internal fun RoundButton(
    icon: Int,
    description: String,
    action: Action,
    p: WidgetPalette,
    primary: Boolean = false,
    size: Dp = WidgetDims.ActionSize,
    iconSize: Dp = 18.dp,
    modifier: GlanceModifier = GlanceModifier,
) {
    Box(
        modifier = modifier.size(size).rounded(if (primary) p.roles.accent else p.roles.tile, radius = size / 2).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            ImageProvider(icon), contentDescription = description, modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(if (primary) p.onAccent else p.icon),
        )
    }
}

/** Empty, locked, gone, not chosen: an icon in a soft circle, a title and a hint. The whole frame is the tap target. */
@Composable
internal fun StateMessage(icon: Int, title: String, hint: String?, p: WidgetPalette, compact: Boolean = false) {
    if (compact) {
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            StateIcon(icon, p, 32.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(title, maxLines = 1, style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                if (hint != null) Text(hint, maxLines = 1, style = TextStyle(color = p.textSecondary, fontSize = 12.sp))
            }
        }
        return
    }
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateIcon(icon, p, 40.dp)
        Spacer(GlanceModifier.height(8.dp))
        Text(title, maxLines = 2, style = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        if (hint != null) {
            Spacer(GlanceModifier.height(2.dp))
            Text(hint, maxLines = 2, style = TextStyle(color = p.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun StateIcon(icon: Int, p: WidgetPalette, size: Dp) {
    Box(modifier = GlanceModifier.size(size).rounded(p.roles.accentTile, radius = size / 2), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(size * 0.5f), colorFilter = ColorFilter.tint(p.accent))
    }
}

/** A page thumbnail, or a lock / blank tile in its place (a locked note never gets a bitmap). */
@Composable
internal fun Thumb(image: ImageProvider?, locked: Boolean, p: WidgetPalette, modifier: GlanceModifier, radius: Dp? = WidgetDims.ThumbRadius) {
    if (image != null && !locked) {
        Image(image, contentDescription = null, modifier = modifier.roundedImage(radius), contentScale = ContentScale.Crop)
        return
    }
    Box(modifier = modifier.rounded(p.roles.tile, radius = radius), contentAlignment = Alignment.Center) {
        Image(
            ImageProvider(if (locked) Oui.drawable.ic_oui_lock else Oui.drawable.ic_oui_notes),
            contentDescription = null, modifier = GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(p.textSecondary),
        )
    }
}
