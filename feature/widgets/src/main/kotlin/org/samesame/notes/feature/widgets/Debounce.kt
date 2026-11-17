package org.samesame.notes.feature.widgets

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** Quiet time after the last catalog change before the widgets are redrawn (autosaves arrive in bursts). */
const val WIDGET_REFRESH_DEBOUNCE_MS = 1_500L

/**
 * Turns catalog fingerprints into redraw triggers: only real changes count, a burst collapses into one trigger, and the
 * first value (the state the widgets were last drawn from) triggers nothing.
 */
@OptIn(FlowPreview::class)
fun Flow<Int>.refreshTriggers(windowMs: Long = WIDGET_REFRESH_DEBOUNCE_MS): Flow<Int> =
    distinctUntilChanged().drop(1).debounce(windowMs)
