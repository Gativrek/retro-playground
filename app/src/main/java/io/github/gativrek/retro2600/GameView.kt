package io.github.gativrek.retro2600

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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
 * Left half of the screen = 8-way stick, right half = fire button, small buttons on top.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val onExit: () -> Unit) : View(context) {

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

    // ---- input ---------------------------------------------------------------------------------

    /** Bits from [Atari2600] (UP, DOWN, ...), from touch. */
    @Volatile private var touchInput = 0
    /** Bits from a physical gamepad/keyboard, set by the activity. */
    @Volatile var hardwareInput = 0

    val input: Int get() = touchInput or hardwareInput

    private val roles = SparseIntArray()
    private var knobX = 0f
    private var knobY = 0f
    private var knobActive = false

    // ---- layout --------------------------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private val screenRect = RectF()
    private var padX = 0f; private var padY = 0f; private var padR = 0f
    private var fireX = 0f; private var fireY = 0f; private var fireR = 0f
    private val exitRect = RectF()
    private val selectRect = RectF()
    private val resetRect = RectF()

    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0x66FFFFFF
    }
    private val fillDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val fillLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt() }
    private val firePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCD9352B.toInt() }
    private val fireLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A4A.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    private val arrow = Path()

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
            padR = min(side * 0.4f, height * 0.25f)
            padX = side / 2; padY = height * 0.62f
            fireR = padR * 0.72f
            fireX = width - side / 2; fireY = padY
        } else {
            // Narrow screens (tablets): controls float over the bottom corners.
            padR = height * 0.17f
            padX = padR * 1.35f; padY = height - padR * 1.35f
            fireR = padR * 0.72f
            fireX = width - padR * 1.35f; fireY = padY
        }

        val bh = 34 * density
        val bw = if (roomy) min(side * 0.7f, 120 * density) else 96 * density
        val top = 18 * density
        val leftCx = if (roomy) side / 2 else bw / 2 + 16 * density
        val rightCx = if (roomy) width - side / 2 else width - bw / 2 - 16 * density
        exitRect.set(leftCx - bw / 2, top, leftCx + bw / 2, top + bh)
        selectRect.set(leftCx - bw / 2, top + bh * 1.5f, leftCx + bw / 2, top + bh * 2.5f)
        resetRect.set(rightCx - bw / 2, top, rightCx + bw / 2, top + bh)
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            if (hasFrame) bitmap.setPixels(pending, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, null, screenRect, pixelPaint)

        val inp = touchInput or hardwareInput
        drawPad(canvas, inp)
        canvas.drawCircle(fireX, fireY, fireR, if ((inp and Atari2600.FIRE) != 0) fireLit else firePaint)
        canvas.drawCircle(fireX, fireY, fireR, outline)
        drawButton(canvas, exitRect, context.getString(R.string.btn_exit), false)
        drawButton(canvas, selectRect, context.getString(R.string.btn_select), (inp and Atari2600.SELECT) != 0)
        drawButton(canvas, resetRect, context.getString(R.string.btn_reset), (inp and Atari2600.RESET) != 0)
    }

    private fun drawPad(canvas: Canvas, inp: Int) {
        canvas.drawCircle(padX, padY, padR, fillDim)
        canvas.drawCircle(padX, padY, padR, outline)
        val a = padR * 0.62f // distance of each arrow from the center
        val s = padR * 0.2f  // arrow size
        drawArrow(canvas, padX, padY - a, 0f, -1f, s, (inp and Atari2600.UP) != 0)
        drawArrow(canvas, padX, padY + a, 0f, 1f, s, (inp and Atari2600.DOWN) != 0)
        drawArrow(canvas, padX - a, padY, -1f, 0f, s, (inp and Atari2600.LEFT) != 0)
        drawArrow(canvas, padX + a, padY, 1f, 0f, s, (inp and Atari2600.RIGHT) != 0)
        if (knobActive) {
            val dx = knobX - padX; val dy = knobY - padY
            val d = hypot(dx, dy)
            val k = if (d > padR * 0.55f) padR * 0.55f / d else 1f
            canvas.drawCircle(padX + dx * k, padY + dy * k, padR * 0.32f, fillLit)
        }
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, dx: Float, dy: Float, s: Float, lit: Boolean) {
        arrow.reset()
        arrow.moveTo(cx + dx * s, cy + dy * s)
        arrow.lineTo(cx - dx * s * 0.6f + dy * s, cy - dy * s * 0.6f + dx * s)
        arrow.lineTo(cx - dx * s * 0.6f - dy * s, cy - dy * s * 0.6f - dx * s)
        arrow.close()
        canvas.drawPath(arrow, if (lit) fillLit else outline)
    }

    private fun drawButton(canvas: Canvas, r: RectF, text: String, lit: Boolean) {
        val radius = r.height() / 2
        canvas.drawRoundRect(r, radius, radius, if (lit) fillLit else fillDim)
        canvas.drawRoundRect(r, radius, radius, outline)
        canvas.drawText(text, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
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
                if (role == ROLE_EXIT) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onExit()
                } else if (role != ROLE_STICK) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            MotionEvent.ACTION_CANCEL -> roles.clear()
        }

        var mask = 0
        var stick = false
        for (k in 0 until e.pointerCount) {
            val lifting = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) &&
                k == e.actionIndex
            if (lifting || action == MotionEvent.ACTION_CANCEL) continue
            when (roles.get(e.getPointerId(k))) {
                ROLE_STICK -> {
                    stick = true
                    knobX = e.getX(k); knobY = e.getY(k)
                    mask = mask or direction(knobX, knobY)
                }
                ROLE_FIRE -> mask = mask or Atari2600.FIRE
                ROLE_SELECT -> mask = mask or Atari2600.SELECT
                ROLE_RESET -> mask = mask or Atari2600.RESET
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            roles.delete(e.getPointerId(e.actionIndex))
        }
        knobActive = stick
        if (mask != touchInput) {
            touchInput = mask
            invalidate()
        }
        return true
    }

    private fun roleAt(x: Float, y: Float): Int {
        val slop = 8 * density
        fun hit(r: RectF) = x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        return when {
            hit(exitRect) -> ROLE_EXIT
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
        private const val ROLE_EXIT = 5

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
