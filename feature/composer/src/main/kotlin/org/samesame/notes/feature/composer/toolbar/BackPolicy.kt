package org.samesame.notes.feature.composer.toolbar

/** What a Back press means for the editor, given the app settings. Pure so it can be unit tested. */
enum class BackOutcome {
    /** Run the normal chain (close panel / menu, clear selection, leave full screen / reading). */
    Chain,

    /** Leave the note. */
    Leave,

    /** Leave, but first ask Save / Discard (auto save is off and there are unsaved edits). */
    AskSaveOrDiscard,

    /** "Block Back button while editing": the key does nothing at the top level. */
    Ignore,
}

object BackPolicy {
    /** The system Back key / gesture. */
    fun systemBack(action: BackAction, blockBack: Boolean, autoSave: Boolean, hasUnsaved: Boolean): BackOutcome = when {
        action != BackAction.LeaveEditor -> BackOutcome.Chain
        blockBack -> BackOutcome.Ignore
        else -> toolbarBack(autoSave, hasUnsaved)
    }

    /** The back arrow in the top bar: always leaves (the block only applies to the Back key). */
    fun toolbarBack(autoSave: Boolean, hasUnsaved: Boolean): BackOutcome =
        if (!autoSave && hasUnsaved) BackOutcome.AskSaveOrDiscard else BackOutcome.Leave
}
