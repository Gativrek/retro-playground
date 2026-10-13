package io.github.gativrek.retro2600.emu

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TraceTest {
    @Test
    fun traceScoreKernel() {
        val dir = System.getenv("ROMS_DIR")?.let(::File)
        assumeTrue(dir != null)
        val name = System.getenv("TRACE_ROM") ?: "Seaquest.a26"
        val emu = Atari2600(File(dir, name).readBytes())
        repeat(300) { emu.runFrame() }
        val names = mapOf(0x04 to "NUSIZ0", 0x05 to "NUSIZ1", 0x10 to "RESP0", 0x11 to "RESP1", 0x1B to "GRP0",
            0x1C to "GRP1", 0x20 to "HMP0", 0x21 to "HMP1", 0x25 to "VDELP0", 0x26 to "VDELP1", 0x2A to "HMOVE",
            0x02 to "WSYNC", 0x2B to "HMCLR", 0x06 to "COLUP0", 0x07 to "COLUP1")
        val lo = (System.getenv("TRACE_FROM") ?: "0").toInt()
        val hi = (System.getenv("TRACE_TO") ?: "70").toInt()
        emu.tia.writeHook = { line, h, reg, v ->
            if (line in lo..hi && reg in names) println("L%3d h%3d c%2d x%4d %-7s %02X".format(line, h, h / 3, h - 68, names[reg], v))
        }
        emu.runFrame()
        emu.tia.writeHook = null
        val screen = IntArray(160 * 210)
        emu.copyScreen(screen)
    }
}
