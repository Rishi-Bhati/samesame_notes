package org.samesame.notes.feature.widgets

import android.content.Context
import android.content.Intent
import dev.oneuiproject.oneui.R as Oui

/**
 * One action a quick-capture button can start. [key] is the stored form (never rename one). [title] names it in the widget
 * settings, [description] is what a screen reader says on the widget, [label] the short text under a tile.
 */
enum class CaptureButton(val key: String, val icon: Int, val title: Int, val description: Int, val label: Int) {
    None("none", 0, R.string.widget_button_none, 0, 0),
    NewNote("note", Oui.drawable.ic_oui_edit_outline, R.string.widget_button_note, R.string.widget_button_note_description, R.string.widget_creator_label_type),
    Checklist("checklist", Oui.drawable.ic_oui_checkbox_checked_outline, R.string.widget_button_checklist, R.string.widget_capture_new_checklist, R.string.widget_capture_label_checklist),
    Dictate("dictate", Oui.drawable.ic_oui_voice, R.string.widget_button_dictate, R.string.widget_capture_dictate_note, R.string.widget_capture_label_voice),
    Handwriting("pen", Oui.drawable.ic_oui_pen_fountain, R.string.widget_button_handwriting, R.string.widget_creator_pen, R.string.widget_creator_label_pen),
    Drawing("brush", Oui.drawable.ic_oui_brush_outline, R.string.widget_button_drawing, R.string.widget_creator_brush, R.string.widget_creator_label_brush),
    Camera("camera", Oui.drawable.ic_oui_camera_outline, R.string.widget_button_camera, R.string.widget_button_camera_description, R.string.widget_button_camera_label),
    Picture("image", Oui.drawable.ic_oui_image_outline, R.string.widget_button_picture, R.string.widget_creator_image, R.string.widget_creator_label_image),
    Markdown("markdown", Oui.drawable.ic_oui_document, R.string.widget_button_markdown, R.string.widget_button_markdown_description, R.string.widget_button_markdown_label),
    OpenApp("app", Oui.drawable.ic_oui_notes, R.string.widget_button_open_app, R.string.widget_button_open_app_description, R.string.widget_button_open_app_label),
    ;

    /** What a tap starts: the capture sheet for typing, a checklist or dictation; a new note in the editor otherwise. */
    fun intent(context: Context): Intent? = when (this) {
        None -> null
        NewNote -> CaptureActivity.intent(context, CaptureMode.Text)
        Checklist -> CaptureActivity.intent(context, CaptureMode.Checklist)
        Dictate -> CaptureActivity.intent(context, CaptureMode.Dictate)
        Handwriting -> WidgetIntents.createNote(context, StartAction.Pen)
        Drawing -> WidgetIntents.createNote(context, StartAction.Brush)
        Camera -> WidgetIntents.createNote(context, StartAction.Camera)
        Picture -> WidgetIntents.createNote(context, StartAction.Image)
        Markdown -> WidgetIntents.createNote(context, StartAction.Markdown)
        OpenApp -> WidgetIntents.openApp(context)
    }

    companion object {
        fun fromKey(key: String?): CaptureButton? = entries.firstOrNull { it.key == key }

        /** What the centre (main) slot may hold: anything but an empty slot. */
        val centreChoices: List<CaptureButton> get() = entries.filter { it != None }

        /** What a corner slot may hold, an empty slot first. */
        val cornerChoices: List<CaptureButton> get() = entries.toList()
    }
}

/**
 * The buttons of one quick-capture widget: the [centre] (main) action and four [corners] in the order top-left, top-right,
 * bottom-left, bottom-right. The bar layouts show the bar for [centre] and the corner actions in that order.
 */
data class CaptureButtons(
    val centre: CaptureButton = DEFAULT_CENTRE,
    val corners: List<CaptureButton> = DEFAULT_CORNERS,
) {
    init {
        require(corners.size == SLOTS) { "four corners" }
        require(centre != CaptureButton.None) { "the centre is never empty" }
    }

    /** The corner actions that are set, in slot order (the bar and tile layouts). */
    val shown: List<CaptureButton> get() = corners.filter { it != CaptureButton.None }

    fun withCentre(button: CaptureButton): CaptureButtons = if (button == CaptureButton.None) this else copy(centre = button)

    fun withCorner(slot: Int, button: CaptureButton): CaptureButtons =
        copy(corners = corners.toMutableList().also { it[slot] = button })

    /** Stored form: the five keys, centre first, comma separated. */
    fun encode(): String = (listOf(centre) + corners).joinToString(",") { it.key }

    companion object {
        const val SLOTS = 4
        const val TOP_LEFT = 0
        const val TOP_RIGHT = 1
        const val BOTTOM_LEFT = 2
        const val BOTTOM_RIGHT = 3

        val DEFAULT_CENTRE = CaptureButton.NewNote
        val DEFAULT_CORNERS = listOf(CaptureButton.Checklist, CaptureButton.Dictate, CaptureButton.Handwriting, CaptureButton.Camera)

        /**
         * Reads [encode]'s form. Nothing saved = the defaults; a missing or unknown key (a newer version's action) falls back to
         * that slot's default, and an empty centre to the default centre.
         */
        fun decode(value: String?): CaptureButtons {
            if (value.isNullOrBlank()) return CaptureButtons()
            val keys = value.split(",").map { it.trim() }
            val centre = CaptureButton.fromKey(keys.getOrNull(0))?.takeIf { it != CaptureButton.None } ?: DEFAULT_CENTRE
            val corners = List(SLOTS) { i -> CaptureButton.fromKey(keys.getOrNull(i + 1)) ?: DEFAULT_CORNERS[i] }
            return CaptureButtons(centre, corners)
        }
    }
}

/** Which buttons each quick-capture widget shows (`widget id -> buttons`); no entry = the defaults. */
class CaptureButtonStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_capture_buttons", Context.MODE_PRIVATE)

    fun get(widgetId: Int): CaptureButtons = CaptureButtons.decode(prefs.getString("b$widgetId", null))

    fun set(widgetId: Int, buttons: CaptureButtons) {
        prefs.edit().putString("b$widgetId", buttons.encode()).apply()
    }

    fun clear(widgetId: Int) {
        prefs.edit().remove("b$widgetId").apply()
    }
}
