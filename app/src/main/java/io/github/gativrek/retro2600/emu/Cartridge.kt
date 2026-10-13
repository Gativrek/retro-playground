package io.github.gativrek.retro2600.emu

/**
 * A cartridge image plus its bank-switching logic. Addresses passed in are 13-bit bus addresses;
 * [read]/[write] are only called for the cartridge window (A12 set).
 */
abstract class Cartridge(protected val rom: IntArray) {
    abstract val type: String
    abstract fun read(addr: Int): Int
    open fun write(addr: Int, value: Int) {}
    /** Writes that land in TIA space (used by Tigervision 3F switching). */
    open fun tiaWrite(addr: Int, value: Int) {}

    companion object {
        const val MAX_SIZE = 64 * 1024

        fun load(bytes: ByteArray): Cartridge {
            require(bytes.isNotEmpty()) { "Arquivo vazio" }
            require(bytes.size <= MAX_SIZE) { "Arquivo grande demais para um cartucho de Atari 2600" }
            val rom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
            return when {
                rom.size <= 4096 -> Plain(mirrorTo4K(rom))
                rom.size == 8192 && isE0(rom) -> ParkerE0(rom)
                rom.size % 2048 == 0 && rom.size > 4096 && is3F(rom) -> Tigervision3F(rom)
                rom.size == 8192 -> Standard(rom, "F8", banks = 2, hotspot = 0xFF8)
                rom.size == 16384 -> Standard(rom, "F6", banks = 4, hotspot = 0xFF6)
                rom.size == 32768 -> Standard(rom, "F4", banks = 8, hotspot = 0xFF4)
                else -> throw IllegalArgumentException("Formato de cartucho não suportado (${rom.size} bytes)")
            }
        }

        /** 2K (and smaller) games are simply mirrored across the 4K window. */
        private fun mirrorTo4K(rom: IntArray): IntArray {
            var size = 1
            while (size < rom.size) size = size shl 1
            return IntArray(4096) { rom[it % size % rom.size] }
        }

        private fun contains(rom: IntArray, vararg sig: Int): Int {
            var count = 0
            outer@ for (start in 0..rom.size - sig.size) {
                for (k in sig.indices) if (rom[start + k] != sig[k]) continue@outer
                count++
            }
            return count
        }

        private fun is3F(rom: IntArray) = contains(rom, 0x85, 0x3F) >= 2

        private fun isE0(rom: IntArray): Boolean {
            val sigs = arrayOf(
                intArrayOf(0x8D, 0xE0, 0x1F), intArrayOf(0x8D, 0xE0, 0x5F), intArrayOf(0x8D, 0xE9, 0xFF),
                intArrayOf(0x0C, 0xE0, 0x1F), intArrayOf(0xAD, 0xE0, 0x1F), intArrayOf(0xAD, 0xE9, 0xFF),
                intArrayOf(0xAD, 0xED, 0xFF), intArrayOf(0xAD, 0xF3, 0xBF),
            )
            return sigs.any { contains(rom, *it) > 0 }
        }
    }
}

private class Plain(rom: IntArray) : Cartridge(rom) {
    override val type = "4K"
    override fun read(addr: Int) = rom[addr and 0xFFF]
}

/** Atari F8/F6/F4: touching a hotspot near the top of the window selects a 4K bank. */
private class Standard(rom: IntArray, name: String, private val banks: Int, private val hotspot: Int) :
    Cartridge(rom) {

    /** Superchip: 128 bytes of extra RAM, written at $1000-$107F and read at $1080-$10FF. */
    private val ram: IntArray? = if (hasSuperChip(rom, banks)) IntArray(128) else null
    override val type = name + if (ram != null) "SC" else ""
    private var bankOffset = (banks - 1) shl 12

    private fun checkHotspot(offset: Int) {
        val b = offset - hotspot
        if (b in 0 until banks) bankOffset = b shl 12
    }

    override fun read(addr: Int): Int {
        val offset = addr and 0xFFF
        checkHotspot(offset)
        if (ram != null && offset < 0x100) return ram[offset and 0x7F]
        return rom[bankOffset or offset]
    }

    override fun write(addr: Int, value: Int) {
        val offset = addr and 0xFFF
        checkHotspot(offset)
        if (ram != null && offset < 0x80) ram[offset] = value
    }

    companion object {
        /** Superchip images have the RAM area (first 256 bytes of every bank) filled with one value. */
        fun hasSuperChip(rom: IntArray, banks: Int): Boolean {
            for (b in 0 until banks) {
                val base = b shl 12
                val first = rom[base]
                for (k in 1 until 256) if (rom[base + k] != first) return false
            }
            return true
        }
    }
}

/** Parker Brothers E0: four 1K slices, the last fixed to slice 7. */
private class ParkerE0(rom: IntArray) : Cartridge(rom) {
    override val type = "E0"
    private val slices = intArrayOf(4, 5, 6, 7)

    private fun checkHotspot(offset: Int) {
        if (offset in 0xFE0..0xFF7) slices[(offset - 0xFE0) shr 3] = offset and 0x07
    }

    override fun read(addr: Int): Int {
        val offset = addr and 0xFFF
        checkHotspot(offset)
        return rom[(slices[offset shr 10] shl 10) or (offset and 0x3FF)]
    }

    override fun write(addr: Int, value: Int) = checkHotspot(addr and 0xFFF)
}

/** Tigervision 3F: writing to $00-$3F selects the 2K bank at $1000; the top 2K is fixed. */
private class Tigervision3F(rom: IntArray) : Cartridge(rom) {
    override val type = "3F"
    private val banks = rom.size / 2048
    private var bankOffset = 0

    override fun read(addr: Int): Int {
        val offset = addr and 0xFFF
        return if (offset < 0x800) rom[bankOffset or offset] else rom[rom.size - 2048 + (offset and 0x7FF)]
    }

    override fun tiaWrite(addr: Int, value: Int) {
        if (addr <= 0x3F) bankOffset = (value % banks) * 2048
    }
}
