package org.samesame.notes.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.oneuiproject.oneui.R

/**
 * The app's icon set: the One UI icons from `io.github.oneuiproject:icons` (`ic_oui_*` vector drawables),
 * matched one-to-one to the the reference app reference screens. Members with no close library glyph fall back
 * to [TracedIcons], our own traced drawings. Member names are stable call-site API.
 *
 * Library-backed members are @Composable (vectorResource); call them from composition only.
 * Mapping table: docs/checkpoints/icons-oui.md.
 */
object AppIcons {
    val Menu: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_drawer)

    val Search: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_search)

    val More: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_more)

    val Back: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_back)

    val Minimize: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_minimize)

    val ControlPlay: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_control_play)

    val ControlPause: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_control_pause)

    val ControlStop: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_control_stop)

    val Pencil: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_pencil)

    val ChevronLeft: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_arrow_left)

    val ChevronRight: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_arrow_right)

    val ChevronDown: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_arrow_down)

    val ChevronUp: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_arrow_up)

    val Close: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_close)

    val Add: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_add)

    val Remove: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_minus)

    val Check: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_selected)

    val Book: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_reading_mode)

    val Clip: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_attach)

    val Handwriting: ImageVector get() = TracedIcons.Handwriting

    val Checklist: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_check_on)

    val TextStyle: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_setting)

    val TextColor: ImageVector get() = TracedIcons.TextColor

    val TextHighlight: ImageVector get() = TracedIcons.TextHighlight

    val DropdownCaret: ImageVector get() = TracedIcons.DropdownCaret

    val Undo: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_undo)

    val Redo: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_redo)

    val Keyboard: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard)

    val Pen: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_pen_fountain)

    val Highlighter: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_pen_marker)

    val Eraser: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_pen_eraser_outline)

    val Lasso: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_selection_lasso)

    val ChangeStyle: ImageVector get() = TracedIcons.ChangeStyle

    val EasyWritingPad: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_hand_writing_input)

    val Bullets: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_dot)

    val Numbering: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_numbering)

    val AlignLeft: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_align_left)

    val AlignCenter: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_align_center)

    val AlignRight: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_align_right)

    val Bold: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_bold)

    val Italic: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_italic)

    val Underline: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_underline)

    val Strikethrough: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_strikethrough)

    val Indent: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_indent)

    val Outdent: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_outdent)

    val Star: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_star_outline)

    val StarFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_star)

    val PenPreset: ImageVector get() = TracedIcons.PenPreset

    val RadioOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_checkbox_unchecked)

    val RadioOn: ImageVector get() = TracedIcons.RadioOn

    val CheckBadge: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_checkbox_checked)

    val Handle: ImageVector get() = TracedIcons.Handle

    val EnterKey: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_btn_enter)

    val FullscreenExit: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_minimize)

    val FullscreenEnter: ImageVector get() = TracedIcons.FullscreenEnter

    val PageVertical: ImageVector get() = TracedIcons.PageVertical

    val PageHorizontal: ImageVector get() = TracedIcons.PageHorizontal

    val Image: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_image_outline)

    val Camera: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_camera_outline)

    val Mic: ImageVector get() = TracedIcons.Mic

    val Palette: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_brush_outline)

    val TextBox: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_text_font_bg_color)

    val PageSorter: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_memo_outline)

    val PageTemplate: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_page_template)

    val PageSettings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_page_settings)

    val Bookmark: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_bookmark_list_outline)

    val BookmarkFilled: ImageVector get() = TracedIcons.BookmarkFilled

    val Sort: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_list_sort)

    val ArrowDown: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_arrow_down)

    val ArrowUp: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_arrow_up)

    val EditSquare: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_compose_edit)

    /** New-note FAB glyph (traced; the library has no match). */
    val ComposeFab: ImageVector get() = TracedIcons.ComposeFab

    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_settings_outline)

    val Notes: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_notes_new)

    /** Recorded audio list (`ic_oui_list`): the voice play panel's list button. */
    val List: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_list)

    val Trash: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_delete_outline)

    val Folder: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_folder_outline)

    val FolderMove: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_move_outline)

    val Share: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_share)

    val Lock: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_lock_outline)

    val Unlock: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_unlock_outline)

    val RestoreFromTrash: ImageVector get() = TracedIcons.RestoreFromTrash

    val ViewAgenda: ImageVector get() = TracedIcons.ViewAgenda

    val ViewModule: ImageVector get() = TracedIcons.ViewModule

    val ViewWeek: ImageVector get() = TracedIcons.ViewWeek

    val Tag: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_tag)

    val Print: ImageVector get() = TracedIcons.Print

    val SaveAlt: ImageVector get() = TracedIcons.SaveAlt

    val Fullscreen: ImageVector get() = TracedIcons.Fullscreen

    val Cut: ImageVector get() = TracedIcons.Cut

    val Copy: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_copy_outline)

    val Paste: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_clipboard)

    val Schedule: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_time)

    val Eyedropper: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_spoid_outline)

    val ExpandMore: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_arrow_down)

    val ExpandLess: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_keyboard_arrow_up)

    val PdfAdd: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_oui_add_pdf_new)
}
