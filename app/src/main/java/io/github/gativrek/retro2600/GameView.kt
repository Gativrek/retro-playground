package io.github.gativrek.retro2600

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.gativrek.retro2600.emu.Atari2600
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the game screen plus the touch controls on top, and turns touches into joystick input.
 * Left half of the screen = 8-way pad, right half = fire button; console switches and the pause
 * button on top.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val onPauseRequest: () -> Unit) : View(context) {

    // ---- frame hand-off from the emulation thread ---------------------------------------------

    private val w = Atari2600.SCREEN_WIDTH
    private val h = Atari2600.SCREEN_HEIGHT
    private val pending = IntArray(w * h)
    private val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val lock = Any()
    private var hasFrame = false

    fun publishFrame(pixels: IntArray) {
        synchronized(lock) {
            System.arraycopy(pixels, 0, pending, 0, pending.size)
            hasFrame = true
        }
        postInvalidateOnAnimation()
    }

    // ---- TV power on/off --------------------------------------------------------------------

    /** 0 = TV off, 1 = picture fully on. In between, the picture opens from a bright line. */
    private var tvLevel = 1f
    private var tvAnimator: ValueAnimator? = null

    /** Old-TV warm-up: a bright line widens, then opens into the picture. */
    fun powerOn(delayMs: Long) = animateTv(0f, 1f, 340, delayMs, null)

    /** The reverse: the picture collapses to a line, then to a dot; [done] runs at the end. */
    fun powerOff(done: () -> Unit) = animateTv(tvLevel, 0f, 280, 0, done)

    private fun animateTv(from: Float, to: Float, duration: Long, delayMs: Long, done: (() -> Unit)?) {
        tvAnimator?.cancel()
        tvLevel = from
        invalidate()
        tvAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            startDelay = delayMs
            interpolator = null // linear; the easing is applied per phase in drawScreen
            addUpdateListener { tvLevel = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tvLevel = to
                    invalidate()
                    done?.invoke()
                }
            })
            start()
        }
    }

    private fun easeOut(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u * u }

    private fun drawScreen(canvas: Canvas) {
        val level = tvLevel
        if (level >= 1f) {
            canvas.drawBitmap(bitmap, null, screenRect, pixelPaint)
            return
        }
        if (level <= 0f) return
        val cx = screenRect.centerX()
        val cy = screenRect.centerY()
        val line = dp(3f)
        if (level < LINE_PHASE) {
            // A bright scan line growing out from the middle.
            val half = screenRect.width() / 2 * easeOut(level / LINE_PHASE)
            fill.color = TV_GLOW
            tmp.set(cx - half, cy - line / 2, cx + half, cy + line / 2)
            canvas.drawRect(tmp, fill)
        } else {
            // The picture opens vertically, washed out at first like a cold tube.
            val t = easeOut((level - LINE_PHASE) / (1f - LINE_PHASE))
            val half = line / 2 + (screenRect.height() / 2 - line / 2) * t
            tmp.set(screenRect.left, cy - half, screenRect.right, cy + half)
            canvas.drawBitmap(bitmap, null, tmp, pixelPaint)
            fill.color = TV_GLOW
            fill.alpha = ((1f - t) * 0.85f * 255).toInt()
            canvas.drawRect(tmp, fill)
        }
    }

    // ---- input ---------------------------------------------------------------------------------

    /** Bits from [Atari2600] (UP, DOWN, ...), from touch. */
    @Volatile private var touchInput = 0
    /** Bits from a physical gamepad/keyboard, set by the activity. */
    @Volatile var hardwareInput = 0

    val input: Int get() = touchInput or hardwareInput

    private val roles = SparseIntArray()

    /** Lets go of everything (e.g. when the pause menu covers the controls). */
    fun releaseTouches() {
        roles.clear()
        touchInput = 0
        invalidate()
    }

    // ---- layout --------------------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val screenRect = RectF()
    private var padX = 0f; private var padY = 0f; private var padR = 0f
    private var fireX = 0f; private var fireY = 0f; private var fireR = 0f
    private val pauseRect = RectF()
    private val selectRect = RectF()
    private val resetRect = RectF()

    // ---- paints (colors from the Retro Playground design) ---------------------------------------

    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Style.TEXT
        textSize = dp(13f)
        typeface = Style.mono(context)
        letterSpacing = 0.14f
    }
    private val path = Path()
    private val tmp = RectF()

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        // 160x210 shown at 4:3, the shape of the TV picture.
        val aspect = 4f / 3f
        var sh = height.toFloat()
        var sw = sh * aspect
        if (sw > width) { sw = width.toFloat(); sh = sw / aspect }
        screenRect.set((width - sw) / 2, (height - sh) / 2, (width + sw) / 2, (height + sh) / 2)

        val side = (width - sw) / 2
        val roomy = side >= height * 0.3f
        if (roomy) {
            // Controls live in the bars beside the picture.
            padR = min(side * 0.41f, height * 0.17f)
            padX = side / 2; padY = height * 0.67f
            fireR = padR * 0.73f
            fireX = width - side / 2; fireY = padY
        } else {
            // Narrow screens (tablets): controls float over the bottom corners.
            padR = height * 0.15f
            padX = padR * 1.3f; padY = height - padR * 1.3f
            fireR = padR * 0.73f
            fireX = width - padR * 1.3f; fireY = padY
        }

        val m = dp(16f)
        val bh = dp(48f)
        val bw = if (roomy) min(dp(130f), side - 2 * m) else dp(130f)
        val e = dp(44f)
        pauseRect.set(m, m, m + e, m + e)
        selectRect.set(m, m + e + dp(16f), m + bw, m + e + dp(16f) + bh)
        resetRect.set(width - m - bw, m, width - m, m + bh)
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            if (hasFrame) bitmap.setPixels(pending, 0, w, 0, 0, w, h)
        }
        drawScreen(canvas)

        val inp = touchInput or hardwareInput
        drawPauseButton(canvas)
        drawSwitch(canvas, selectRect, context.getString(R.string.btn_select), 0xFFC9C2B6.toInt(),
            (inp and Atari2600.SELECT) != 0)
        drawSwitch(canvas, resetRect, context.getString(R.string.btn_reset), Style.ACCENT,
            (inp and Atari2600.RESET) != 0)
        drawPad(canvas, inp)
        drawFire(canvas, (inp and Atari2600.FIRE) != 0)
    }

    private fun drawPauseButton(canvas: Canvas) {
        val r = pauseRect
        fill.color = PANEL
        canvas.drawOval(r, fill)
        stroke.color = Style.BORDER_STRONG; stroke.strokeWidth = dp(1f)
        canvas.drawOval(r, stroke)
        // Two bars
        val bw = dp(4f); val bh = dp(14f); val gap = dp(3.5f)
        fill.color = Style.TEXT
        tmp.set(r.centerX() - gap - bw, r.centerY() - bh / 2, r.centerX() - gap, r.centerY() + bh / 2)
        canvas.drawRoundRect(tmp, dp(1f), dp(1f), fill)
        tmp.set(r.centerX() + gap, r.centerY() - bh / 2, r.centerX() + gap + bw, r.centerY() + bh / 2)
        canvas.drawRoundRect(tmp, dp(1f), dp(1f), fill)
    }

    /** A 2600-style lever switch: the lever drops when held. */
    private fun drawSwitch(canvas: Canvas, r: RectF, text: String, knob: Int, down: Boolean) {
        val radius = dp(12f)
        fill.color = if (down) PANEL_LIT else PANEL
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = Style.BORDER_STRONG; stroke.strokeWidth = dp(1f)
        canvas.drawRoundRect(r, radius, radius, stroke)

        val sx = r.left + dp(14f)
        val sy = r.centerY() - dp(14f)
        tmp.set(sx + dp(4f), sy + dp(1f), sx + dp(10f), sy + dp(27f))
        fill.color = 0xFF0A0908.toInt()
        canvas.drawRoundRect(tmp, dp(3f), dp(3f), fill)
        canvas.drawRoundRect(tmp, dp(3f), dp(3f), stroke)
        val ky = if (down) sy + dp(16f) else sy + dp(3f)
        tmp.set(sx + dp(1f), ky, sx + dp(13f), ky + dp(9f))
        fill.color = knob
        canvas.drawRoundRect(tmp, dp(2f), dp(2f), fill)

        canvas.drawText(text, sx + dp(26f), r.centerY() - (label.ascent() + label.descent()) / 2, label)
    }

    private fun drawPad(canvas: Canvas, inp: Int) {
        val s = padR / 66f // the pad is drawn on a 132-unit grid
        fill.color = PANEL
        canvas.drawCircle(padX, padY, padR, fill)
        stroke.color = Style.BORDER_STRONG; stroke.strokeWidth = dp(1.5f)
        canvas.drawCircle(padX, padY, padR, stroke)

        // Cross plate
        val arm = 18f * s; val reach = 52f * s; val corner = 4f * s
        fill.color = 0xFF211F1B.toInt()
        tmp.set(padX - arm, padY - reach, padX + arm, padY + reach)
        canvas.drawRoundRect(tmp, corner, corner, fill)
        tmp.set(padX - reach, padY - arm, padX + reach, padY + arm)
        canvas.drawRoundRect(tmp, corner, corner, fill)
        fill.color = 0xFF171612.toInt()
        canvas.drawCircle(padX, padY, 10f * s, fill)

        // Glow where the thumb is pushing
        val dir = inp and (Atari2600.UP or Atari2600.DOWN or Atari2600.LEFT or Atari2600.RIGHT)
        if (dir != 0) {
            var gx = 0f; var gy = 0f
            if ((dir and Atari2600.LEFT) != 0) gx -= 1f
            if ((dir and Atari2600.RIGHT) != 0) gx += 1f
            if ((dir and Atari2600.UP) != 0) gy -= 1f
            if ((dir and Atari2600.DOWN) != 0) gy += 1f
            val len = hypot(gx, gy)
            fill.color = 0x2EF2EDE4
            canvas.drawCircle(padX + gx / len * 28f * s, padY + gy / len * 28f * s, 20f * s, fill)
        }

        drawArrow(canvas, 0f, -1f, s, (inp and Atari2600.UP) != 0)
        drawArrow(canvas, 0f, 1f, s, (inp and Atari2600.DOWN) != 0)
        drawArrow(canvas, -1f, 0f, s, (inp and Atari2600.LEFT) != 0)
        drawArrow(canvas, 1f, 0f, s, (inp and Atari2600.RIGHT) != 0)
    }

    /** A filled triangle at the end of one arm, pointing outwards along (dx, dy). */
    private fun drawArrow(canvas: Canvas, dx: Float, dy: Float, s: Float, lit: Boolean) {
        val tipX = padX + dx * 44f * s; val tipY = padY + dy * 44f * s
        val baseX = padX + dx * 34f * s; val baseY = padY + dy * 34f * s
        val half = 8f * s
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX + dy * half, baseY + dx * half)
        path.lineTo(baseX - dy * half, baseY - dx * half)
        path.close()
        fill.color = if (lit) Style.TEXT else 0xFF8C857A.toInt()
        canvas.drawPath(path, fill)
    }

    private fun drawFire(canvas: Canvas, down: Boolean) {
        fill.color = if (down) 0xFFFF5A4A.toInt() else 0xFFC8302A.toInt()
        canvas.drawCircle(fireX, fireY, fireR, fill)
        // Lower lip so the button reads as raised; it flattens when pressed.
        if (!down) {
            fill.color = 0x47000000
            tmp.set(fireX - fireR, fireY - fireR, fireX + fireR, fireY + fireR)
            canvas.save()
            canvas.clipRect(fireX - fireR, fireY + fireR * 0.55f, fireX + fireR, fireY + fireR)
            canvas.drawOval(tmp, fill)
            canvas.restore()
        }
        stroke.color = 0xFF3A1410.toInt(); stroke.strokeWidth = dp(3f)
        canvas.drawCircle(fireX, fireY, fireR, stroke)
    }

    // ---- touch ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val role = roleAt(e.getX(i), e.getY(i))
                roles.put(e.getPointerId(i), role)
                if (role != ROLE_STICK) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (role == ROLE_PAUSE) onPauseRequest()
            }
            MotionEvent.ACTION_CANCEL -> roles.clear()
        }

        var mask = 0
        for (k in 0 until e.pointerCount) {
            val lifting = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
                k == e.actionIndex
            if (lifting || action == MotionEvent.ACTION_CANCEL) continue
            when (roles.get(e.getPointerId(k))) {
                ROLE_STICK -> mask = mask or direction(e.getX(k), e.getY(k))
                ROLE_FIRE -> mask = mask or Atari2600.FIRE
                ROLE_SELECT -> mask = mask or Atari2600.SELECT
                ROLE_RESET -> mask = mask or Atari2600.RESET
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            roles.delete(e.getPointerId(e.actionIndex))
        }
        if (mask != touchInput) {
            touchInput = mask
            invalidate()
        }
        return true
    }

    private fun roleAt(x: Float, y: Float): Int {
        val slop = dp(8f)
        fun hit(r: RectF) = x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        return when {
            hit(pauseRect) -> ROLE_PAUSE
            hit(selectRect) -> ROLE_SELECT
            hit(resetRect) -> ROLE_RESET
            x < width / 2f -> ROLE_STICK
            else -> ROLE_FIRE
        }
    }

    /** 8-way direction from the pad center, with a small dead zone. */
    private fun direction(x: Float, y: Float): Int {
        val dx = x - padX
        val dy = y - padY
        if (hypot(dx, dy) < padR * 0.2f) return 0
        val sector = ((atan2(dy, dx) / (PI / 4)).roundToInt() + 8) % 8
        return SECTORS[sector]
    }

    companion object {
        private const val ROLE_STICK = 1
        private const val ROLE_FIRE = 2
        private const val ROLE_SELECT = 3
        private const val ROLE_RESET = 4
        private const val ROLE_PAUSE = 5

        private const val PANEL = 0xFF141310.toInt()

        /** Share of the power-on spent as a widening line before the picture opens. */
        private const val LINE_PHASE = 0.35f
        private const val TV_GLOW = 0xFFF2EDE4.toInt()
        private const val PANEL_LIT = 0xFF26231F.toInt()

        // Screen angles (y grows downwards), starting at "right" and turning clockwise.
        private val SECTORS = intArrayOf(
            Atari2600.RIGHT,
            Atari2600.RIGHT or Atari2600.DOWN,
            Atari2600.DOWN,
            Atari2600.DOWN or Atari2600.LEFT,
            Atari2600.LEFT,
            Atari2600.LEFT or Atari2600.UP,
            Atari2600.UP,
            Atari2600.UP or Atari2600.RIGHT,
        )
    }
}
