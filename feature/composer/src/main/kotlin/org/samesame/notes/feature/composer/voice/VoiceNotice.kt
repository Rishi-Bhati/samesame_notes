package org.samesame.notes.feature.composer.voice

/** What the editor tells the user after a recording ended (snackbar). */
enum class VoiceNotice { Saved, SavedInterrupted, SavedMaxLength, TooShort, Failed }

/** What to do when the user taps Insert > Voice recording. */
enum class MicStep { Start, Rationale, Request, Denied }

object VoicePermissionPolicy {
    /**
     * Next step of the microphone permission flow: granted -> start; denied once (the system says to explain) -> our
     * rationale ("Used to add voice recordings to notes"), then the system prompt; never asked -> the system prompt;
     * asked and the system will not ask again -> point to the app settings.
     */
    fun next(granted: Boolean, shouldShowRationale: Boolean, requestedBefore: Boolean): MicStep = when {
        granted -> MicStep.Start
        shouldShowRationale -> MicStep.Rationale
        requestedBefore -> MicStep.Denied
        else -> MicStep.Request
    }
}
