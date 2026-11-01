package org.samesame.notes.core.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.model.PenType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Binary layer file for one page's strokes.
 *
 * ```
 * magic "SSNK" · u16 version · u32 strokeCount
 * per stroke: utf penType · bool pressure · i32 argb · f32 size · f32 epsilon
 *             u32 byteCount · androidx.ink StrokeInputBatch (gzipped proto)
 * ```
 * Version 1 only: new files are written by [org.samesame.notes.core.ink.page.PageLayerCodec] (v2),
 * which delegates here to read v1 files. Only the raw inputs are stored; meshes are rebuilt from them on load.
 */
object StrokeCodec {
    private const val MAGIC = 0x53534E4B // "SSNK"
    private const val VERSION = 1

    fun encode(strokes: List<Stroke>, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeShort(VERSION)
        out.writeInt(strokes.size)
        val inputBytes = ByteArrayOutputStream()
        for (stroke in strokes) {
            val (type, pressure) = PenBrushes.parseKey(stroke.brush.family.developerComment)
                ?: (PenType.Pen to true)
            out.writeUTF(type.name)
            out.writeBoolean(pressure)
            out.writeInt(stroke.brush.colorIntArgb)
            out.writeFloat(stroke.brush.size)
            out.writeFloat(stroke.brush.epsilon)
            inputBytes.reset()
            stroke.inputs.encode(inputBytes)
            out.writeInt(inputBytes.size())
            inputBytes.writeTo(out)
        }
        out.flush()
    }

    fun decode(input: InputStream): List<Stroke> {
        val data = DataInputStream(input)
        if (data.readInt() != MAGIC) throw IOException("Not a SameSame notes ink layer")
        val version = data.readUnsignedShort()
        if (version > VERSION) throw IOException("Ink layer version $version is newer than this app supports")
        return readV1Strokes(data) { key -> PenBrushes.parseKey(key)?.let { PenBrushes.familyFor(it.first, it.second) } ?: PenBrushes.familyFor(PenType.Pen, true) }
    }

    /**
     * Reads the v1 body (everything after the version field). [resolveFamily] receives the
     * synthesised `samesame/<Type>/<pressure|fixed>` key of each stroke.
     */
    internal fun readV1Strokes(data: DataInputStream, resolveFamily: (String) -> BrushFamily): List<Stroke> {
        val count = PageLayerCodec.checkedCount(data.readInt())
        val result = ArrayList<Stroke>(minOf(count, 1024))
        repeat(count) {
            val typeName = data.readUTF()
            val type = PenType.entries.firstOrNull { it.name == typeName } ?: throw IOException("Unknown pen type")
            val pressure = data.readBoolean()
            val argb = data.readInt()
            val size = data.readFloat()
            val epsilon = data.readFloat()
            val bytes = PageLayerCodec.readPayload(data, data.readInt())
            val brush = Brush.createWithColorIntArgb(resolveFamily(PenBrushes.keyOf(type, pressure)), argb, size, epsilon)
            result += Stroke(brush, StrokeInputBatch.decode(ByteArrayInputStream(bytes)))
        }
        return result
    }
}
