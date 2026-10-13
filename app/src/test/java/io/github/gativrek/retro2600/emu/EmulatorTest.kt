package io.github.gativrek.retro2600.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EmulatorTest {

    private val outDir = File("build/test-shots").apply { mkdirs() }

    /** A standard 262-line kernel that places every object at a known cycle. */
    private fun testKernel(): ByteArray = Asm().run {
        sei(); cld(); ldxI(0xFF); txs()
        ldaI(0); ldxI(0)
        label("clear"); staZpX(0); dex(); bne("clear")

        label("frame")
        ldaI(2); sta(WSYNC); sta(VSYNC); sta(WSYNC); sta(WSYNC); sta(WSYNC); ldaI(0); sta(VSYNC)
        ldaI(2); sta(VBLANK)
        ldaI(43); staAbs(TIM64T)

        // RESP0 ending on cycle 23, RESP1 on 33, RESM0 on 43, RESBL on 53.
        sta(WSYNC); nop(10); sta(RESP0)
        sta(WSYNC); nop(15); sta(RESP1)
        sta(WSYNC); nop(20); sta(RESM0)
        sta(WSYNC); nop(25); sta(RESBL)
        // Nudge P1 one pixel right with HMOVE.
        ldaI(0xF0); sta(HMP1)
        sta(WSYNC); sta(HMOVE)

        ldaI(0x1E); sta(COLUP0); ldaI(0x46); sta(COLUP1); ldaI(0xC6); sta(COLUPF); ldaI(0x00); sta(COLUBK)
        ldaI(0x81); sta(GRP0); ldaI(0xFF); sta(GRP1)
        ldaI(0x01); sta(NUSIZ0)
        ldaI(2); sta(ENAM0); sta(ENABL)
        ldaI(0x30); sta(PF0); ldaI(0x00); sta(PF1); ldaI(0x80); sta(PF2)
        ldaI(0x01); sta(CTRLPF)
        ldaI(4); sta(AUDC0); ldaI(16); sta(AUDF0); ldaI(8); sta(AUDV0)

        label("vbl"); ldaAbs(INTIM); bne("vbl")
        sta(WSYNC); ldaI(0); sta(VBLANK)
        ldxI(192)
        label("vis"); sta(WSYNC); dex(); bne("vis")
        ldaI(2); sta(VBLANK)
        ldxI(30)
        label("os"); sta(WSYNC); dex(); bne("os")
        jmp("frame")
        build()
    }

    @Test
    fun kernelFrameTimingAndObjectPositions() {
        val emu = Atari2600(testKernel())
        repeat(30) { emu.runFrame() }
        assertTrue("frame lines ${emu.tia.lastFrameLines}", emu.tia.lastFrameLines in 258..266)

        val screen = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
        emu.copyScreen(screen)
        save(screen, "kernel")

        // Inspect one line in the middle of the picture.
        val row = 100 * Atari2600.SCREEN_WIDTH
        val p0 = Palette.NTSC[0x1E shr 1]; val p1 = Palette.NTSC[0x46 shr 1]; val pf = Palette.NTSC[0xC6 shr 1]
        fun xs(color: Int) = (0 until 160).filter { screen[row + it] == color }
        println("P0 at ${xs(p0)}")
        println("P1 at ${xs(p1)}")
        println("PF at ${xs(pf)}")

        // Expected from the TIA counter model: players appear 4 clocks after the write cycle
        // ends, missiles/ball 3, and the HMOVE moved P1 right by one.
        // P0 (pattern 10000001, two close copies) and M0 share COLUP0.
        assertEquals(listOf(5, 12, 21, 28, 64, 80), xs(p0)) // the missile copies with NUSIZ too
        assertEquals((36..43).toList(), xs(p1))
        // Playfield: PF0=$30 -> x 0..7 (P0's pixel 5 covers one), PF2 bit 7 -> 76..79, mirrored on the right,
        // ball at 94.
        val expectedPf = (0..7).filter { it != 5 } + (76..83).filter { it != 80 } + listOf(94) + (152..159)
        assertEquals(expectedPf, xs(pf))

        // P0 overlaps the playfield at x=5 -> CXP0FB bit 7.
        assertEquals(0x80, emu.tia.read(0x02) and 0x80)
        // AUDC 4 is a square wave: the samples must toggle.
        val pcm = ShortArray(4096)
        emu.drainAudio(pcm)
        emu.runFrame()
        val n = emu.drainAudio(pcm)
        assertTrue("samples per frame $n", n in 500..540)
        val toggles = (1 until n).count { pcm[it] != pcm[it - 1] }
        assertTrue("square wave toggles $toggles", toggles > 20)
    }

    /** Activision kernels end lines with STA WSYNC landing exactly on cycle 76; that must not cost a line. */
    @Test
    fun wsyncOnCycle76DoesNotSkipALine() {
        val rom = Asm().run {
            label("frame")
            ldaI(2); sta(WSYNC); sta(VSYNC); sta(WSYNC); sta(WSYNC); sta(WSYNC); ldaI(0); sta(VSYNC)
            ldxI(200)
            sta(WSYNC)
            // bne (3) + 34 nops (68) + dex (2) + sta WSYNC (3) = 76 cycles per line.
            label("line"); nop(34); dex(); sta(WSYNC); bne("line")
            jmp("frame")
            build()
        }
        val emu = Atari2600(rom)
        repeat(5) { emu.runFrame() }
        assertTrue("lines ${emu.tia.lastFrameLines}", emu.tia.lastFrameLines in 200..210)
    }

    @Test
    fun speed() {
        val emu = Atari2600(testKernel())
        repeat(120) { emu.runFrame() }
        val t0 = System.nanoTime()
        val frames = 1200
        repeat(frames) { emu.runFrame() }
        val ms = (System.nanoTime() - t0) / 1e6
        println("JVM: %.3f ms per frame (%.0f fps)".format(ms / frames, frames * 1000 / ms))
    }

    /**
     * Klaus Dormann's 6502 functional test (not shipped). Put 6502_functional_test.bin in
     * testdata/ to run it; success is the trap at $3469.
     */
    @Test
    fun klausDormannFunctionalTest() {
        val file = listOf(File("../testdata/6502_functional_test.bin"), File("testdata/6502_functional_test.bin"))
            .firstOrNull { it.exists() }
        assumeTrue("6502_functional_test.bin not present", file != null)
        val mem = IntArray(65536)
        file!!.readBytes().forEachIndexed { k, b -> mem[k] = b.toInt() and 0xFF }
        val bus = object : Bus {
            override fun read(addr: Int) = mem[addr]
            override fun write(addr: Int, value: Int) { mem[addr] = value }
        }
        val cpu = Cpu6502(bus)
        cpu.pc = 0x0400
        var last = -1
        var steps = 0L
        while (cpu.pc != last) {
            last = cpu.pc
            cpu.step()
            steps++
            require(steps < 200_000_000) { "runaway" }
        }
        println("Functional test trapped at $%04X after %d instructions".format(cpu.pc, steps))
        assertEquals(0x3469, cpu.pc)
    }

    /** Screenshots of real games: set ROMS_DIR to a folder of .a26 files. */
    @Test
    fun romScreenshots() {
        val dir = System.getenv("ROMS_DIR")?.let(::File)
        assumeTrue("ROMS_DIR not set", dir != null && dir.isDirectory)
        val screen = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
        dir!!.listFiles { f -> f.extension.lowercase() in setOf("a26", "bin") }!!.sorted().forEach { f ->
            val emu = Atari2600(f.readBytes())
            val t0 = System.nanoTime()
            for (frame in 0 until 900) {
                val input = when (frame) {
                    in 60..70 -> Atari2600.RESET
                    in 200..330 -> Atari2600.FIRE or Atari2600.DOWN
                    in 331..899 -> Atari2600.FIRE or (if ((frame / 45) % 2 == 0) Atari2600.LEFT else Atari2600.RIGHT)
                    else -> 0
                }
                emu.setInput(input)
                emu.runFrame()
                if (frame == 50 || frame == 300 || frame == 450 || frame == 600 || frame == 899) {
                    emu.copyScreen(screen)
                    save(screen, "${f.nameWithoutExtension}-$frame")
                }
            }
            val ms = (System.nanoTime() - t0) / 1e6 / 900
            println("${f.name}: ${emu.cart.type}, ${emu.tia.lastFrameLines} lines, %.3f ms/frame".format(ms))
        }
    }

    private fun save(screen: IntArray, name: String) {
        val w = Atari2600.SCREEN_WIDTH; val h = Atari2600.SCREEN_HEIGHT
        Png.write(File(outDir, "$name.png"), w * 4, h * 2) { x, y -> screen[(y / 2) * w + x / 4] }
    }
}

/** An interactive ROM for checking the touch controls on a device: stick moves, fire = color + tone. */
class DemoRomTest {
    private fun demoRom(): ByteArray = Asm().run {
        sei(); cld(); ldxI(0xFF); txs()
        ldaI(0); ldxI(0)
        label("clear"); staZpX(0); dex(); bne("clear")
        ldaI(0xFF); sta(GRP0)
        ldaI(0x1E); sta(COLUP0)
        ldaI(4); sta(AUDC0); ldaI(12); sta(AUDF0)
        sta(WSYNC); nop(30); sta(RESP0)

        label("frame")
        ldaI(2); sta(WSYNC); sta(VSYNC); sta(WSYNC); sta(WSYNC); sta(WSYNC); ldaI(0); sta(VSYNC)
        ldaI(2); sta(VBLANK)
        ldaI(43); staAbs(TIM64T)

        ldaI(0); sta(HMP0)
        ldaAbs(SWCHA); andI(0x80); bne("noRight"); ldaI(0xE0); sta(HMP0)   // right 2 px/frame
        label("noRight")
        ldaAbs(SWCHA); andI(0x40); bne("noLeft"); ldaI(0x20); sta(HMP0)    // left 2 px/frame
        label("noLeft")
        ldaAbs(SWCHA); andI(0x10); bne("noUp"); ldaI(0x07); sta(NUSIZ0)    // up: quad width
        label("noUp")
        ldaAbs(SWCHA); andI(0x20); bne("noDown"); ldaI(0x00); sta(NUSIZ0)  // down: normal
        label("noDown")
        ldaAbs(SWCHB); andI(0x01); bne("noReset"); sta(WSYNC); nop(30); sta(RESP0)
        label("noReset")
        ldaAbs(SWCHB); andI(0x02); bne("noSelect"); ldaI(0x86); sta(COLUP0)
        label("noSelect")
        sta(WSYNC); sta(HMOVE)

        lda(INPT4); bmi("noFire")
        ldaI(0x44); sta(COLUBK); ldaI(8); sta(AUDV0); jmp("fireDone")
        label("noFire")
        ldaI(0x02); sta(COLUBK); ldaI(0); sta(AUDV0)
        label("fireDone")

        label("vbl"); ldaAbs(INTIM); bne("vbl")
        sta(WSYNC); ldaI(0); sta(VBLANK)
        ldxI(192)
        label("vis"); sta(WSYNC); dex(); bne("vis")
        ldaI(2); sta(VBLANK)
        ldxI(30)
        label("os"); sta(WSYNC); dex(); bne("os")
        jmp("frame")
        build()
    }

    @Test
    fun stickMovesThePlayer() {
        val rom = demoRom()
        File("build/test-shots").mkdirs()
        File("build/test-shots/Demo dos Controles.a26").writeBytes(rom)
        val emu = Atari2600(rom)
        val screen = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
        val yellow = Palette.NTSC[0x1E shr 1]
        fun playerX(): Int {
            emu.copyScreen(screen)
            return (0 until 160).first { screen[100 * 160 + it] == yellow }
        }
        repeat(10) { emu.runFrame() }
        val start = playerX()
        emu.setInput(Atari2600.RIGHT)
        repeat(10) { emu.runFrame() }
        assertEquals(start + 20, playerX())
        emu.setInput(Atari2600.LEFT)
        repeat(5) { emu.runFrame() }
        assertEquals(start + 10, playerX())
        emu.setInput(Atari2600.FIRE)
        emu.runFrame(); emu.runFrame()
        emu.copyScreen(screen)
        assertEquals(Palette.NTSC[0x44 shr 1], screen[100 * 160 + 0])
    }
}
