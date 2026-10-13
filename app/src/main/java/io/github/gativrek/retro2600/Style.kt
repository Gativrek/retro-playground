package io.github.gativrek.retro2600

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/** The app's small design system: colors and typefaces from the Retro Playground design. */
object Style {
    const val GROUND = 0xFF11100E.toInt()
    const val SURFACE = 0xFF1C1A17.toInt()
    const val SURFACE_HIGH = 0xFF26231F.toInt()
    const val BORDER = 0xFF2E2A25.toInt()
    const val BORDER_STRONG = 0xFF3A3530.toInt()
    const val TEXT = 0xFFF2EDE4.toInt()
    const val TEXT_DIM = 0xFFA39C90.toInt()
    const val ACCENT = 0xFFF07A3A.toInt()
    const val ON_ACCENT = 0xFF1A1009.toInt()

    /** Cartridge label colors, handed out in list order. */
    val LABELS = intArrayOf(ACCENT, 0xFFE8C547.toInt(), 0xFF4FB3A9.toInt(), 0xFF3D6FB6.toInt())

    private var cache: Array<Typeface>? = null
    private fun fonts(context: Context): Array<Typeface> = cache ?: run {
        val r = context.resources
        arrayOf(
            r.getFont(R.font.chakra_petch_bold),
            r.getFont(R.font.chakra_petch_semibold),
            r.getFont(R.font.plex_sans_regular),
            r.getFont(R.font.plex_sans_semibold),
            r.getFont(R.font.plex_mono_regular),
        ).also { cache = it }
    }

    fun display(c: Context) = fonts(c)[0]
    fun displaySemi(c: Context) = fonts(c)[1]
    fun body(c: Context) = fonts(c)[2]
    fun bodySemi(c: Context) = fonts(c)[3]
    fun mono(c: Context) = fonts(c)[4]
}

/** Switches a run of text to another typeface (TypefaceSpan(Typeface) needs API 28). */
class FontSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) { paint.typeface = typeface }
    override fun updateDrawState(paint: TextPaint) { paint.typeface = typeface }
}

/** The little cartridge shown beside each game (designed on a 40x48 grid). */
class CartridgeDrawable(private val labelColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val sx = b.width() / 40f
        val sy = b.height() / 48f
        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
            paint.color = color
            r.set(b.left + x * sx, b.top + y * sy, b.left + (x + w) * sx, b.top + (y + h) * sy)
            canvas.drawRoundRect(r, radius * sx, radius * sx, paint)
        }
        rect(0f, 0f, 40f, 48f, 4f, 0xFF2A2622.toInt())
        for (y in intArrayOf(4, 8, 12)) rect(5f, y.toFloat(), 30f, 1.6f, 0f, 0xFF3E3832.toInt())
        rect(5f, 18f, 30f, 25f, 2f, labelColor)
        rect(9f, 24f, 22f, 2.4f, 0f, 0x8C1A1009.toInt())
        rect(9f, 29f, 14f, 2.4f, 0f, 0x591A1009)
    }

    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
