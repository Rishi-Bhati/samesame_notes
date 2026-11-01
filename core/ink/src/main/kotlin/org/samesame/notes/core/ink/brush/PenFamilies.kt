package org.samesame.notes.core.ink.brush

import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ValueNode
import org.samesame.notes.core.model.PenType

/** Brush family definitions for the pen catalog (docs/editor-architecture.md 4.1). */
internal object PenFamilies {
    fun build(type: PenType, pressure: Boolean, key: String): BrushFamily {
        val (tip, paint) = when (type) {
            PenType.Fountain -> fountain(pressure) to BrushPaint()
            PenType.Calligraphy -> calligraphy(pressure) to BrushPaint()
            PenType.Pen -> pen(pressure) to BrushPaint()
            PenType.Pencil -> pencil(pressure) to grainPaint()
            PenType.Brush -> brush(pressure) to BrushPaint()
            PenType.Highlighter -> highlighter() to BrushPaint(emptyList(), emptyList(), SelfOverlap.DISCARD)
        }
        return BrushFamily(tip, paint, BrushFamily.InputModel.DEFAULT_INPUT_MODEL, key)
    }

    /** Round marker tip: like the highlighter (flat colour, no self-overlap darkening) but with a round, full-width tip. */
    fun buildRoundMarker(key: String): BrushFamily = BrushFamily(
        tip(scaleX = 1f, scaleY = 1f, rounding = 1f, b = emptyList()),
        BrushPaint(emptyList(), emptyList(), SelfOverlap.DISCARD),
        BrushFamily.InputModel.DEFAULT_INPUT_MODEL, key,
    )

    private fun source(s: SourceNode.Source, start: Float, end: Float) =
        SourceNode(s, start, end, OutOfRange.CLAMP)

    private fun mapped(input: ValueNode, target: TargetNode.Target, from: Float, to: Float) =
        BrushBehavior(TargetNode(target, from, to, ResponseNode(EasingFunction.Predefined.LINEAR, input)))

    private fun pressureSize(from: Float, to: Float) = mapped(
        source(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f), TargetNode.Target.SIZE_MULTIPLIER, from, to,
    )

    private fun tip(rotation: Float = 0f, scaleY: Float = 1f, scaleX: Float = 1f, rounding: Float = 1f, b: List<BrushBehavior>) =
        BrushTip(scaleX = scaleX, scaleY = scaleY, cornerRounding = rounding, rotationDegrees = rotation, behaviors = b)

    private fun fountain(pressure: Boolean): BrushTip {
        val b = mutableListOf<BrushBehavior>()
        if (pressure) b += pressureSize(0.35f, 1f)
        // Faster strokes thin the line slightly, smoothed so it does not wobble.
        b += mapped(
            DampingNode(
                ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE, 0.5f,
                source(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, 0f, 25f),
            ),
            TargetNode.Target.SIZE_MULTIPLIER, 1f, 0.85f,
        )
        return tip(b = b)
    }

    // Chisel nib at a fixed 45 degrees: thick on down-strokes, thin across.
    private fun calligraphy(pressure: Boolean) =
        tip(rotation = 45f, scaleY = 0.25f, rounding = 0f, b = if (pressure) listOf(pressureSize(0.75f, 1f)) else emptyList())

    private fun pen(pressure: Boolean) = tip(b = if (pressure) listOf(pressureSize(0.85f, 1f)) else emptyList())

    private fun pencil(pressure: Boolean) = tip(
        b = if (pressure) {
            listOf(
                pressureSize(0.6f, 1f),
                mapped(source(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f), TargetNode.Target.OPACITY_MULTIPLIER, 0.55f, 1f),
            )
        } else emptyList(),
    )

    private fun brush(pressure: Boolean): BrushTip {
        val b = mutableListOf<BrushBehavior>()
        if (pressure) b += pressureSize(0.1f, 1f)
        // Taper at both ends over three brush widths.
        b += mapped(
            source(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 3f),
            TargetNode.Target.SIZE_MULTIPLIER, 0.2f, 1f,
        )
        b += mapped(
            source(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 3f),
            TargetNode.Target.SIZE_MULTIPLIER, 0.2f, 1f,
        )
        return tip(b = b)
    }

    // Flat rectangular marker tip; alpha comes from PenType.fixedAlpha.
    private fun highlighter() = tip(scaleX = 0.5f, scaleY = 1f, rounding = 0f, b = emptyList())

    private fun grainPaint(): BrushPaint = BrushPaint(
        listOf(
            BrushPaint.TilingTexture(
                clientTextureId = GrainTextureStore.GRAIN_ID,
                sizeX = 24f, sizeY = 24f, offsetX = 0f, offsetY = 0f, rotationDegrees = 0f,
                sizeUnit = BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES,
                origin = BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN,
                wrapX = BrushPaint.TextureLayer.Wrap.REPEAT,
                wrapY = BrushPaint.TextureLayer.Wrap.REPEAT,
                blendMode = BrushPaint.TextureLayer.BlendMode.MODULATE,
            ),
        ),
    )
}
