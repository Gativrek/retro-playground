package io.github.gativrek.retro2600.emu

/** The whole console: CPU, TIA, RIOT, 128 bytes of RAM and a cartridge on a 13-bit bus. */
class Atari2600(rom: ByteArray) : Bus {
    companion object {
        const val SCREEN_WIDTH = Tia.WIDTH
        const val SCREEN_HEIGHT = 210

        /** NTSC: 262 lines x 228 color clocks at 3.579545 MHz. */
        const val FRAMES_PER_SECOND = 3579545.0 / (228 * 262)
        /** Two audio samples per scanline. */
        const val AUDIO_SAMPLE_RATE = 31400

        // Input bits for [setInput].
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 4
        const val RIGHT = 8
        const val FIRE = 16
        const val RESET = 32
        const val SELECT = 64

        /** A frame never takes more than ~1.5 frames' worth of CPU time. */
        private const val MAX_CYCLES_PER_FRAME = 40_000

        private const val STATE_MAGIC = 0x52323653 // "R26S"
        private const val STATE_VERSION = 1
    }

    private val romCrc = java.util.zip.CRC32().run { update(rom); value }
    val cart = Cartridge.load(rom)
    val tia = Tia()
    private val riot = Riot()
    private val ram = IntArray(128)
    private val cpu = Cpu6502(this)

    /** CPU cycles since power-on. */
    var cycles = 0L
        private set
    private var dataBus = 0

    // Visible window, following where the game turns VBLANK off.
    private var windowTop = 31
    private var candidateTop = -1
    private var candidateFrames = 0

    init {
        cpu.reset()
    }

    private fun cycle() {
        cycles++
        tia.clock(); tia.clock(); tia.clock()
    }

    override fun read(addr: Int): Int {
        // WSYNC pulls RDY low: the CPU stalls on its next read until the scanline ends.
        while (tia.wsync) cycle()
        cycle()
        val a = addr and 0x1FFF
        val value = when {
            (a and 0x1000) != 0 -> cart.read(a)
            (a and 0x80) == 0 -> tia.read(a) or (dataBus and 0x3F)
            (a and 0x200) == 0 -> ram[a and 0x7F]
            else -> riot.read(a, cycles)
        }
        dataBus = value
        return value
    }

    override fun write(addr: Int, value: Int) {
        cycle()
        val a = addr and 0x1FFF
        dataBus = value
        when {
            (a and 0x1000) != 0 -> cart.write(a, value)
            (a and 0x80) == 0 -> {
                tia.write(a, value)
                cart.tiaWrite(a, value)
            }
            (a and 0x200) == 0 -> ram[a and 0x7F] = value
            else -> riot.write(a, value, cycles)
        }
    }

    /**
     * The whole machine as a compact blob. Taken between frames (after [runFrame]), it restores
     * exactly: running the same input afterwards gives the same frames and sound.
     */
    fun saveState(): ByteArray {
        val w = StateWriter()
        w.int(STATE_MAGIC); w.int(STATE_VERSION); w.long(romCrc)
        cpu.save(w)
        w.ints(ram); w.long(cycles); w.int(dataBus)
        w.int(windowTop); w.int(candidateTop); w.int(candidateFrames)
        riot.save(w)
        tia.save(w)
        cart.save(w)
        return w.finish()
    }

    /** Restores a [saveState] blob; throws (leaving the console unusable) if it's damaged or for another game. */
    fun loadState(data: ByteArray) {
        val r = StateReader(data)
        require(r.int() == STATE_MAGIC && r.int() == STATE_VERSION) { "unknown save state format" }
        require(r.long() == romCrc) { "save state belongs to another game" }
        cpu.load(r)
        r.ints(ram); cycles = r.long(); dataBus = r.int()
        windowTop = r.int(); candidateTop = r.int(); candidateFrames = r.int()
        riot.load(r)
        tia.load(r)
        cart.load(r)
    }

    /** One byte of console RAM ($80-$FF), by offset 0..127. */
    fun peekRam(offset: Int) = ram[offset and 0x7F]

    /** Joystick, fire button and console switches, as a mask of the input bits above. */
    fun setInput(mask: Int) {
        var joy = 0xFF
        if ((mask and RIGHT) != 0) joy = joy and 0x80.inv()
        if ((mask and LEFT) != 0) joy = joy and 0x40.inv()
        if ((mask and DOWN) != 0) joy = joy and 0x20.inv()
        if ((mask and UP) != 0) joy = joy and 0x10.inv()
        // Opposite directions at once can confuse games; real joysticks can't do it.
        if ((joy and 0xC0) == 0) joy = joy or 0xC0
        if ((joy and 0x30) == 0) joy = joy or 0x30
        riot.swchaIn = joy
        tia.fire0 = (mask and FIRE) != 0
        var switches = 0x0B // color, both difficulty switches on B
        if ((mask and RESET) != 0) switches = switches and 0x01.inv()
        if ((mask and SELECT) != 0) switches = switches and 0x02.inv()
        riot.swchbIn = switches
    }

    /** Runs until the game starts a new frame (VSYNC), with a safety limit. */
    fun runFrame() {
        tia.frameReady = false
        val limit = cycles + MAX_CYCLES_PER_FRAME
        while (!tia.frameReady && cycles < limit) cpu.step()
        updateWindow()
    }

    private fun updateWindow() {
        val first = tia.lastFirstVisible
        val wanted = if (first in 16..100) first - 9 else 31
        if (wanted == windowTop) {
            candidateFrames = 0
            return
        }
        // Only move the window once the game has settled, so it never jitters.
        if (wanted == candidateTop) {
            if (++candidateFrames >= 15) {
                windowTop = wanted
                candidateFrames = 0
            }
        } else {
            candidateTop = wanted
            candidateFrames = 1
        }
    }

    /** Copies the visible 160x210 window of the last frame into [dest]. */
    fun copyScreen(dest: IntArray) {
        val src = tia.frameBuffer
        val lines = minOf(tia.lastFrameLines, Tia.MAX_LINES)
        for (row in 0 until SCREEN_HEIGHT) {
            val srcLine = windowTop + row
            val o = row * SCREEN_WIDTH
            if (srcLine < lines) {
                System.arraycopy(src, srcLine * SCREEN_WIDTH, dest, o, SCREEN_WIDTH)
            } else {
                dest.fill(0xFF000000.toInt(), o, o + SCREEN_WIDTH)
            }
        }
    }

    /** Moves this frame's audio (raw 0..30 levels) into [dest]; returns the sample count. */
    fun drainAudio(dest: ShortArray): Int {
        val n = minOf(tia.audioCount, dest.size)
        System.arraycopy(tia.audio, 0, dest, 0, n)
        tia.audioCount = 0
        return n
    }
}
