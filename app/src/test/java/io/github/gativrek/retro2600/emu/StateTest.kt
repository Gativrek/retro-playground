package io.github.gativrek.retro2600.emu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class StateTest {

    private fun roms(): List<File> {
        val dir = System.getenv("ROMS_DIR")?.let(::File)
        assumeTrue("ROMS_DIR not set", dir != null && dir.isDirectory)
        return dir!!.listFiles { f -> f.extension.lowercase() in setOf("a26", "bin") }!!.sorted()
    }

    /** Deterministic "player": changes direction every 30 frames and fires a lot. */
    private fun input(frame: Int): Int {
        if (frame in 60..70) return Atari2600.RESET
        if (frame < 100) return 0
        val dirs = intArrayOf(Atari2600.LEFT, Atari2600.RIGHT, Atari2600.UP, Atari2600.DOWN, 0)
        return dirs[(frame / 30 * 7) % dirs.size] or (if ((frame / 10) % 3 != 0) Atari2600.FIRE else 0)
    }

    /** Hash of every frame and audio buffer over [count] frames starting at [first]. */
    private fun run(emu: Atari2600, first: Int, count: Int): List<Int> {
        val screen = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
        val pcm = ShortArray(4096)
        return (first until first + count).map { frame ->
            emu.setInput(input(frame))
            emu.runFrame()
            emu.copyScreen(screen)
            val n = emu.drainAudio(pcm)
            31 * screen.contentHashCode() + pcm.copyOf(n).contentHashCode()
        }
    }

    private fun assertRestoresExactly(rom: ByteArray, name: String) {
        val emu = Atari2600(rom)
        run(emu, 0, 400)
        val state = emu.saveState()
        val screenAtSave = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT).also(emu::copyScreen)
        val expected = run(emu, 400, 300)

        val restored = Atari2600(rom)
        restored.loadState(state)
        // The saved frame is visible immediately, before running anything.
        val screenAfterLoad = IntArray(screenAtSave.size).also(restored::copyScreen)
        assertArrayEquals("$name: frame shown after loading", screenAtSave, screenAfterLoad)
        assertArrayEquals("$name: state round trip", state, restored.saveState())
        assertEquals("$name: frames and audio after loading", expected, run(restored, 400, 300))
        println("$name: ${state.size} bytes")
    }

    @Test
    fun demoRomRestoresExactly() = assertRestoresExactly(DemoRomTest().demoRom(), "demo")

    @Test
    fun realGamesRestoreExactly() = roms().forEach { assertRestoresExactly(it.readBytes(), it.name) }

    @Test(expected = IllegalArgumentException::class)
    fun stateFromAnotherGameIsRejected() {
        val a = Atari2600(DemoRomTest().demoRom())
        repeat(10) { a.runFrame() }
        val other = DemoRomTest().demoRom().also { it[0] = (it[0] + 1).toByte() }
        Atari2600(other).loadState(a.saveState())
    }

    @Test
    fun scoresOfKnownGames() {
        roms().forEach { f ->
            val rom = f.readBytes()
            val reader = ScoreReader.forRom(ScoreReader.md5(rom)) ?: return@forEach
            val emu = Atari2600(rom)
            var best = 0
            var last = 0
            // Seaquest only scores underwater: hold down for a while every few seconds.
            val dive = f.name.startsWith("Seaquest")
            for (frame in 0 until 2500) {
                emu.setInput(input(frame) or (if (dive && frame > 100 && (frame / 90) % 2 == 0) Atari2600.DOWN else 0))
                emu.runFrame()
                val s = reader.read(emu)
                if (frame in 80..2499 && s >= 0) {
                    // Within one game the score never goes down.
                    assertTrue("${f.name}: score went from $last to $s at frame $frame", s >= last)
                    last = s
                }
                best = maxOf(best, s)
            }
            println("${f.name}: best $best")
            assertTrue("${f.name}: no score read", best > 0)
        }
    }

    @Test
    fun flickerFilterBlendsOnlySustainedFlicker() {
        val size = 160 * 210
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        fun frame(t: Int, flickering: Int) = IntArray(size) { i -> if (i < flickering && t % 2 == 0) red else blue }

        // A small animation never turns it on.
        val small = FlickerFilter(size)
        repeat(200) { small.process(frame(it, 200)) }
        assertFalse(small.active)

        // A big flickering sprite does, and is then shown half-blended.
        val big = FlickerFilter(size)
        repeat(100) { big.process(frame(it, 2000)) }
        assertTrue(big.active)
        val out = frame(100, 2000).also(big::process)
        assertEquals(0xFF7F007F.toInt(), out[0])
        assertEquals(blue, out[size - 1])

        // It turns off again once the game stops flickering.
        repeat(100) { big.process(frame(1, 0)) }
        assertFalse(big.active)
    }
}
