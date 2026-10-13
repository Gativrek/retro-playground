package io.github.gativrek.retro2600

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.gativrek.retro2600.emu.Atari2600

/**
 * Runs the console on its own thread. Pacing comes from the audio device: writing each frame's
 * samples blocks until the hardware has room, which keeps the game at exactly its native speed.
 * If audio can't be opened, a clock-based sleep takes over.
 */
class EmulationLoop(private val console: Atari2600, private val view: GameView) : Thread("Atari2600") {

    @Volatile private var running = true

    fun shutdown() {
        running = false
        join(1000)
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val screen = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
        val pcm = ShortArray(4096)
        val track = openAudio()
        val frameNanos = (1e9 / Atari2600.FRAMES_PER_SECOND).toLong()
        var deadline = System.nanoTime()
        // DC blocker state: TIA output is 0..30, so remove the offset to avoid clicks.
        var prevIn = 0f
        var prevOut = 0f

        try {
            while (running) {
                console.setInput(view.input)
                console.runFrame()
                console.copyScreen(screen)
                view.publishFrame(screen)

                val n = console.drainAudio(pcm)
                if (track != null) {
                    for (k in 0 until n) {
                        val x = pcm[k] * 1000f
                        val y = x - prevIn + 0.995f * prevOut
                        prevIn = x; prevOut = y
                        pcm[k] = y.coerceIn(-32767f, 32767f).toInt().toShort()
                    }
                    track.write(pcm, 0, n)
                } else {
                    deadline += frameNanos
                    val wait = deadline - System.nanoTime()
                    if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -frameNanos * 5) deadline = System.nanoTime()
                }
            }
        } finally {
            track?.run {
                runCatching { pause(); flush() }
                release()
            }
        }
    }

    private fun openAudio(): AudioTrack? = runCatching {
        val rate = Atari2600.AUDIO_SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val threeFrames = (rate / Atari2600.FRAMES_PER_SECOND * 3 * 2).toInt()
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, threeFrames))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }
    }.getOrNull()
}
