package org.samesame.notes.feature.composer.text

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * The floating text selection menu with our own extra rows. Compose's platform toolbar only knows Cut / Copy / Paste /
 * Select all; the reference's read-only (view state) menu is Copy, Select all, Share, Web search, and an editable
 * selection that covers only anchored objects also offers Remove anchor. Provided through `LocalTextToolbar`, so
 * `RichTextToolbarEffect` drives it unchanged; [extras] is read every time the menu is shown or refreshed.
 * Uses the platform floating action mode like Compose's own toolbar ([rect] is in [view] coordinates).
 */
internal class ExtendedTextToolbar(private val view: View) : TextToolbar {
    class Extra(val label: String, val onClick: () -> Unit)

    var extras: () -> List<Extra> = { emptyList() }

    private var mode: ActionMode? = null
    private var rect = Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null
    private var shownExtras: List<Extra> = emptyList()
    private var menuKey: List<Any> = emptyList()

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        this.rect = rect
        onCopy = onCopyRequested; onPaste = onPasteRequested; onCut = onCutRequested; onSelectAll = onSelectAllRequested
        shownExtras = extras()
        val key = listOf(onCut != null, onCopy != null, onPaste != null, onSelectAll != null) + shownExtras.map { it.label }
        val m = mode
        if (m == null) {
            menuKey = key
            status = TextToolbarStatus.Shown
            mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
            if (mode == null) status = TextToolbarStatus.Hidden
        } else if (key != menuKey) {
            menuKey = key
            m.invalidate()
        } else {
            m.invalidateContentRect()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        mode?.finish()
        mode = null
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            fill(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            fill(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action: (() -> Unit)? = when (item.itemId) {
                android.R.id.cut -> onCut
                android.R.id.copy -> onCopy
                android.R.id.paste -> onPaste
                android.R.id.selectAll -> onSelectAll
                else -> shownExtras.getOrNull(item.itemId - EXTRA_BASE)?.onClick
            }
            action?.invoke()
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (this@ExtendedTextToolbar.mode === mode || this@ExtendedTextToolbar.mode == null) {
                this@ExtendedTextToolbar.mode = null
                status = TextToolbarStatus.Hidden
            }
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private fun fill(menu: Menu) {
        menu.clear()
        var order = 0
        fun add(id: Int, title: CharSequence) = menu.add(0, id, order++, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (onCut != null) add(android.R.id.cut, view.context.getString(android.R.string.cut))
        if (onCopy != null) add(android.R.id.copy, view.context.getString(android.R.string.copy))
        if (onPaste != null) add(android.R.id.paste, view.context.getString(android.R.string.paste))
        if (onSelectAll != null) add(android.R.id.selectAll, view.context.getString(android.R.string.selectAll))
        shownExtras.forEachIndexed { i, e -> menu.add(0, EXTRA_BASE + i, order++, e.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM) }
    }

    private companion object {
        const val EXTRA_BASE = 0x5A00
    }
}
