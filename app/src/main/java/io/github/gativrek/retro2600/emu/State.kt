package io.github.gativrek.retro2600.emu

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Sequential, compressed writer for save states. Fields are read back in the same order. */
class StateWriter {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(DeflaterOutputStream(bytes))

    fun int(v: Int) = out.writeInt(v)
    fun long(v: Long) = out.writeLong(v)
    fun bool(v: Boolean) = out.writeBoolean(v)
    fun ints(a: IntArray) { int(a.size); for (v in a) out.writeInt(v) }

    fun finish(): ByteArray {
        out.close()
        return bytes.toByteArray()
    }
}

class StateReader(data: ByteArray) {
    private val input = DataInputStream(InflaterInputStream(ByteArrayInputStream(data)))

    fun int() = input.readInt()
    fun long() = input.readLong()
    fun bool() = input.readBoolean()

    /** Fills [dest], which must have the size that was saved. */
    fun ints(dest: IntArray) {
        val n = int()
        require(n == dest.size) { "save state does not match this console" }
        for (k in 0 until n) dest[k] = input.readInt()
    }
}
