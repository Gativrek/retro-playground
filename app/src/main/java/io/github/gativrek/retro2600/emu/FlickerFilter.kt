package io.github.gativrek.retro2600.emu

/**
 * Many 2600 games show more objects than the hardware allows by drawing them on alternate frames.
 * On a CRT that looked like a soft shimmer; on a phone it's a hard 30 Hz blink. When a game keeps
 * doing that, this blends each frame with the previous one (like Stella's "phosphor" mode).
 *
 * Detection looks for pixels that alternate frame by frame (A B A B). Small animations, such as
 * Enduro's spinning wheels (~200 pixels), stay below the threshold and are left alone.
 */
class FlickerFilter(private val size: Int) {
    companion object {
        /** Alternating pixels per frame that count as sprite flicker. */
        const val PIXEL_THRESHOLD = 300
        private const val LEVEL_MAX = 60
        private const val LEVEL_ON = 30
    }

    private val history = Array(3) { IntArray(size) } // previous 1, 2 and 3 frames back
    private var frames = 0
    private var level = 0

    /** True while frames are being blended. */
    var active = false
        private set

    /** Processes one frame in place. */
    fun process(screen: IntArray) {
        val f1 = history[0]; val f2 = history[1]; val f3 = history[2]
        if (frames >= 3) {
            var n = 0
            for (i in 0 until size) {
                val c = screen[i]
                val p = f1[i]
                if (c != p && c == f2[i] && p == f3[i]) n++
            }
            level = if (n >= PIXEL_THRESHOLD) minOf(level + 1, LEVEL_MAX) else maxOf(level - 1, 0)
            if (level >= LEVEL_ON) active = true else if (level == 0) active = false
        } else {
            frames++
        }

        // Rotate the history: the oldest buffer receives this raw frame.
        history[2] = f2; history[1] = f1; history[0] = f3
        System.arraycopy(screen, 0, f3, 0, size)

        if (active) {
            for (i in 0 until size) {
                val a = screen[i]
                val b = f1[i]
                if (a != b) screen[i] = (((a and 0xFEFEFE) + (b and 0xFEFEFE)) ushr 1) or 0xFF000000.toInt()
            }
        }
    }

    /** Forget the past frames (after loading a state or restarting). */
    fun reset() {
        frames = 0
        level = 0
        active = false
    }
}
