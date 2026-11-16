package io.github.gativrek.retro2600.emu

/**
 * 6532 RIOT minus its RAM (which lives on the bus): the joystick port (SWCHA), the console
 * switches (SWCHB) and the interval timer.
 *
 * The timer is evaluated lazily from the global CPU cycle count instead of being clocked.
 */
class Riot {
    /** Joystick lines, active low. P0: bit 7 right, 6 left, 5 down, 4 up. */
    var swchaIn = 0xFF
    /** Console switches, active low for reset (bit 0) and select (bit 1); bit 3 = color. */
    var swchbIn = 0x0B

    private var swchaOut = 0
    private var swacnt = 0
    private var swchbOut = 0
    private var swbcnt = 0

    private var timerBase = 0L
    private var timerSetAt = 0L
    private var shift = 10
    private var flagCleared = false

    init {
        timerBase = 0x80L shl shift
    }

    fun save(w: StateWriter) {
        w.int(swchaOut); w.int(swacnt); w.int(swchbOut); w.int(swbcnt)
        w.long(timerBase); w.long(timerSetAt); w.int(shift); w.bool(flagCleared)
    }

    fun load(r: StateReader) {
        swchaOut = r.int(); swacnt = r.int(); swchbOut = r.int(); swbcnt = r.int()
        timerBase = r.long(); timerSetAt = r.long(); shift = r.int(); flagCleared = r.bool()
    }

    private fun timer(cycles: Long) = timerBase - (cycles - timerSetAt) - 1

    fun read(addr: Int, cycles: Long): Int {
        if ((addr and 0x04) == 0) {
            return when (addr and 0x03) {
                0 -> ((swchaIn and swacnt.inv()) or (swchaOut and swacnt)) and 0xFF
                1 -> swacnt
                2 -> ((swchbIn and swbcnt.inv()) or (swchbOut and swbcnt)) and 0xFF
                else -> swbcnt
            }
        }
        val t = timer(cycles)
        if ((addr and 0x01) == 0) {
            // INTIM. Once expired the timer keeps counting down once per cycle.
            if (t < 0) {
                flagCleared = true
                return (t and 0xFF).toInt()
            }
            return ((t shr shift) and 0xFF).toInt()
        }
        // TIMINT: bit 7 = timer expired.
        return if (t < 0 && !flagCleared) 0x80 else 0
    }

    fun write(addr: Int, value: Int, cycles: Long) {
        if ((addr and 0x04) == 0) {
            when (addr and 0x03) {
                0 -> swchaOut = value
                1 -> swacnt = value
                2 -> swchbOut = value
                else -> swbcnt = value
            }
        } else if ((addr and 0x10) != 0) {
            shift = when (addr and 0x03) { 0 -> 0; 1 -> 3; 2 -> 6; else -> 10 }
            timerBase = value.toLong() shl shift
            timerSetAt = cycles
            flagCleared = false
        }
        // Writes with A4 = 0 configure PA7 edge detection, which no supported game needs.
    }
}
