package org.samesame.notes.feature.composer.panels

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The 21 eight-colour swatch sets of the pen colour palette (facts from the reference app's colour tables: each set has a
 * light-theme row and an "adaptive" row used in dark mode, 8 colours each). The panel shows the Recent page followed by the
 * user's chosen sets (at most [MAX_SELECTED], default [DEFAULT_SELECTED]); "Select colour sets to show" edits that choice and
 * [SWATCH_ORDER] is the order of the selection list.
 */
internal object ColorPalettes {
    const val MAX_SELECTED = 4
    const val SET_COUNT = 21
    /** Recent page: 8 cells; the eyedropper takes the last one, leaving 7 colours (the colour list itself holds 8). */
    const val RECENT_CAPACITY = 8
    const val RECENT_VISIBLE_WITH_EYEDROPPER = 7
    val DEFAULT_SELECTED = listOf(1, 4, 21, 5)
    val SWATCH_ORDER = listOf(1, 4, 21, 5, 2, 9, 7, 3, 6, 8, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)

    private val light: Map<Int, LongArray> = mapOf(
        1 to longArrayOf(0xFFFF3636, 0xFFFFCB30, 0xFFF7EB00, 0xFF2FEBD2, 0xFF3396FF, 0xFF1447FF, 0xFF9933FF, 0xFF252525),
        2 to longArrayOf(0xFFE91E63, 0xFFFF7043, 0xFFFFC107, 0xFFCDDC39, 0xFF00BCD4, 0xFF2B61FB, 0xFF795548, 0xFF9D9D9D),
        3 to longArrayOf(0xFFFF5E32, 0xFFFF9002, 0xFFFECA93, 0xFFB3B242, 0xFF545691, 0xFF5A7C7D, 0xFF73BAC0, 0xFF68B231),
        4 to longArrayOf(0xFFEF806C, 0xFFD94D3C, 0xFF123644, 0xFF3F8881, 0xFF6088B1, 0xFF50479A, 0xFFA56133, 0xFFCEB8A1),
        5 to longArrayOf(0xFF6CA9E8, 0xFF92B0E2, 0xFFB0D29F, 0xFFBA9500, 0xFFEB7461, 0xFF743AA2, 0xFFC767A6, 0xFFC27F65),
        6 to longArrayOf(0xFFE9E9E9, 0xFFD9D9D9, 0xFFBFBFBF, 0xFFA6A6A6, 0xFF919191, 0xFF6A6A6A, 0xFF4E4E4E, 0xFF040400),
        7 to longArrayOf(0xFFF9DAAE, 0xFFFFBFA6, 0xFFFE9A9C, 0xFFFF85AC, 0xFF687AB3, 0xFF8781AD, 0xFFAE8EBD, 0xFFD488B7),
        8 to longArrayOf(0xFF91E2C2, 0xFF04878F, 0xFFBCB5E9, 0xFF6F55B8, 0xFF5CDEE6, 0xFF699BD8, 0xFF00377A, 0xFF071F3C),
        9 to longArrayOf(0xFFB3C871, 0xFF9AD2C9, 0xFFF1C27D, 0xFFEF8862, 0xFF5C69AE, 0xFF8B7BC9, 0xFFC27DC3, 0xFFD2634E),
        10 to longArrayOf(0xFF439AF6, 0xFFA3F178, 0xFF7A5DE9, 0xFFD7559B, 0xFF2E1EEF, 0xFF7DFAA7, 0xFFEFFD6D, 0xFFE86536),
        11 to longArrayOf(0xFFE54D62, 0xFFFF8F00, 0xFF24BCEB, 0xFF786AE4, 0xFFFFDA28, 0xFF8FD82F, 0xFF01BC8E, 0xFFEB5CD9),
        12 to longArrayOf(0xFFFF0C84, 0xFFFF2400, 0xFF0DFB05, 0xFF0D0DFF, 0xFFFF00FF, 0xFFFFFF00, 0xFF21FFFA, 0xFFA82AFF),
        13 to longArrayOf(0xFFFFD8E2, 0xFFFFF1CD, 0xFFC0EDFF, 0xFFDDDFFE, 0xFFFFFAC3, 0xFFEBF4C6, 0xFFBDEFE3, 0xFFF9DBFC),
        14 to longArrayOf(0xFFFF7B8B, 0xFFFF95AE, 0xFFD6EA74, 0xFFAFEAB9, 0xFFFFB3A3, 0xFFFFEB62, 0xFFBAE4E3, 0xFFC1D2EF),
        15 to longArrayOf(0xFFFB95D3, 0xFFFFBBE2, 0xFF9D97EF, 0xFFA68FC4, 0xFFDB9BF1, 0xFFDBD5F9, 0xFF78ACE8, 0xFFEEEEB0),
        16 to longArrayOf(0xFFBA0F1B, 0xFFD74F4C, 0xFFFFDB5E, 0xFFA6B653, 0xFFE87D8D, 0xFFFFB74F, 0xFFC9D87B, 0xFF26998A),
        17 to longArrayOf(0xFFB65858, 0xFFDFA798, 0xFF6395A0, 0xFFD1E2E9, 0xFFEA8F74, 0xFFFFF4DB, 0xFF7DC7D2, 0xFF9994B4),
        18 to longArrayOf(0xFF431F0E, 0xFF71391C, 0xFF37582D, 0xFF6E7D46, 0xFF7A5F4B, 0xFFB08E7D, 0xFFC3C06D, 0xFFC9D9B2),
        19 to longArrayOf(0xFF915D6E, 0xFFC4A0AE, 0xFFF5E5E5, 0xFF55687E, 0xFFBD7E93, 0xFFF0CED1, 0xFFAABED3, 0xFF3A4959),
        20 to longArrayOf(0xFF494640, 0xFF686763, 0xFFBBB0A2, 0xFFC5CDCD, 0xFF978E8F, 0xFFCEC4C5, 0xFFD6D7D2, 0xFFF0F0F0),
        21 to longArrayOf(0xFFFBE156, 0xFFF05268, 0xFF6CDD8F, 0xFF7289FF, 0xFFDADADA, 0xFF676767, 0xFFFFC000, 0xFF37D366),
    )

    private val adaptive: Map<Int, LongArray> = mapOf(
        1 to longArrayOf(0xFFC90000, 0xFFFFCB30, 0xFFF7EB00, 0xFF2FEBD2, 0xFF3396FF, 0xFF1447FF, 0xFF9933FF, 0xFFDADADA),
        2 to longArrayOf(0xFFE91E63, 0xFFBC2D00, 0xFFFFC107, 0xFFCDDC39, 0xFF00BCD4, 0xFF2B61FB, 0xFFB79386, 0xFF626262),
        3 to longArrayOf(0xFFFF5E32, 0xFFFF9002, 0xFF6C3801, 0xFFB3B242, 0xFF545691, 0xFF5A7C7D, 0xFF3F868C, 0xFF68B231),
        4 to longArrayOf(0xFF932410, 0xFFD94D3C, 0xFFBBDFED, 0xFF77C0B9, 0xFF6088B1, 0xFF50479A, 0xFFA56133, 0xFF5E4831),
        5 to longArrayOf(0xFF175493, 0xFF1D3B6D, 0xFF3E602D, 0xFFFFDA45, 0xFF9E2714, 0xFF743AA2, 0xFFC767A6, 0xFFC27F65),
        6 to longArrayOf(0xFF161616, 0xFF262626, 0xFF404040, 0xFF595959, 0xFF919191, 0xFF6A6A6A, 0xFFB1B1B1, 0xFFFFFFFB),
        7 to longArrayOf(0xFF513206, 0xFF591900, 0xFF650103, 0xFF7A0027, 0xFF687AB3, 0xFF8781AD, 0xFF624271, 0xFF772B5A),
        8 to longArrayOf(0xFF1D6E4E, 0xFF70F3FB, 0xFF1D164A, 0xFF6F55B8, 0xFF199BA3, 0xFF275996, 0xFF85BCFF, 0xFFC3DBF8),
        9 to longArrayOf(0xFF798E37, 0xFF2D655C, 0xFF82530E, 0xFF9D3610, 0xFF5C69AE, 0xFF463684, 0xFF813C82, 0xFFD2634E),
        10 to longArrayOf(0xFF0960BC, 0xFF39870E, 0xFF3316A2, 0xFFD7559B, 0xFF2E1EEF, 0xFF05822F, 0xFF849202, 0xFFE86536),
        11 to longArrayOf(0xFFE54D62, 0xFFFF8F00, 0xFF24BCEB, 0xFF291B95, 0xFFFFDA28, 0xFF8FD82F, 0xFF43FED0, 0xFFA31491),
        12 to longArrayOf(0xFFFF0C84, 0xFFFF2400, 0xFF0DFB05, 0xFF0D0DFF, 0xFFFF00FF, 0xFFFFFF00, 0xFF21FFFA, 0xFFA82AFF),
        13 to longArrayOf(0xFF27000A, 0xFF322400, 0xFF002D3F, 0xFF010322, 0xFF3C3700, 0xFF30390B, 0xFF104236, 0xFF210324),
        14 to longArrayOf(0xFF840010, 0xFF6A0019, 0xFF778B15, 0xFF15501F, 0xFF5C1000, 0xFF9D8900, 0xFF1B4544, 0xFF10213E),
        15 to longArrayOf(0xFF6A0442, 0xFF440027, 0xFF161068, 0xFF523B70, 0xFF4E0E64, 0xFF0C062A, 0xFF174B87, 0xFF4F4F11),
        16 to longArrayOf(0xFFF04551, 0xFFD74F4C, 0xFFA17D00, 0xFFA6B653, 0xFF821727, 0xFFB06800, 0xFF758427, 0xFF66D9CA),
        17 to longArrayOf(0xFFB65858, 0xFF672F20, 0xFF6395A0, 0xFF16272E, 0xFF8B3015, 0xFF241900, 0xFF2D7782, 0xFF504B6B),
        18 to longArrayOf(0xFFF1CDBC, 0xFFE3AB8E, 0xFFB1D2A7, 0xFFAAB982, 0xFFB49985, 0xFFB08E7D, 0xFFC3C06D, 0xFF3D4D26),
        19 to longArrayOf(0xFF915D6E, 0xFF5F3B49, 0xFF1A0A0A, 0xFF55687E, 0xFF814257, 0xFF310F12, 0xFF2C4055, 0xFFA6B5C5),
        20 to longArrayOf(0xFFBFBCB6, 0xFF9C9B97, 0xFF5D5244, 0xFF323A3A, 0xFF978E8F, 0xFF3B3132, 0xFF2C2D28, 0xFF0F0F0F),
        21 to longArrayOf(0xFFA98F04, 0xFFAD0F25, 0xFF229345, 0xFF00178D, 0xFF252525, 0xFF676767, 0xFFFFC000, 0xFF37D366),
    )

    /** The 8 colours of set [id] (1..21), the adaptive row when [dark]. Unknown ids give set 1. */
    fun colors(id: Int, dark: Boolean): List<Color> =
        (if (dark) adaptive else light).getValue(if (id in 1..SET_COUNT) id else 1).map { Color(it) }

    /** Drops unknown/duplicate ids and keeps at most [MAX_SELECTED]; an empty result falls back to the default selection. */
    fun normalize(ids: List<Int>): List<Int> =
        ids.filter { it in 1..SET_COUNT }.distinct().take(MAX_SELECTED).ifEmpty { DEFAULT_SELECTED }

    /** Index of [selected] in [colors] (compared as ARGB, alpha ignored), or -1. */
    fun indexOf(colors: List<Color>, selected: Color?): Int {
        if (selected == null) return -1
        val want = selected.toArgb() and 0xFFFFFF
        return colors.indexOfFirst { (it.toArgb() and 0xFFFFFF) == want }
    }

    /** The selected colour is on none of the visible swatch [pages]: the custom-colour tile carries the check mark. */
    fun isCustomSelection(pages: List<List<Color>>, selected: Color?): Boolean =
        selected != null && pages.none { indexOf(it, selected) >= 0 }
}
