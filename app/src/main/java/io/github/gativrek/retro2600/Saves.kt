package io.github.gativrek.retro2600

import android.content.Context
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Per-game files in the app's private storage, keyed by the ROM's MD5 so renaming or moving a
 * game keeps its saves: an automatic snapshot taken whenever the game is left, one manual slot,
 * and the best score.
 */
class Saves(context: Context, private val md5: String) {
    private val dir = File(context.filesDir, "saves").apply { mkdirs() }
    private val prefs = context.getSharedPreferences(RECORDS, Context.MODE_PRIVATE)

    val auto = File(dir, "$md5.auto")
    val slot = File(dir, "$md5.slot")

    var record: Int
        get() = prefs.getInt(md5, 0)
        set(value) { prefs.edit().putInt(md5, value).apply() }

    fun read(file: File): ByteArray? = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    /** Writes via a temporary file so a crash mid-write never leaves a half-written save. */
    fun write(file: File, data: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val RECORDS = "records"

        fun record(context: Context, md5: String) =
            context.getSharedPreferences(RECORDS, Context.MODE_PRIVATE).getInt(md5, 0)

        /** 12345 -> "12.345" */
        fun formatScore(score: Int): String =
            NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(score)
    }
}
