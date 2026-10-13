package io.github.gativrek.retro2600.emu

/*
 * TIA (Television Interface Adaptor): video and sound.
 *
 * Emulated one color clock at a time, with the movable objects modelled as the hardware does it:
 * free-running position counters that tick on every visible clock, plus extra ticks during an
 * HMOVE. The structure follows Stella's TIA (by Christian Speckner), so the Activision-style
 * HMOVE bars, same-line RESPx behavior and mid-line register changes come out right.
 */

private const val H_CLOCKS = 228
private const val H_BLANK = 68
private const val BLACK = 0xFF000000.toInt()

/** Values a position counter is reset to by RESPx/RESMx/RESBL. */
private const val RESX_HBLANK = 158
private const val RESX_FRAME = 157

/** For each NUSIZ copy mode: the counter values that start drawing a copy (main copy at 156). */
private val DECODES: Array<BooleanArray> = Array(8) { mode ->
    BooleanArray(160).also { t ->
        t[156] = true
        when (mode) {
            1 -> t[12] = true
            2 -> t[28] = true
            3 -> { t[12] = true; t[28] = true }
            4 -> t[60] = true
            6 -> { t[28] = true; t[60] = true }
        }
    }
}

/** log2 of the player stretch factor for each NUSIZ mode (5 = double, 7 = quad). */
private val PLAYER_SHIFT = intArrayOf(0, 0, 0, 0, 0, 1, 0, 2)

// Object bits used to index the collision table.
private const val O_P0 = 1
private const val O_P1 = 2
private const val O_M0 = 4
private const val O_M1 = 8
private const val O_BL = 16
private const val O_PF = 32

/**
 * Collision latch bit layout (pairs map straight onto the read registers):
 * 0 M0-P1, 1 M0-P0, 2 M1-P0, 3 M1-P1, 4 P0-PF, 5 P0-BL, 6 P1-PF, 7 P1-BL,
 * 8 M0-PF, 9 M0-BL, 10 M1-PF, 11 M1-BL, 12 BL-PF, 13 P0-P1, 14 M0-M1.
 */
private val COLLISIONS = IntArray(64) { m ->
    fun has(o: Int) = (m and o) != 0
    var r = 0
    if (has(O_M0) && has(O_P1)) r = r or (1 shl 0)
    if (has(O_M0) && has(O_P0)) r = r or (1 shl 1)
    if (has(O_M1) && has(O_P0)) r = r or (1 shl 2)
    if (has(O_M1) && has(O_P1)) r = r or (1 shl 3)
    if (has(O_P0) && has(O_PF)) r = r or (1 shl 4)
    if (has(O_P0) && has(O_BL)) r = r or (1 shl 5)
    if (has(O_P1) && has(O_PF)) r = r or (1 shl 6)
    if (has(O_P1) && has(O_BL)) r = r or (1 shl 7)
    if (has(O_M0) && has(O_PF)) r = r or (1 shl 8)
    if (has(O_M0) && has(O_BL)) r = r or (1 shl 9)
    if (has(O_M1) && has(O_PF)) r = r or (1 shl 10)
    if (has(O_M1) && has(O_BL)) r = r or (1 shl 11)
    if (has(O_BL) && has(O_PF)) r = r or (1 shl 12)
    if (has(O_P0) && has(O_P1)) r = r or (1 shl 13)
    if (has(O_M0) && has(O_M1)) r = r or (1 shl 14)
    r
}

/** Bit-reversed bytes, for PF1 which is drawn MSB first. */
private val REVERSE = IntArray(256) { b ->
    var r = 0
    for (k in 0 until 8) if (((b shr k) and 1) != 0) r = r or (1 shl (7 - k))
    r
}

internal class Player {
    var counter = 0
    var rendering = false
    var renderIdx = 0
    var mode = 0
    var grpNew = 0
    var grpOld = 0
    var vdel = false
    var reflect = false
    var hmClocks = 8
    var moving = false

    fun tick() {
        if (DECODES[mode][counter]) {
            rendering = true
            renderIdx = -5
        } else if (rendering) {
            renderIdx++
            val shift = PLAYER_SHIFT[mode]
            // Stretched players start one clock later than normal ones.
            if (renderIdx - (if (shift > 0) 1 else 0) >= (8 shl shift)) rendering = false
        }
        if (++counter == 160) counter = 0
    }

    fun movementTick(clock: Int, hblank: Boolean) {
        if (clock == hmClocks) moving = false
        if (moving && hblank) tick()
    }

    fun pixel(): Boolean {
        if (!rendering) return false
        val shift = PLAYER_SHIFT[mode]
        val i = renderIdx - (if (shift > 0) 1 else 0)
        if (i < 0) return false
        val bit = i shr shift
        if (bit > 7) return false
        val g = if (vdel) grpOld else grpNew
        return ((if (reflect) g shr bit else g shr (7 - bit)) and 1) != 0
    }
}

internal class Missile {
    var counter = 0
    var rendering = false
    var renderIdx = 0
    var mode = 0
    var width = 1
    var enabled = false
    var resmp = false
    var hmClocks = 8
    var moving = false

    fun tick() {
        if (DECODES[mode][counter]) {
            rendering = true
            renderIdx = -4
        } else if (rendering) {
            if (++renderIdx >= width) rendering = false
        }
        if (++counter == 160) counter = 0
    }

    fun movementTick(clock: Int, hblank: Boolean) {
        if (clock == hmClocks) moving = false
        if (moving && hblank) tick()
    }

    fun pixel() = rendering && renderIdx >= 0 && enabled && !resmp

    /** RESMP keeps the missile glued to the middle of its player. */
    fun lockTo(p: Player) {
        val shift = PLAYER_SHIFT[p.mode]
        val delta = 1 + (if (shift > 0) 1 else 0) + ((4 shl shift) - 1)
        counter = (p.counter - delta + 160) % 160
    }
}

internal class Ball {
    var counter = 0
    var rendering = false
    var renderIdx = 0
    var width = 1
    var enaNew = false
    var enaOld = false
    var vdel = false
    var hmClocks = 8
    var moving = false

    fun tick() {
        if (counter == 156) {
            rendering = true
            renderIdx = -4
        } else if (rendering) {
            if (++renderIdx >= width) rendering = false
        }
        if (++counter == 160) counter = 0
    }

    fun movementTick(clock: Int, hblank: Boolean) {
        if (clock == hmClocks) moving = false
        if (moving && hblank) tick()
    }

    /** Unlike players and missiles, the ball starts drawing on the same line it is reset. */
    fun reset(value: Int) {
        counter = value
        rendering = true
        renderIdx = -4 + (value - RESX_FRAME)
    }

    fun pixel() = rendering && renderIdx >= 0 && (if (vdel) enaOld else enaNew)
}

/** One TIA sound channel (polynomial counters as in Stella 6's AudioChannel). */
internal class AudioChannel {
    var audc = 0
    var audf = 0
    var audv = 0
    private var clockEnable = false
    private var noiseFeedback = false
    private var noiseCounterBit4 = false
    private var pulseCounterHold = false
    private var divCounter = 0
    private var pulseCounter = 0
    private var noiseCounter = 0

    fun phase0() {
        if (clockEnable) {
            noiseCounterBit4 = (noiseCounter and 0x01) != 0
            pulseCounterHold = when (audc and 0x03) {
                0, 1 -> false
                2 -> (noiseCounter and 0x1E) != 0x02
                else -> !noiseCounterBit4
            }
            noiseFeedback = if ((audc and 0x03) == 0) {
                ((pulseCounter xor noiseCounter) and 0x01) != 0 ||
                    !(noiseCounter != 0 || pulseCounter != 0x0A) ||
                    (audc and 0x0C) == 0
            } else {
                (((noiseCounter and 0x04) != 0) xor ((noiseCounter and 0x01) != 0)) || noiseCounter == 0
            }
        }
        clockEnable = divCounter == audf
        if (divCounter == audf || divCounter == 0x1F) divCounter = 0 else divCounter++
    }

    fun phase1(): Int {
        if (clockEnable) {
            val pulseFeedback = when (audc shr 2) {
                0 -> (((pulseCounter and 0x02) != 0) xor ((pulseCounter and 0x01) != 0)) &&
                    pulseCounter != 0x0A && (audc and 0x03) != 0
                1 -> (pulseCounter and 0x08) == 0
                2 -> !noiseCounterBit4
                else -> !((pulseCounter and 0x02) != 0 || (pulseCounter and 0x0E) == 0)
            }
            noiseCounter = noiseCounter shr 1
            if (noiseFeedback) noiseCounter = noiseCounter or 0x10
            if (!pulseCounterHold) {
                pulseCounter = (pulseCounter shr 1).inv() and 0x07
                if (pulseFeedback) pulseCounter = pulseCounter or 0x08
            }
        }
        return (pulseCounter and 0x01) * audv
    }
}

class Tia {
    companion object {
        const val WIDTH = 160
        const val MAX_LINES = 320
        /** A frame is forced if the game never strobes VSYNC (e.g. while booting). */
        private const val FORCED_FRAME_LINES = 400
        const val AUDIO_CAPACITY = 2048
    }

    /** ARGB pixels indexed by (scanline since VSYNC) * 160 + x. */
    val frameBuffer = IntArray(WIDTH * MAX_LINES)
    var frameReady = false
    var frameCount = 0L
        private set
    /** Scanline count of the last finished frame. */
    var lastFrameLines = 262
        private set
    /** First scanline with VBLANK off in the last finished frame, or -1. */
    var lastFirstVisible = -1
        private set
    private var firstVisible = -1

    /** Raw mixed audio (0..30 per sample), two samples per scanline. */
    val audio = ShortArray(AUDIO_CAPACITY)
    var audioCount = 0

    var hclock = 0
        private set
    var line = 0
        private set
    /** CPU is halted (RDY low) until the start of the next scanline. */
    var wsync = false
        private set

    // Inputs
    var fire0 = false
    private var latchInputs = false
    private var fire0Latched = false

    private var hblank = true
    private var extHblank = false
    private var vsync = false
    private var vblank = false

    private var colP0 = BLACK
    private var colP1 = BLACK
    private var colPF = BLACK
    private var colBK = BLACK
    private var ctrlpf = 0
    private var pf0 = 0
    private var pf1 = 0
    private var pf2 = 0
    private var pfMask = 0
    private var pfBit = false

    private val p0 = Player()
    private val p1 = Player()
    private val m0 = Missile()
    private val m1 = Missile()
    private val bl = Ball()

    private var movementInProgress = false
    private var movementClock = 0
    private var collisions = 0

    private val a0 = AudioChannel()
    private val a1 = AudioChannel()

    // Some register writes reach the hardware a few color clocks late.
    private val dqReg = IntArray(32)
    private val dqVal = IntArray(32)
    private val dqTime = IntArray(32)
    private var dqCount = 0

    // ---- CPU interface ---------------------------------------------------------------------

    /** Returns bits 7..6 of a TIA read; the caller fills the undriven bits from the data bus. */
    fun read(addr: Int): Int = when (val r = addr and 0x0F) {
        in 0..5 -> (((collisions shr (2 * r)) and 1) shl 7) or (((collisions shr (2 * r + 1)) and 1) shl 6)
        6 -> ((collisions shr 12) and 1) shl 7
        7 -> (((collisions shr 13) and 1) shl 7) or (((collisions shr 14) and 1) shl 6)
        0x0C -> {
            if (latchInputs) {
                if (fire0) fire0Latched = true
                if (fire0Latched) 0 else 0x80
            } else if (fire0) 0 else 0x80
        }
        0x0D -> 0x80
        else -> 0
    }

    /** Debugging aid: called for every register write (line, color clock, register, value). */
    var writeHook: ((Int, Int, Int, Int) -> Unit)? = null

    fun write(addr: Int, v: Int) {
        writeHook?.invoke(line, hclock, addr and 0x3F, v)
        when (val r = addr and 0x3F) {
            0x00 -> {
                val on = (v and 0x02) != 0
                if (on && !vsync) finishFrame()
                vsync = on
            }
            0x01 -> queue(r, v, 1)
            // Landing exactly on the line boundary (cycle 76) halts for zero clocks, as in Stella.
            0x02 -> if (hclock != 0) wsync = true
            0x04 -> { p0.mode = v and 7; m0.mode = v and 7; m0.width = 1 shl ((v shr 4) and 3) }
            0x05 -> { p1.mode = v and 7; m1.mode = v and 7; m1.width = 1 shl ((v shr 4) and 3) }
            0x06 -> colP0 = Palette.NTSC[(v shr 1) and 0x7F]
            0x07 -> colP1 = Palette.NTSC[(v shr 1) and 0x7F]
            0x08 -> colPF = Palette.NTSC[(v shr 1) and 0x7F]
            0x09 -> colBK = Palette.NTSC[(v shr 1) and 0x7F]
            0x0A -> { ctrlpf = v; bl.width = 1 shl ((v shr 4) and 3) }
            0x0B, 0x0C -> queue(r, v, 1)
            0x0D, 0x0E, 0x0F -> queue(r, v, 2)
            0x10 -> p0.counter = resxCounter()
            0x11 -> p1.counter = resxCounter()
            0x12 -> m0.counter = resxCounter()
            0x13 -> m1.counter = resxCounter()
            0x14 -> bl.reset(resxCounter())
            0x15 -> a0.audc = v and 0x0F
            0x16 -> a1.audc = v and 0x0F
            0x17 -> a0.audf = v and 0x1F
            0x18 -> a1.audf = v and 0x1F
            0x19 -> a0.audv = v and 0x0F
            0x1A -> a1.audv = v and 0x0F
            0x1B, 0x1C, 0x1D, 0x1E, 0x1F -> queue(r, v, 1)
            0x20, 0x21, 0x22, 0x23, 0x24 -> queue(r, v, 2)
            0x25 -> p0.vdel = (v and 1) != 0
            0x26 -> p1.vdel = (v and 1) != 0
            0x27 -> bl.vdel = (v and 1) != 0
            0x28 -> { m0.resmp = (v and 2) != 0; if (m0.resmp) m0.lockTo(p0) }
            0x29 -> { m1.resmp = (v and 2) != 0; if (m1.resmp) m1.lockTo(p1) }
            0x2A -> queue(r, v, 6)
            0x2B -> queue(r, v, 2)
            0x2C -> collisions = 0
        }
    }

    private fun resxCounter() = if (hblank) RESX_HBLANK else RESX_FRAME

    private fun queue(reg: Int, value: Int, delay: Int) {
        if (dqCount == dqReg.size) runDelayQueueNow()
        dqReg[dqCount] = reg
        dqVal[dqCount] = value
        dqTime[dqCount] = delay + 1
        dqCount++
    }

    private fun runDelayQueueNow() {
        for (k in 0 until dqCount) applyDelayed(dqReg[k], dqVal[k])
        dqCount = 0
    }

    private fun runDelayQueue() {
        var w = 0
        for (k in 0 until dqCount) {
            val t = dqTime[k] - 1
            if (t == 0) {
                applyDelayed(dqReg[k], dqVal[k])
            } else {
                dqReg[w] = dqReg[k]; dqVal[w] = dqVal[k]; dqTime[w] = t
                w++
            }
        }
        dqCount = w
    }

    private fun applyDelayed(r: Int, v: Int) {
        when (r) {
            0x01 -> {
                vblank = (v and 0x02) != 0
                val latch = (v and 0x40) != 0
                if (latch && !latchInputs) fire0Latched = false
                latchInputs = latch
            }
            0x0B -> p0.reflect = (v and 0x08) != 0
            0x0C -> p1.reflect = (v and 0x08) != 0
            0x0D -> { pf0 = v; updatePlayfield() }
            0x0E -> { pf1 = v; updatePlayfield() }
            0x0F -> { pf2 = v; updatePlayfield() }
            0x1B -> { p0.grpNew = v; p1.grpOld = p1.grpNew }
            0x1C -> { p1.grpNew = v; p0.grpOld = p0.grpNew; bl.enaOld = bl.enaNew }
            0x1D -> m0.enabled = (v and 0x02) != 0
            0x1E -> m1.enabled = (v and 0x02) != 0
            0x1F -> bl.enaNew = (v and 0x02) != 0
            0x20 -> p0.hmClocks = hm(v)
            0x21 -> p1.hmClocks = hm(v)
            0x22 -> m0.hmClocks = hm(v)
            0x23 -> m1.hmClocks = hm(v)
            0x24 -> bl.hmClocks = hm(v)
            0x2A -> {
                movementClock = 0
                movementInProgress = true
                p0.moving = true; p1.moving = true; m0.moving = true; m1.moving = true; bl.moving = true
                extHblank = true
            }
            0x2B -> {
                val c = hm(0)
                p0.hmClocks = c; p1.hmClocks = c; m0.hmClocks = c; m1.hmClocks = c; bl.hmClocks = c
            }
        }
    }

    /** Number of extra clocks an object receives during HMOVE for a given HMxx value. */
    private fun hm(v: Int) = ((v shr 4) and 0x0F) xor 0x08

    private fun updatePlayfield() {
        pfMask = ((pf0 shr 4) and 0x0F) or (REVERSE[pf1 and 0xFF] shl 4) or ((pf2 and 0xFF) shl 12)
    }

    private fun latchPlayfield(x: Int) {
        val i = x shr 2
        val bit = when {
            i < 20 -> i
            (ctrlpf and 0x01) != 0 -> 39 - i
            else -> i - 20
        }
        pfBit = ((pfMask shr bit) and 1) != 0
    }

    // ---- timing --------------------------------------------------------------------------------

    /** Advance one color clock. */
    fun clock() {
        if (dqCount != 0) runDelayQueue()
        if (movementInProgress && (hclock and 3) == 0) tickMovement()

        if (hblank) {
            when (hclock) {
                0 -> extHblank = false
                H_BLANK - 1 -> if (!extHblank) hblank = false
                H_BLANK + 7 -> if (extHblank) hblank = false
            }
            if (hclock >= H_BLANK) {
                // Inside the HMOVE "comb": the playfield keeps latching but the beam is blanked.
                val x = hclock - H_BLANK
                if ((x and 3) == 0) latchPlayfield(x)
                if (line < MAX_LINES) frameBuffer[line * WIDTH + x] = BLACK
            }
        } else {
            val x = hclock - H_BLANK
            if ((x and 3) == 0) latchPlayfield(x)
            p0.tick(); p1.tick(); m0.tick(); m1.tick(); bl.tick()
            renderPixel(x)
        }
        if (m0.resmp) m0.lockTo(p0)
        if (m1.resmp) m1.lockTo(p1)

        when (hclock) {
            9, 81 -> { a0.phase0(); a1.phase0() }
            37, 149 -> {
                val s = a0.phase1() + a1.phase1()
                if (audioCount < AUDIO_CAPACITY) audio[audioCount++] = s.toShort()
            }
        }

        if (++hclock == H_CLOCKS) {
            hclock = 0
            hblank = true
            wsync = false
            if (++line >= FORCED_FRAME_LINES) finishFrame()
        }
    }

    private fun tickMovement() {
        val clock = movementClock
        val hb = hblank
        p0.movementTick(clock, hb)
        p1.movementTick(clock, hb)
        m0.movementTick(clock, hb)
        m1.movementTick(clock, hb)
        bl.movementTick(clock, hb)
        movementInProgress = p0.moving || p1.moving || m0.moving || m1.moving || bl.moving
        movementClock++
    }

    private fun renderPixel(x: Int) {
        if (line >= MAX_LINES) return
        val idx = line * WIDTH + x
        if (vblank) {
            frameBuffer[idx] = BLACK
            return
        }
        if (firstVisible < 0) firstVisible = line

        val pf = pfBit
        val ball = this.bl.pixel()
        val pl0 = this.p0.pixel()
        val pl1 = this.p1.pixel()
        val ms0 = this.m0.pixel()
        val ms1 = this.m1.pixel()
        val mask = (if (pl0) O_P0 else 0) or (if (pl1) O_P1 else 0) or (if (ms0) O_M0 else 0) or
            (if (ms1) O_M1 else 0) or (if (ball) O_BL else 0) or (if (pf) O_PF else 0)
        if (mask == 0) {
            frameBuffer[idx] = colBK
            return
        }
        collisions = collisions or COLLISIONS[mask]

        frameBuffer[idx] = if ((ctrlpf and 0x04) != 0) {
            // Playfield priority: PF/BL in front of the players.
            when {
                pf || ball -> colPF
                pl0 || ms0 -> colP0
                else -> colP1
            }
        } else if ((ctrlpf and 0x02) != 0) {
            // Score mode: each half of the playfield takes its player's color (and priority).
            val left = x < 80
            when {
                pl0 || ms0 -> colP0
                pf && left -> colP0
                pl1 || ms1 -> colP1
                pf -> colP1
                else -> colPF
            }
        } else {
            when {
                pl0 || ms0 -> colP0
                pl1 || ms1 -> colP1
                else -> colPF
            }
        }
    }

    private fun finishFrame() {
        frameReady = true
        frameCount++
        lastFrameLines = line
        lastFirstVisible = firstVisible
        firstVisible = -1
        line = 0
    }
}
