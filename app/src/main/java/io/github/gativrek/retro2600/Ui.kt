package io.github.gativrek.retro2600

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

/** View helpers shared by the library and the pause menu. */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.text(value: String, sp: Float, face: Typeface, color: Int) = TextView(this).apply {
    text = value
    textSize = sp
    typeface = face
    setTextColor(color)
    includeFontPadding = false
}

fun Context.rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
    cornerRadius = radius.toFloat()
    setColor(fill)
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun ripple(content: Drawable, radius: Int) = RippleDrawable(
    ColorStateList.valueOf(0x33F2EDE4),
    content,
    GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(0xFFFFFFFF.toInt()) }
)

/** The orange pill used for main actions, optionally with a leading icon. */
fun Context.pillButton(label: String, iconRes: Int?, heightDp: Int, onClick: () -> Unit) = TextView(this).apply {
    text = label
    textSize = if (heightDp >= 52) 16f else 14f
    typeface = Style.bodySemi(context)
    setTextColor(Style.ON_ACCENT)
    gravity = Gravity.CENTER
    includeFontPadding = false
    minHeight = dp(heightDp)
    setPadding(dp(16), 0, dp(16), 0)
    background = ripple(rounded(Style.ACCENT, dp(heightDp / 2), 0), dp(heightDp / 2))
    if (iconRes != null) {
        val d = getDrawable(iconRes)!!.mutate().apply {
            setTint(Style.ON_ACCENT)
            setBounds(0, 0, dp(20), dp(20))
        }
        setCompoundDrawables(d, null, null, null)
        compoundDrawablePadding = dp(10)
        // Keep icon + label centered together in a full-width button.
        addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val tv = v as TextView
            val content = tv.paint.measureText(tv.text.toString()) + dp(30)
            val side = ((r - l - content) / 2).toInt().coerceAtLeast(dp(16))
            if (tv.paddingLeft != side) tv.setPadding(side, 0, side, 0)
        }
    }
    isClickable = true
    setOnClickListener { onClick() }
}

/** A quieter outlined button for secondary actions. */
fun Context.ghostButton(label: String, heightDp: Int, onClick: () -> Unit) = TextView(this).apply {
    text = label
    textSize = 15f
    typeface = Style.bodySemi(context)
    setTextColor(Style.TEXT)
    gravity = Gravity.CENTER
    includeFontPadding = false
    minHeight = dp(heightDp)
    setPadding(dp(12), dp(6), dp(12), dp(6))
    background = ripple(rounded(Style.SURFACE_HIGH, dp(heightDp / 2), Style.BORDER_STRONG), dp(heightDp / 2))
    isClickable = true
    setOnClickListener { onClick() }
}
