package org.samesame.notes.feature.composer.input

import androidx.compose.ui.geometry.Offset

/** A point in document space (pu). */
data class DocPoint(val position: Offset, val pressure: Float = 1f)

enum class InputToolType { Stylus, StylusEraser, Finger }

/** Overlay a tool wants drawn on top of the canvas (document space). */
sealed interface ToolOverlay {
    data class Path(val points: List<Offset>, val closed: Boolean = false) : ToolOverlay
    data class Circle(val center: Offset, val radiusPu: Float) : ToolOverlay
    data class Rect(val a: Offset, val b: Offset) : ToolOverlay
}

/** Tools other than live ink (eraser, lasso, rectangle, transform). Implemented by WP-E/WP-H; docs section 3. */
interface ToolHandler {
    fun onDown(p: DocPoint, tool: InputToolType)
    fun onMove(points: List<DocPoint>)
    fun onUp()
    fun onCancel()

    /** Two fingers landed on the selected object ([InputRouter] `GestureKind.ObjectPinch`): pinch scales, twist rotates. */
    fun onPinchStart(a: DocPoint, b: DocPoint) {}
    fun onPinchMove(a: DocPoint, b: DocPoint) {}
    /** One of the two fingers lifted: commit (one undo step). */
    fun onPinchEnd() {}
    fun onPinchCancel() {}
}
