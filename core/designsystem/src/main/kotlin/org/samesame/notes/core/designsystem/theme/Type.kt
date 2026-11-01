package org.samesame.notes.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** System font (Roboto on most devices). */
private val Font = FontFamily.SansSerif

internal val SameSameTypography = Typography().let { base ->
    fun TextStyle.sys() = copy(fontFamily = Font)
    base.copy(
        displayLarge = base.displayLarge.sys(), displayMedium = base.displayMedium.sys(),
        displaySmall = base.displaySmall.sys(), headlineLarge = base.headlineLarge.sys(),
        headlineMedium = base.headlineMedium.sys(), headlineSmall = base.headlineSmall.sys(),
        titleLarge = base.titleLarge.sys(), titleMedium = base.titleMedium.sys(),
        titleSmall = base.titleSmall.sys(), bodyLarge = base.bodyLarge.sys(),
        bodyMedium = base.bodyMedium.sys(), bodySmall = base.bodySmall.sys(),
        labelLarge = base.labelLarge.sys(), labelMedium = base.labelMedium.sys(),
        labelSmall = base.labelSmall.sys(),
    )
}

/** Styles for the One UI header and cards. */
object SameSameText {
    val LargeTitle = TextStyle(fontFamily = Font, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
    val LargeSubtitle = TextStyle(fontFamily = Font, fontSize = 15.sp, lineHeight = 20.sp)
    val ToolbarTitle = TextStyle(fontFamily = Font, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val CardTitle = TextStyle(fontFamily = Font, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val CardDate = TextStyle(fontFamily = Font, fontSize = 13.sp, lineHeight = 18.sp)
    /** Grid card preview body (13sp). */
    val CardBody = TextStyle(fontFamily = Font, fontSize = 13.sp, lineHeight = 19.sp)
    /** List/simple item title (16sp bold). */
    val ListTitle = TextStyle(fontFamily = Font, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    /** List item body text (16sp). */
    val ListBody = TextStyle(fontFamily = Font, fontSize = 14.sp, lineHeight = 20.sp)
    /** Drawer row / folder name title (18sp). */
    val DrawerTitle = TextStyle(fontFamily = Font, fontSize = 18.sp, lineHeight = 24.sp)
    /** Section subtitle such as "2 notes" (15sp). */
    val SectionSubtitle = TextStyle(fontFamily = Font, fontSize = 15.sp, lineHeight = 20.sp)
    /** Empty-state headline (17sp). */
    val EmptyTitle = TextStyle(fontFamily = Font, fontSize = 17.sp, lineHeight = 24.sp)
    /** Search field title/placeholder (24sp). */
    val SearchTitle = TextStyle(fontFamily = Font, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val Menu = TextStyle(fontFamily = Font, fontSize = 17.sp, lineHeight = 22.sp)
    val Button = TextStyle(fontFamily = Font, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val DialogMessage = TextStyle(fontFamily = Font, fontSize = 16.sp, lineHeight = 24.sp)
    val Caption = TextStyle(fontFamily = Font, fontSize = 12.sp, lineHeight = 16.sp)
}

/**
 * the reference's text sizes mix units: some dimens are `dp` (ignore the system font scale), most are `sp`, and
 * large-text-limited views cap the font scale at 1.1. These helpers reproduce all three on any device.
 */
@Composable
@ReadOnlyComposable
fun fixedSp(dp: Float): TextUnit = with(LocalDensity.current) { dp.dp.toSp() }

/** [sp] scaled by the system font scale, but never by more than [max] (One UI large-text limit). */
@Composable
@ReadOnlyComposable
fun cappedSp(sp: Float, max: Float = 1.1f): TextUnit =
    with(LocalDensity.current) { (sp * minOf(fontScale, max)).dp.toSp() }

/** This style with its size/line height ignoring the font scale (spec dimen in dp). */
@Composable
@ReadOnlyComposable
fun TextStyle.fixed(): TextStyle = copy(
    fontSize = if (fontSize.isSp) fixedSp(fontSize.value) else fontSize,
    lineHeight = if (lineHeight.isSp) fixedSp(lineHeight.value) else lineHeight,
)

/** This style with the font scale capped at [max] (spec dimen in sp on a large-text-limited view). */
@Composable
@ReadOnlyComposable
fun TextStyle.capped(max: Float = 1.1f): TextStyle = copy(
    fontSize = if (fontSize.isSp) cappedSp(fontSize.value, max) else fontSize,
    lineHeight = if (lineHeight.isSp) cappedSp(lineHeight.value, max) else lineHeight,
)
