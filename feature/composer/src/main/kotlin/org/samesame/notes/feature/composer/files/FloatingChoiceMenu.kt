package org.samesame.notes.feature.composer.files

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect

/**
 * A small floating menu at the caret in the style of the text selection menu (the platform floating action mode, like
 * `ExtendedTextToolbar`): the paste chooser "Paste as link" / "Show in note". Closing it without picking a row (a tap
 * elsewhere, Back) calls [onDismiss], so a paste never does nothing.
 */
internal class FloatingChoiceMenu(private val view: View) {
    class Row(val label: String, val onClick: () -> Unit)

    private var mode: ActionMode? = null
    private var rows: List<Row> = emptyList()
    private var rect = Rect.Zero
    private var onDismiss: () -> Unit = {}
    private var picked = false

    val isShown: Boolean get() = mode != null

    /** Shows [rows] at [rect] (root view coordinates). */
    fun show(rect: Rect, rows: List<Row>, onDismiss: () -> Unit) {
        hide()
        this.rect = rect
        this.rows = rows
        this.onDismiss = onDismiss
        picked = false
        mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        if (mode == null) {
            // No floating menu on this device: behave as if it was dismissed.
            this.onDismiss = {}
            onDismiss()
        }
    }

    /** Closes the menu without a choice ([onDismiss] runs). */
    fun hide() {
        mode?.finish()
        mode = null
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            rows.forEachIndexed { i, r -> menu.add(0, BASE + i, i, r.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS) }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val row = rows.getOrNull(item.itemId - BASE) ?: return false
            picked = true
            mode.finish()
            row.onClick()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (this@FloatingChoiceMenu.mode === mode) this@FloatingChoiceMenu.mode = null
            val dismiss = onDismiss
            onDismiss = {}
            if (!picked) dismiss()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), maxOf(rect.bottom.toInt(), rect.top.toInt() + 1))
        }
    }

    private companion object {
        const val BASE = 0x5B00
    }
}
