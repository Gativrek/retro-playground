package io.github.gativrek.retro2600.emu

/** A tiny 6502 assembler, just enough to build test kernels in code. */
class Asm(private val origin: Int = 0xF000) {
    private val bytes = ArrayList<Int>()
    private val labels = HashMap<String, Int>()
    private val fixups = ArrayList<Triple<Int, String, Boolean>>() // position, label, relative?

    val pc get() = origin + bytes.size

    fun label(name: String) { labels[name] = pc }
    fun b(vararg v: Int) { for (x in v) bytes.add(x and 0xFF) }

    fun ldaI(v: Int) = b(0xA9, v)
    fun ldxI(v: Int) = b(0xA2, v)
    fun ldyI(v: Int) = b(0xA0, v)
    fun lda(zp: Int) = b(0xA5, zp)
    fun ldaAbs(addr: Int) = b(0xAD, addr, addr shr 8)
    fun sta(zp: Int) = b(0x85, zp)
    fun staAbs(addr: Int) = b(0x8D, addr, addr shr 8)
    fun staZpX(zp: Int) = b(0x95, zp)
    fun stx(zp: Int) = b(0x86, zp)
    fun nop(count: Int = 1) = repeat(count) { b(0xEA) }
    fun dex() = b(0xCA)
    fun txs() = b(0x9A)
    fun sei() = b(0x78)
    fun cld() = b(0xD8)

    fun andI(v: Int) = b(0x29, v)
    fun bne(target: String) = branch(0xD0, target)
    fun beq(target: String) = branch(0xF0, target)
    fun bmi(target: String) = branch(0x30, target)
    private fun branch(op: Int, target: String) {
        b(op, 0)
        fixups.add(Triple(bytes.size - 1, target, true))
    }

    fun jmp(target: String) {
        b(0x4C, 0, 0)
        fixups.add(Triple(bytes.size - 2, target, false))
    }

    /** A 4K image with the reset vector pointing at [origin]. */
    fun build(): ByteArray {
        for ((pos, name, rel) in fixups) {
            val addr = labels[name] ?: error("label $name")
            if (rel) {
                val off = addr - (origin + pos + 1)
                require(off in -128..127) { "branch to $name out of range" }
                bytes[pos] = off and 0xFF
            } else {
                bytes[pos] = addr and 0xFF
                bytes[pos + 1] = (addr shr 8) and 0xFF
            }
        }
        val image = ByteArray(4096) { 0xEA.toByte() }
        for (k in bytes.indices) image[k] = bytes[k].toByte()
        image[0xFFC] = origin.toByte()
        image[0xFFD] = (origin shr 8).toByte()
        return image
    }
}

// TIA / RIOT register addresses
const val VSYNC = 0x00; const val VBLANK = 0x01; const val WSYNC = 0x02
const val NUSIZ0 = 0x04; const val COLUP0 = 0x06; const val COLUP1 = 0x07; const val COLUPF = 0x08
const val COLUBK = 0x09; const val CTRLPF = 0x0A; const val PF0 = 0x0D; const val PF1 = 0x0E; const val PF2 = 0x0F
const val RESP0 = 0x10; const val RESP1 = 0x11; const val RESM0 = 0x12; const val RESBL = 0x14
const val AUDC0 = 0x15; const val AUDF0 = 0x17; const val AUDV0 = 0x19
const val GRP0 = 0x1B; const val GRP1 = 0x1C; const val ENAM0 = 0x1D; const val ENABL = 0x1F
const val INPT4 = 0x0C
const val HMP0 = 0x20; const val HMP1 = 0x21; const val HMOVE = 0x2A
const val SWCHA = 0x280; const val SWCHB = 0x282; const val INTIM = 0x284; const val TIM64T = 0x296
