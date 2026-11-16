package io.github.gativrek.retro2600.emu

/**
 * Reads the on-screen score of a few known games straight from console RAM, so the app can keep
 * a personal best. Games are identified by the MD5 of their ROM image.
 */
fun interface ScoreReader {
    /** The current score, or a negative number when there's nothing meaningful to read. */
    fun read(console: Atari2600): Int

    companion object {
        private fun bcd(v: Int) = (v shr 4) * 10 + (v and 0x0F)

        /** Seaquest: six BCD digits at $B8-$BA. */
        private val SEAQUEST = ScoreReader { c ->
            bcd(c.peekRam(0x38)) * 10000 + bcd(c.peekRam(0x39)) * 100 + bcd(c.peekRam(0x3A))
        }

        /**
         * River Raid keeps the score as six pointers into its digit graphics ($CD, $CF ... $D7),
         * eight bytes per digit; anything else is a blank leading digit. Real scores always end
         * in 0, which also filters out the attract mode's "1".
         */
        private val RIVER_RAID = ScoreReader { c ->
            var score = 0
            for (k in 0 until 6) {
                val v = c.peekRam(0x4D + 2 * k)
                score = score * 10 + (if (v % 8 == 0 && v / 8 <= 9) v / 8 else 0)
            }
            if (score % 10 == 0) score else -1
        }

        /** Enduro: the odometer, four BCD digits at $A8-$A9. */
        private val ENDURO = ScoreReader { c -> bcd(c.peekRam(0x29)) * 100 + bcd(c.peekRam(0x28)) }

        private val KNOWN = mapOf(
            "240bfbac5163af4df5ae713985386f92" to SEAQUEST,
            "393948436d1f4cc3192410bb918f9724" to RIVER_RAID,
            "94b92a882f6dbaa6993a46e2dcc58402" to ENDURO,
        )

        /** The reader for a ROM with this MD5 (lowercase hex), if it's a game we know. */
        fun forRom(md5: String): ScoreReader? = KNOWN[md5]

        fun md5(rom: ByteArray): String =
            java.security.MessageDigest.getInstance("MD5").digest(rom).joinToString("") { "%02x".format(it) }
    }
}
