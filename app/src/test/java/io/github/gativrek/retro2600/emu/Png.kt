package io.github.gativrek.retro2600.emu

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Minimal RGB PNG writer (java.awt isn't on the Android unit-test classpath). */
object Png {
    fun write(file: File, width: Int, height: Int, argb: (x: Int, y: Int) -> Int) {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val row = ByteArray(1 + width * 3)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val c = argb(x, y)
                    row[1 + x * 3] = (c shr 16).toByte()
                    row[2 + x * 3] = (c shr 8).toByte()
                    row[3 + x * 3] = c.toByte()
                }
                z.write(row)
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            val ihdr = ByteArrayOutputStream().also {
                DataOutputStream(it).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray()
            chunk(out, "IHDR", ihdr)
            chunk(out, "IDAT", raw.toByteArray())
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.writeInt(crc.value.toInt())
    }
}
