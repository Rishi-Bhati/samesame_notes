package org.samesame.notes.feature.widgets

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NoteId
import dev.oneuiproject.oneui.R as Oui

/**
 * Quick capture: a small sheet over the home screen, opened from the quick-capture widget. Type or dictate a note (or a
 * checklist) and Save, without the full app; "Open in app" saves and continues in the editor.
 *
 * Data safety: the draft survives rotation and process death (saved state); Save, back, a tap outside and leaving the sheet
 * (home, recents) all save a non-empty draft, exactly once, through the repository's atomic create; an empty draft creates
 * nothing. The save runs in the application scope, so closing the sheet can't cut it off.
 */
class CaptureActivity : ComponentActivity() {
    private var draft by mutableStateOf(CaptureDraft())
    private var saved = false

    /** The speech dialog is on top: the sheet is only covered, not left, so onStop must not save and close it. */
    private var dictating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = CaptureMode.fromKey(intent.getStringExtra(EXTRA_MODE))
        draft = savedInstanceState?.let(::readDraft) ?: CaptureDraft(checklist = mode == CaptureMode.Checklist)
        saved = savedInstanceState?.getBoolean(STATE_SAVED) ?: false
        WidgetLog.i("capture opened mode=${mode.key}")
        val settingsFlow = WidgetEntryPoint.get(this).settings().settings
        val dictateFirst = mode == CaptureMode.Dictate && savedInstanceState == null
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
            SameSameTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme()), font = settings.font) {
                CaptureSheet(
                    draft = draft,
                    onDraft = { draft = it },
                    dictateFirst = dictateFirst,
                    onDictating = { dictating = it },
                    onSave = { saveAndClose(openInApp = false) },
                    onOpenInApp = { saveAndClose(openInApp = true) },
                    onRecordAudio = { recordAudioInstead() },
                    onDismiss = { saveAndClose(openInApp = false) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        writeDraft(outState, draft)
        outState.putBoolean(STATE_SAVED, saved)
    }

    /** Leaving the sheet any other way (home, recents, another app on top) keeps what was typed. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !dictating && !saved && !draft.isEmpty) {
            WidgetLog.i("capture left with text: saving")
            saveAndClose(openInApp = false)
        }
    }

    private fun saveAndClose(openInApp: Boolean) {
        if (saved) { finish(); return }
        val current = draft
        if (current.isEmpty) {
            saved = true
            // Nothing typed: "Open in app" still starts a fresh note in the editor; dismissing creates nothing.
            if (openInApp) startActivity(WidgetIntents.createNote(this, StartAction.Keypad))
            finish()
            return
        }
        saved = true
        val ep = WidgetEntryPoint.get(this)
        val app = applicationContext
        val creation = ep.appScope().async {
            runCatching { ep.notes().importRichText(current.richText(), current.noteTitle()) }
                .onFailure { WidgetLog.w("capture save failed", it) }
                .getOrNull()
        }
        lifecycleScope.launch {
            val id: NoteId? = creation.await()
            if (id == null) {
                // Keep the text on screen so nothing is lost; the user can try again.
                saved = false
                Toast.makeText(app, R.string.widget_capture_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            WidgetLog.i("capture saved")
            if (openInApp) startActivity(WidgetIntents.openNote(this@CaptureActivity, id.value).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else Toast.makeText(app, R.string.widget_capture_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Voice as a recording, not as text: the editor opens a new note and records. */
    private fun recordAudioInstead() {
        val current = draft
        if (!current.isEmpty) { saveAndClose(openInApp = false) } else { saved = true; finish() }
        startActivity(WidgetIntents.createNote(this, StartAction.Voice))
    }

    companion object {
        private const val EXTRA_MODE = "org.samesame.notes.widgets.extra.CAPTURE_MODE"
        private const val STATE_TITLE = "capture_title"
        private const val STATE_BODY = "capture_body"
        private const val STATE_CHECKLIST = "capture_checklist"
        private const val STATE_ITEMS = "capture_items"
        private const val STATE_SAVED = "capture_saved"

        fun intent(context: Context, mode: CaptureMode): Intent =
            Intent(context, CaptureActivity::class.java).putExtra(EXTRA_MODE, mode.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        private fun writeDraft(out: Bundle, d: CaptureDraft) {
            out.putString(STATE_TITLE, d.title)
            out.putString(STATE_BODY, d.body)
            out.putBoolean(STATE_CHECKLIST, d.checklist)
            out.putStringArrayList(STATE_ITEMS, ArrayList(d.items))
        }

        private fun readDraft(b: Bundle): CaptureDraft = CaptureDraft(
            title = b.getString(STATE_TITLE).orEmpty(),
            body = b.getString(STATE_BODY).orEmpty(),
            checklist = b.getBoolean(STATE_CHECKLIST),
            items = b.getStringArrayList(STATE_ITEMS)?.toList()?.ifEmpty { null } ?: listOf(""),
        )
    }
}

/** How the sheet opens: typing, a checklist, or dictation straight away. */
enum class CaptureMode(val key: String) {
    Text("text"), Checklist("checklist"), Dictate("dictate");

    companion object {
        fun fromKey(key: String?): CaptureMode = entries.firstOrNull { it.key == key } ?: Text
    }
}

@Composable
private fun CaptureSheet(
    draft: CaptureDraft,
    onDraft: (CaptureDraft) -> Unit,
    dictateFirst: Boolean,
    onDictating: (Boolean) -> Unit,
    onSave: () -> Unit,
    onOpenInApp: () -> Unit,
    onRecordAudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = SameSameTheme.colors
    val context = LocalContext.current
    val speakPrompt = stringResource(R.string.widget_capture_speak)
    val bodyFocus = remember { FocusRequester() }
    var focusItem by remember { mutableStateOf(-1) }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onDictating(false)
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) onDraft(draft.append(text))
    }
    val dictate: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, speakPrompt)
        try {
            onDictating(true)
            dictation.launch(intent)
        } catch (e: ActivityNotFoundException) {
            onDictating(false)
            Toast.makeText(context, R.string.widget_capture_no_dictation, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (dictateFirst) dictate() else if (!draft.checklist) runCatching { bodyFocus.requestFocus() } else focusItem = draft.items.lastIndex
    }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Scrim: a tap outside the sheet closes it and keeps any text, like back. Screen readers use back or Save instead.
        Box(
            Modifier.fillMaxSize().background(c.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .clearAndSetSemantics {},
        )
        Column(
            Modifier.statusBarsPadding().widthIn(max = 640.dp).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.dialog)
                .navigationBarsPadding().imePadding()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.divider))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.widget_capture_title), color = c.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                ChecklistToggle(draft.checklist) { on -> onDraft(if (on) draft.toChecklist() else draft.toText()) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 8.dp)) {
                CaptureField(
                    value = draft.title, onValue = { onDraft(draft.copy(title = it.replace("\n", " "))) },
                    hint = stringResource(R.string.widget_capture_title_hint), style = TextStyle(color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    imeAction = ImeAction.Next, singleLine = true,
                    onIme = { if (draft.checklist) focusItem = 0 else runCatching { bodyFocus.requestFocus() } },
                )
                if (draft.checklist) {
                    ChecklistEditor(draft, onDraft, focusItem) { focusItem = it }
                } else {
                    CaptureField(
                        value = draft.body, onValue = { onDraft(draft.copy(body = it)) },
                        hint = stringResource(R.string.widget_capture_body_hint), style = TextStyle(color = c.textPrimary, fontSize = 17.sp),
                        imeAction = ImeAction.Default, singleLine = false, minHeight = 96.dp,
                        modifier = Modifier.focusRequester(bodyFocus),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SheetIconButton(Oui.drawable.ic_oui_voice, stringResource(R.string.widget_capture_dictate), onClick = dictate)
                SheetIconButton(Oui.drawable.ic_oui_record, stringResource(R.string.widget_capture_record), onClick = onRecordAudio)
                Spacer(Modifier.weight(1f))
                SheetTextButton(stringResource(R.string.widget_capture_open), filled = false, onClick = onOpenInApp)
                Spacer(Modifier.width(8.dp))
                SheetTextButton(stringResource(R.string.widget_save), filled = true, onClick = onSave)
            }
        }
    }
}

@Composable
private fun CaptureField(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    style: TextStyle,
    imeAction: ImeAction,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 48.dp,
    onIme: () -> Unit = {},
) {
    val c = SameSameTheme.colors
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = singleLine, textStyle = style,
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight).padding(vertical = 10.dp)
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = style.copy(color = c.textTertiary, fontWeight = FontWeight.Normal))
                inner()
            }
        },
    )
}

@Composable
private fun ChecklistEditor(draft: CaptureDraft, onDraft: (CaptureDraft) -> Unit, focusItem: Int, onFocusItem: (Int) -> Unit) {
    val c = SameSameTheme.colors
    val requesters = remember(draft.items.size) { List(draft.items.size) { FocusRequester() } }
    LaunchedEffect(focusItem, draft.items.size) {
        if (focusItem in requesters.indices) runCatching { requesters[focusItem].requestFocus() }
    }
    draft.items.forEachIndexed { i, item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).border(2.dp, c.checkOutline, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(12.dp))
            CaptureField(
                value = item, onValue = { onDraft(draft.setItem(i, it)) },
                hint = stringResource(R.string.widget_capture_item_hint, i + 1), style = TextStyle(color = c.textPrimary, fontSize = 17.sp),
                imeAction = ImeAction.Next, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(requesters[i]),
                onIme = { onDraft(draft.insertItemAfter(i)); onFocusItem(i + 1) },
            )
            if (draft.items.size > 1) {
                SheetIconButton(Oui.drawable.ic_oui_close, stringResource(R.string.widget_capture_remove_item, i + 1)) {
                    onDraft(draft.removeItem(i)); onFocusItem((i - 1).coerceAtLeast(0))
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onDraft(draft.insertItemAfter(draft.items.lastIndex)); onFocusItem(draft.items.size) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Oui.drawable.ic_oui_add), contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.widget_capture_add_item), color = c.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChecklistToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = SameSameTheme.colors
    val label = stringResource(R.string.widget_capture_checklist)
    val state = stringResource(if (on) R.string.widget_capture_on else R.string.widget_capture_off)
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (on) c.accent else c.pill)
            .clickable(role = Role.Switch) { onChange(!on) }
            .semantics { stateDescription = state }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(Oui.drawable.ic_oui_checkbox_checked_outline), contentDescription = null,
            tint = if (on) Color.White else c.icon, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) Color.White else c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SheetIconButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = SameSameTheme.colors.icon, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SheetTextButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val c = SameSameTheme.colors
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(if (filled) c.accent else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Color.White else c.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
