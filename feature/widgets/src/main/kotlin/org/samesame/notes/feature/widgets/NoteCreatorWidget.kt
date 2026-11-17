package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import dev.oneuiproject.oneui.R as Oui

/** A row of buttons that each start a new note in a mode: keyboard, pen, camera, image, voice. */
class NoteCreatorWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { NoteCreatorContent() }
    }
}

private data class CreatorButton(val action: StartAction, val icon: Int, val label: Int)

private val creatorButtons = listOf(
    CreatorButton(StartAction.Keypad, Oui.drawable.ic_oui_keyboard, R.string.widget_creator_keypad),
    CreatorButton(StartAction.Pen, Oui.drawable.ic_oui_pen_fountain, R.string.widget_creator_pen),
    CreatorButton(StartAction.Camera, Oui.drawable.ic_oui_camera_outline, R.string.widget_creator_camera),
    CreatorButton(StartAction.Image, Oui.drawable.ic_oui_image_outline, R.string.widget_creator_image),
    CreatorButton(StartAction.Voice, R.drawable.ic_widget_mic, R.string.widget_creator_voice),
)

@Composable
private fun NoteCreatorContent() {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(WidgetColors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        creatorButtons.forEach { button ->
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(WidgetIntents.createNote(context, button.action))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(button.icon), contentDescription = context.getString(button.label),
                    modifier = GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(WidgetColors.icon),
                )
            }
        }
    }
}

class NoteCreatorWidgetReceiver : WidgetReceiverBase() {
    override val glanceAppWidget: GlanceAppWidget = NoteCreatorWidget()
}
