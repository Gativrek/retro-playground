package io.github.gativrek.retro2600

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * The card shown over a paused game: continue, save / go back to the saved point, leave.
 * It swallows every touch while visible so nothing reaches the game controls underneath.
 */
@SuppressLint("ViewConstructor")
class PauseMenu(
    context: Context,
    gameName: String,
    onContinue: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onExit: () -> Unit,
) : FrameLayout(context) {

    private val record: TextView
    private val save: TextView
    private val load: TextView
    private val resetSaveLabel = Runnable { save.text = context.getString(R.string.btn_save) }

    val isShowing get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xD90B0A09.toInt())
        isClickable = true // swallow touches
        visibility = View.GONE

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(22), context.dp(24), context.dp(24))
            background = context.rounded(Style.SURFACE, context.dp(20), Style.BORDER_STRONG)
        }
        card.addView(context.text(context.getString(R.string.paused_tag), 12f, Style.mono(context), Style.ACCENT).apply {
            letterSpacing = 0.18f
        })
        card.addView(context.text(gameName, 28f, Style.display(context), Style.TEXT).apply {
            setPadding(0, context.dp(4), 0, 0)
        })
        record = context.text("", 15f, Style.body(context), Style.TEXT_DIM).apply {
            setPadding(0, context.dp(6), 0, 0)
        }
        card.addView(record)

        card.addView(context.pillButton(context.getString(R.string.btn_continue), R.drawable.ic_play, 52, onContinue).apply {
            isFocusable = true
        }, LinearLayout.LayoutParams(-1, context.dp(52)).apply { topMargin = context.dp(20) })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        save = context.ghostButton(context.getString(R.string.btn_save), 48, onSave).apply { isFocusable = true }
        load = context.ghostButton(context.getString(R.string.btn_load), 48, onLoad).apply { isFocusable = true }
        row.addView(save, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(load, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(10) })
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })

        card.addView(context.ghostButton(context.getString(R.string.btn_exit), 48, onExit).apply {
            isFocusable = true
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })

        val metrics = resources.displayMetrics
        val width = min(context.dp(460), metrics.widthPixels - context.dp(48))
        addView(ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(context).apply {
                setPadding(0, context.dp(16), 0, context.dp(16))
                addView(card, LayoutParams(width, -2, Gravity.CENTER))
            })
        }, LayoutParams(-1, -1))
    }

    /** [recordText] is hidden when null; a new record is shown in the highlight color. */
    fun show(recordText: String?, newRecord: Boolean, canLoad: Boolean) {
        record.text = recordText ?: ""
        record.visibility = if (recordText == null) View.GONE else View.VISIBLE
        record.setTextColor(if (newRecord) Style.LABELS[1] else Style.TEXT_DIM)
        setCanLoad(canLoad)
        removeCallbacks(resetSaveLabel)
        save.text = context.getString(R.string.btn_save)
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
    }

    fun setCanLoad(canLoad: Boolean) {
        load.isEnabled = canLoad
        load.alpha = if (canLoad) 1f else 0.4f
    }

    /** Brief confirmation on the save button. */
    fun flashSaved() {
        save.text = context.getString(R.string.btn_saved)
        removeCallbacks(resetSaveLabel)
        postDelayed(resetSaveLabel, 1500)
    }
}
