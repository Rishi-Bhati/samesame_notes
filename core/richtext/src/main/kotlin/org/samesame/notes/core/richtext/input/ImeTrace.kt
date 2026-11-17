package org.samesame.notes.core.richtext.input

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/** Debug-only trace of every IME call (tag `SsIme`). Does nothing unless the app is debuggable. */
object ImeTrace {
    const val TAG = "SsIme"
    const val MATH_TAG = "SsMath"

    @Volatile
    var enabled: Boolean = false
        private set

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun log(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun logMath(msg: String) {
        if (enabled) Log.d(MATH_TAG, msg)
    }

    /** Logs [name]([args]) and the value [block] returned. */
    inline fun <T> call(name: String, args: String = "", block: () -> T): T {
        if (!enabled) return block()
        val r = block()
        Log.d(TAG, "$name($args) -> ${describe(r)}")
        return r
    }

    fun describe(r: Any?): String = when (r) {
        null -> "null"
        is CharSequence -> "\"${r.toString().replace("\n", "\\n")}\" len=${r.length}"
        is android.view.inputmethod.ExtractedText ->
            "ET(startOffset=${r.startOffset} partial=${r.partialStartOffset}..${r.partialEndOffset} sel=${r.selectionStart},${r.selectionEnd} " +
                "flags=${r.flags} textLen=${r.text?.length} text=\"${r.text.toString().takeLast(60).replace("\n", "\\n")}\")"
        is android.view.inputmethod.SurroundingText ->
            "ST(offset=${r.offset} sel=${r.selectionStart},${r.selectionEnd} textLen=${r.text.length} text=\"${r.text.toString().takeLast(60).replace("\n", "\\n")}\")"
        else -> r.toString()
    }
}
