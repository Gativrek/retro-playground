package io.github.gativrek.retro2600.emu

/** Anything the CPU can talk to. Each call is exactly one CPU cycle. */
interface Bus {
    fun read(addr: Int): Int
    fun write(addr: Int, value: Int)
}

/**
 * NMOS 6502 core (the 2600's 6507 is the same die with fewer address pins).
 *
 * Every bus access — including the dummy reads/writes real silicon performs — costs one cycle,
 * so instruction timings come out exact and writes to the TIA land on the right color clock.
 * Includes the stable undocumented opcodes some 2600 games rely on.
 */
class Cpu6502(private val bus: Bus) {
    var a = 0; var x = 0; var y = 0
    var sp = 0xFD
    var pc = 0
    private var n = false; private var v = false; private var d = false
    private var i = true; private var z = false; private var c = false

    /** Set when a KIL/JAM opcode locks up the processor. */
    var jammed = false
        private set

    var status: Int
        get() = (if (n) 0x80 else 0) or (if (v) 0x40 else 0) or 0x20 or (if (d) 0x08 else 0) or
            (if (i) 0x04 else 0) or (if (z) 0x02 else 0) or (if (c) 0x01 else 0)
        set(p) {
            n = (p and 0x80) != 0; v = (p and 0x40) != 0; d = (p and 0x08) != 0
            i = (p and 0x04) != 0; z = (p and 0x02) != 0; c = (p and 0x01) != 0
        }

    fun reset() {
        jammed = false
        sp = 0xFD; i = true; d = false
        pc = bus.read(0xFFFC) or (bus.read(0xFFFD) shl 8)
    }

    // ---- bus helpers -------------------------------------------------------------------------

    private fun read(addr: Int) = bus.read(addr)
    private fun write(addr: Int, value: Int) = bus.write(addr, value)

    private fun fetch(): Int {
        val b = bus.read(pc)
        pc = (pc + 1) and 0xFFFF
        return b
    }

    /** Implied/accumulator instructions still read the next byte (and throw it away). */
    private fun implied() { bus.read(pc) }

    private fun push(value: Int) {
        write(0x100 or sp, value)
        sp = (sp - 1) and 0xFF
    }

    private fun pull(): Int {
        sp = (sp + 1) and 0xFF
        return read(0x100 or sp)
    }

    // ---- addressing modes (return the effective address after spending the right cycles) ----

    private fun zp() = fetch()
    private fun zpx(): Int { val b = fetch(); read(b); return (b + x) and 0xFF }
    private fun zpy(): Int { val b = fetch(); read(b); return (b + y) and 0xFF }
    private fun abs(): Int { val lo = fetch(); return lo or (fetch() shl 8) }

    /** abs,X / abs,Y for reads: the extra cycle only happens when a page is crossed. */
    private fun absR(index: Int): Int {
        val base = abs()
        val ea = (base + index) and 0xFFFF
        if (((base xor ea) and 0xFF00) != 0) read((base and 0xFF00) or (ea and 0xFF))
        return ea
    }

    /** abs,X / abs,Y for writes and read-modify-writes: the dummy read always happens. */
    private fun absW(index: Int): Int {
        val base = abs()
        val ea = (base + index) and 0xFFFF
        read((base and 0xFF00) or (ea and 0xFF))
        return ea
    }

    private fun izx(): Int {
        val b = fetch()
        read(b)
        val p = (b + x) and 0xFF
        val lo = read(p)
        return lo or (read((p + 1) and 0xFF) shl 8)
    }

    private fun izyBase(): Int {
        val b = fetch()
        val lo = read(b)
        return lo or (read((b + 1) and 0xFF) shl 8)
    }

    private fun izyR(): Int {
        val base = izyBase()
        val ea = (base + y) and 0xFFFF
        if (((base xor ea) and 0xFF00) != 0) read((base and 0xFF00) or (ea and 0xFF))
        return ea
    }

    private fun izyW(): Int {
        val base = izyBase()
        val ea = (base + y) and 0xFFFF
        read((base and 0xFF00) or (ea and 0xFF))
        return ea
    }

    /** Read-modify-write: read, write the old value back (a real double write), write the result. */
    private inline fun rmw(ea: Int, op: (Int) -> Int) {
        val old = read(ea)
        write(ea, old)
        write(ea, op(old) and 0xFF)
    }

    // ---- ALU -----------------------------------------------------------------------------------

    private fun nz(value: Int) {
        n = (value and 0x80) != 0
        z = (value and 0xFF) == 0
    }

    private fun lda(m: Int) { a = m; nz(a) }
    private fun ldx(m: Int) { x = m; nz(x) }
    private fun ldy(m: Int) { y = m; nz(y) }
    private fun ora(m: Int) { a = a or m; nz(a) }
    private fun and(m: Int) { a = a and m; nz(a) }
    private fun eor(m: Int) { a = a xor m; nz(a) }

    private fun cmp(reg: Int, m: Int) {
        c = reg >= m
        nz((reg - m) and 0xFF)
    }

    private fun bit(m: Int) {
        z = (a and m) == 0
        n = (m and 0x80) != 0
        v = (m and 0x40) != 0
    }

    private fun adc(m: Int) {
        val cin = if (c) 1 else 0
        if (!d) {
            val s = a + m + cin
            v = ((a xor s) and (m xor s) and 0x80) != 0
            c = s > 0xFF
            a = s and 0xFF
            nz(a)
            return
        }
        // NMOS decimal mode (Bruce Clark's "Decimal Mode" appendix): Z from the binary sum,
        // N and V from the intermediate result, C and A from the fully adjusted one.
        var lo = (a and 0x0F) + (m and 0x0F) + cin
        if (lo >= 0x0A) lo = ((lo + 0x06) and 0x0F) + 0x10
        var s = (a and 0xF0) + (m and 0xF0) + lo
        z = ((a + m + cin) and 0xFF) == 0
        n = (s and 0x80) != 0
        v = ((a xor s) and (m xor s) and 0x80) != 0
        if (s >= 0xA0) s += 0x60
        c = s >= 0x100
        a = s and 0xFF
    }

    private fun sbc(m: Int) {
        val cin = if (c) 1 else 0
        val bin = a - m - (1 - cin)
        v = ((a xor m) and (a xor bin) and 0x80) != 0
        c = bin >= 0
        nz(bin and 0xFF)
        if (!d) {
            a = bin and 0xFF
            return
        }
        // Flags come from the binary result on the NMOS part; only A gets the BCD adjust.
        var lo = (a and 0x0F) - (m and 0x0F) + cin - 1
        if (lo < 0) lo = ((lo - 0x06) and 0x0F) - 0x10
        var s = (a and 0xF0) - (m and 0xF0) + lo
        if (s < 0) s -= 0x60
        a = s and 0xFF
    }

    private fun asl(m: Int): Int { c = (m and 0x80) != 0; val r = (m shl 1) and 0xFF; nz(r); return r }
    private fun lsr(m: Int): Int { c = (m and 0x01) != 0; val r = m shr 1; nz(r); return r }
    private fun rol(m: Int): Int {
        val r = ((m shl 1) or (if (c) 1 else 0)) and 0xFF
        c = (m and 0x80) != 0; nz(r); return r
    }
    private fun ror(m: Int): Int {
        val r = (m shr 1) or (if (c) 0x80 else 0)
        c = (m and 0x01) != 0; nz(r); return r
    }
    private fun inc(m: Int): Int { val r = (m + 1) and 0xFF; nz(r); return r }
    private fun dec(m: Int): Int { val r = (m - 1) and 0xFF; nz(r); return r }

    // Undocumented read-modify-write combos.
    private fun slo(m: Int): Int { val r = asl(m); ora(r); return r }
    private fun rla(m: Int): Int { val r = rol(m); and(r); return r }
    private fun sre(m: Int): Int { val r = lsr(m); eor(r); return r }
    private fun rra(m: Int): Int { val r = ror(m); adc(r); return r }
    private fun dcp(m: Int): Int { val r = (m - 1) and 0xFF; cmp(a, r); return r }
    private fun isb(m: Int): Int { val r = (m + 1) and 0xFF; sbc(r); return r }
    private fun lax(m: Int) { a = m; x = m; nz(m) }

    private fun branch(taken: Boolean) {
        val offset = fetch().toByte().toInt()
        if (!taken) return
        read(pc)
        val target = (pc + offset) and 0xFFFF
        if (((target xor pc) and 0xFF00) != 0) read((pc and 0xFF00) or (target and 0xFF))
        pc = target
    }

    /** SHA/SHX/SHY/TAS store `value AND (high byte of base + 1)`. */
    private fun storeHigh(ea: Int, value: Int) {
        write(ea, value and (((ea shr 8) + 1) and 0xFF))
    }

    // ---- execution -----------------------------------------------------------------------------

    fun step() {
        if (jammed) {
            read(0xFFFF)
            return
        }
        when (fetch()) {
            // Loads
            0xA9 -> lda(fetch()); 0xA5 -> lda(read(zp())); 0xB5 -> lda(read(zpx())); 0xAD -> lda(read(abs()))
            0xBD -> lda(read(absR(x))); 0xB9 -> lda(read(absR(y))); 0xA1 -> lda(read(izx())); 0xB1 -> lda(read(izyR()))
            0xA2 -> ldx(fetch()); 0xA6 -> ldx(read(zp())); 0xB6 -> ldx(read(zpy())); 0xAE -> ldx(read(abs()))
            0xBE -> ldx(read(absR(y)))
            0xA0 -> ldy(fetch()); 0xA4 -> ldy(read(zp())); 0xB4 -> ldy(read(zpx())); 0xAC -> ldy(read(abs()))
            0xBC -> ldy(read(absR(x)))

            // Stores
            0x85 -> write(zp(), a); 0x95 -> write(zpx(), a); 0x8D -> write(abs(), a); 0x9D -> write(absW(x), a)
            0x99 -> write(absW(y), a); 0x81 -> write(izx(), a); 0x91 -> write(izyW(), a)
            0x86 -> write(zp(), x); 0x96 -> write(zpy(), x); 0x8E -> write(abs(), x)
            0x84 -> write(zp(), y); 0x94 -> write(zpx(), y); 0x8C -> write(abs(), y)

            // Register transfers
            0xAA -> { implied(); x = a; nz(x) }
            0xA8 -> { implied(); y = a; nz(y) }
            0x8A -> { implied(); a = x; nz(a) }
            0x98 -> { implied(); a = y; nz(a) }
            0xBA -> { implied(); x = sp; nz(x) }
            0x9A -> { implied(); sp = x }

            // Stack
            0x48 -> { implied(); push(a) }
            0x08 -> { implied(); push(status or 0x10) }
            0x68 -> { implied(); read(0x100 or sp); a = pull(); nz(a) }
            0x28 -> { implied(); read(0x100 or sp); status = pull() }

            // Logic / arithmetic
            0x09 -> ora(fetch()); 0x05 -> ora(read(zp())); 0x15 -> ora(read(zpx())); 0x0D -> ora(read(abs()))
            0x1D -> ora(read(absR(x))); 0x19 -> ora(read(absR(y))); 0x01 -> ora(read(izx())); 0x11 -> ora(read(izyR()))
            0x29 -> and(fetch()); 0x25 -> and(read(zp())); 0x35 -> and(read(zpx())); 0x2D -> and(read(abs()))
            0x3D -> and(read(absR(x))); 0x39 -> and(read(absR(y))); 0x21 -> and(read(izx())); 0x31 -> and(read(izyR()))
            0x49 -> eor(fetch()); 0x45 -> eor(read(zp())); 0x55 -> eor(read(zpx())); 0x4D -> eor(read(abs()))
            0x5D -> eor(read(absR(x))); 0x59 -> eor(read(absR(y))); 0x41 -> eor(read(izx())); 0x51 -> eor(read(izyR()))
            0x69 -> adc(fetch()); 0x65 -> adc(read(zp())); 0x75 -> adc(read(zpx())); 0x6D -> adc(read(abs()))
            0x7D -> adc(read(absR(x))); 0x79 -> adc(read(absR(y))); 0x61 -> adc(read(izx())); 0x71 -> adc(read(izyR()))
            0xE9, 0xEB -> sbc(fetch()); 0xE5 -> sbc(read(zp())); 0xF5 -> sbc(read(zpx())); 0xED -> sbc(read(abs()))
            0xFD -> sbc(read(absR(x))); 0xF9 -> sbc(read(absR(y))); 0xE1 -> sbc(read(izx())); 0xF1 -> sbc(read(izyR()))
            0xC9 -> cmp(a, fetch()); 0xC5 -> cmp(a, read(zp())); 0xD5 -> cmp(a, read(zpx())); 0xCD -> cmp(a, read(abs()))
            0xDD -> cmp(a, read(absR(x))); 0xD9 -> cmp(a, read(absR(y))); 0xC1 -> cmp(a, read(izx()))
            0xD1 -> cmp(a, read(izyR()))
            0xE0 -> cmp(x, fetch()); 0xE4 -> cmp(x, read(zp())); 0xEC -> cmp(x, read(abs()))
            0xC0 -> cmp(y, fetch()); 0xC4 -> cmp(y, read(zp())); 0xCC -> cmp(y, read(abs()))
            0x24 -> bit(read(zp())); 0x2C -> bit(read(abs()))

            // Increments / decrements
            0xE8 -> { implied(); x = (x + 1) and 0xFF; nz(x) }
            0xC8 -> { implied(); y = (y + 1) and 0xFF; nz(y) }
            0xCA -> { implied(); x = (x - 1) and 0xFF; nz(x) }
            0x88 -> { implied(); y = (y - 1) and 0xFF; nz(y) }
            0xE6 -> rmw(zp()) { inc(it) }; 0xF6 -> rmw(zpx()) { inc(it) }
            0xEE -> rmw(abs()) { inc(it) }; 0xFE -> rmw(absW(x)) { inc(it) }
            0xC6 -> rmw(zp()) { dec(it) }; 0xD6 -> rmw(zpx()) { dec(it) }
            0xCE -> rmw(abs()) { dec(it) }; 0xDE -> rmw(absW(x)) { dec(it) }

            // Shifts
            0x0A -> { implied(); a = asl(a) }; 0x06 -> rmw(zp()) { asl(it) }; 0x16 -> rmw(zpx()) { asl(it) }
            0x0E -> rmw(abs()) { asl(it) }; 0x1E -> rmw(absW(x)) { asl(it) }
            0x4A -> { implied(); a = lsr(a) }; 0x46 -> rmw(zp()) { lsr(it) }; 0x56 -> rmw(zpx()) { lsr(it) }
            0x4E -> rmw(abs()) { lsr(it) }; 0x5E -> rmw(absW(x)) { lsr(it) }
            0x2A -> { implied(); a = rol(a) }; 0x26 -> rmw(zp()) { rol(it) }; 0x36 -> rmw(zpx()) { rol(it) }
            0x2E -> rmw(abs()) { rol(it) }; 0x3E -> rmw(absW(x)) { rol(it) }
            0x6A -> { implied(); a = ror(a) }; 0x66 -> rmw(zp()) { ror(it) }; 0x76 -> rmw(zpx()) { ror(it) }
            0x6E -> rmw(abs()) { ror(it) }; 0x7E -> rmw(absW(x)) { ror(it) }

            // Flags
            0x18 -> { implied(); c = false }; 0x38 -> { implied(); c = true }
            0x58 -> { implied(); i = false }; 0x78 -> { implied(); i = true }
            0xD8 -> { implied(); d = false }; 0xF8 -> { implied(); d = true }
            0xB8 -> { implied(); v = false }

            // Branches
            0x10 -> branch(!n); 0x30 -> branch(n); 0x50 -> branch(!v); 0x70 -> branch(v)
            0x90 -> branch(!c); 0xB0 -> branch(c); 0xD0 -> branch(!z); 0xF0 -> branch(z)

            // Jumps and subroutines
            0x4C -> pc = abs()
            0x6C -> {
                val ptr = abs()
                val lo = read(ptr)
                // NMOS bug: the pointer's high byte never crosses a page.
                pc = lo or (read((ptr and 0xFF00) or ((ptr + 1) and 0xFF)) shl 8)
            }
            0x20 -> {
                val lo = fetch()
                read(0x100 or sp)
                push(pc shr 8)
                push(pc and 0xFF)
                pc = lo or (read(pc) shl 8)
            }
            0x60 -> {
                implied()
                read(0x100 or sp)
                val lo = pull()
                pc = lo or (pull() shl 8)
                read(pc)
                pc = (pc + 1) and 0xFFFF
            }
            0x40 -> {
                implied()
                read(0x100 or sp)
                status = pull()
                val lo = pull()
                pc = lo or (pull() shl 8)
            }
            0x00 -> {
                fetch()
                push(pc shr 8)
                push(pc and 0xFF)
                push(status or 0x10)
                i = true
                val lo = read(0xFFFE)
                pc = lo or (read(0xFFFF) shl 8)
            }

            // NOPs, documented and not
            0xEA, 0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA -> implied()
            0x80, 0x82, 0x89, 0xC2, 0xE2 -> fetch()
            0x04, 0x44, 0x64 -> read(zp())
            0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> read(zpx())
            0x0C -> read(abs())
            0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC -> read(absR(x))

            // Undocumented combos
            0xA7 -> lax(read(zp())); 0xB7 -> lax(read(zpy())); 0xAF -> lax(read(abs())); 0xBF -> lax(read(absR(y)))
            0xA3 -> lax(read(izx())); 0xB3 -> lax(read(izyR())); 0xAB -> lax(fetch())
            0x87 -> write(zp(), a and x); 0x97 -> write(zpy(), a and x); 0x8F -> write(abs(), a and x)
            0x83 -> write(izx(), a and x)
            0x07 -> rmw(zp()) { slo(it) }; 0x17 -> rmw(zpx()) { slo(it) }; 0x0F -> rmw(abs()) { slo(it) }
            0x1F -> rmw(absW(x)) { slo(it) }; 0x1B -> rmw(absW(y)) { slo(it) }; 0x03 -> rmw(izx()) { slo(it) }
            0x13 -> rmw(izyW()) { slo(it) }
            0x27 -> rmw(zp()) { rla(it) }; 0x37 -> rmw(zpx()) { rla(it) }; 0x2F -> rmw(abs()) { rla(it) }
            0x3F -> rmw(absW(x)) { rla(it) }; 0x3B -> rmw(absW(y)) { rla(it) }; 0x23 -> rmw(izx()) { rla(it) }
            0x33 -> rmw(izyW()) { rla(it) }
            0x47 -> rmw(zp()) { sre(it) }; 0x57 -> rmw(zpx()) { sre(it) }; 0x4F -> rmw(abs()) { sre(it) }
            0x5F -> rmw(absW(x)) { sre(it) }; 0x5B -> rmw(absW(y)) { sre(it) }; 0x43 -> rmw(izx()) { sre(it) }
            0x53 -> rmw(izyW()) { sre(it) }
            0x67 -> rmw(zp()) { rra(it) }; 0x77 -> rmw(zpx()) { rra(it) }; 0x6F -> rmw(abs()) { rra(it) }
            0x7F -> rmw(absW(x)) { rra(it) }; 0x7B -> rmw(absW(y)) { rra(it) }; 0x63 -> rmw(izx()) { rra(it) }
            0x73 -> rmw(izyW()) { rra(it) }
            0xC7 -> rmw(zp()) { dcp(it) }; 0xD7 -> rmw(zpx()) { dcp(it) }; 0xCF -> rmw(abs()) { dcp(it) }
            0xDF -> rmw(absW(x)) { dcp(it) }; 0xDB -> rmw(absW(y)) { dcp(it) }; 0xC3 -> rmw(izx()) { dcp(it) }
            0xD3 -> rmw(izyW()) { dcp(it) }
            0xE7 -> rmw(zp()) { isb(it) }; 0xF7 -> rmw(zpx()) { isb(it) }; 0xEF -> rmw(abs()) { isb(it) }
            0xFF -> rmw(absW(x)) { isb(it) }; 0xFB -> rmw(absW(y)) { isb(it) }; 0xE3 -> rmw(izx()) { isb(it) }
            0xF3 -> rmw(izyW()) { isb(it) }
            0x0B, 0x2B -> { and(fetch()); c = n }
            0x4B -> { and(fetch()); a = lsr(a) }
            0x6B -> {
                and(fetch())
                a = (a shr 1) or (if (c) 0x80 else 0)
                nz(a)
                c = (a and 0x40) != 0
                v = (((a shr 6) xor (a shr 5)) and 1) != 0
            }
            0xCB -> {
                val t = (a and x) - fetch()
                c = t >= 0
                x = t and 0xFF
                nz(x)
            }
            0x8B -> { a = (a or 0xEE) and x and fetch(); nz(a) }
            0xBB -> { val m = read(absR(y)) and sp; a = m; x = m; sp = m; nz(m) }
            0x9C -> storeHigh(absW(x), y)
            0x9E -> storeHigh(absW(y), x)
            0x9F -> storeHigh(absW(y), a and x)
            0x93 -> storeHigh(izyW(), a and x)
            0x9B -> { sp = a and x; storeHigh(absW(y), sp) }

            // KIL / JAM
            else -> {
                jammed = true
                pc = (pc - 1) and 0xFFFF
            }
        }
    }
}
