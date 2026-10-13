package io.github.gativrek.retro2600

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Minimal game picker: one list of names and a shortcut to the games folder. */
class MainActivity : Activity() {

    private class Game(val name: String, val uri: Uri)

    private lateinit var list: LinearLayout
    private lateinit var message: TextView
    private lateinit var folderLabel: TextView
    private lateinit var openButton: Button
    private lateinit var changeButton: Button

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var folder: Uri?
        get() = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        set(value) { prefs.edit().putString(KEY_FOLDER, value?.toString()).apply() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        refresh() // the user may have copied new games into the folder meanwhile
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = systemBars(insets)
            v.setPadding(dp(20) + bars[0], dp(16) + bars[1], dp(20) + bars[2], dp(8) + bars[3])
            insets
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(ACCENT)
            textSize = 30f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            setTextColor(TEXT_DIM)
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        openButton = pillButton(R.string.open_folder, filled = true) { openFolder() }
        changeButton = pillButton(R.string.change_folder, filled = false) { pickFolder() }
        buttons.addView(openButton)
        buttons.addView(changeButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        root.addView(buttons)

        folderLabel = TextView(this).apply {
            setTextColor(TEXT_DIM)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(folderLabel)

        message = TextView(this).apply {
            setTextColor(TEXT_DIM)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(48), dp(16), dp(48))
        }
        root.addView(message)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun pillButton(textRes: Int, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = getString(textRes)
        isAllCaps = false
        textSize = 15f
        setTextColor(if (filled) BACKGROUND else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (filled) setColor(ACCENT) else setStroke(dp(1), TEXT_DIM)
        }
        stateListAnimator = null
        minHeight = dp(44)
        setPadding(dp(20), 0, dp(20), 0)
        setOnClickListener { onClick() }
    }

    // ---- folder ------------------------------------------------------------------------------

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        folder?.let { tree ->
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderDocumentUri(tree))
        }
        try {
            startActivityForResult(intent, REQUEST_FOLDER)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_file_manager))
        }
    }

    /** "Go to the folder": show it in the system file manager (falls back to the folder picker). */
    private fun openFolder() {
        val tree = folder ?: return pickFolder()
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folderDocumentUri(tree), DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            pickFolder()
        } catch (e: SecurityException) {
            pickFolder()
        }
    }

    private fun folderDocumentUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUri(tree.authority, DocumentsContract.getTreeDocumentId(tree))

    @Deprecated("Framework Activity API; no AndroidX in this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || tree == null) return
        folder?.let { old ->
            if (old != tree) runCatching {
                contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        folder = tree
        refresh()
    }

    // ---- game list ---------------------------------------------------------------------------

    private fun refresh() {
        val tree = folder
        openButton.text = getString(if (tree == null) R.string.choose_folder else R.string.open_folder)
        changeButton.visibility = if (tree == null) View.GONE else View.VISIBLE
        folderLabel.text = tree?.let { getString(R.string.folder_label, readableFolder(it)) } ?: ""
        if (tree == null) {
            showMessage(getString(R.string.no_folder))
            list.removeAllViews()
            return
        }
        if (list.childCount == 0) showMessage(getString(R.string.loading))
        Thread {
            val games = runCatching { listGames(tree) }.getOrNull()
            runOnUiThread { if (!isDestroyed) show(games) }
        }.start()
    }

    private fun show(games: List<Game>?) {
        list.removeAllViews()
        when {
            games == null -> showMessage(getString(R.string.folder_unavailable))
            games.isEmpty() -> showMessage(getString(R.string.empty_folder))
            else -> {
                message.visibility = View.GONE
                games.forEach { list.addView(gameRow(it)) }
            }
        }
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun gameRow(game: Game) = TextView(this).apply {
        text = game.name
        setTextColor(TEXT)
        textSize = 20f
        setPadding(dp(4), dp(18), dp(4), dp(18))
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
        layoutParams = ViewGroup.LayoutParams(-1, -2)
        setOnClickListener {
            startActivity(
                Intent(this@MainActivity, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_URI, game.uri.toString())
                    .putExtra(GameActivity.EXTRA_NAME, game.name)
            )
        }
    }

    private fun listGames(tree: Uri): List<Game> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val games = ArrayList<Game>()
        contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val fileName = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext != "a26" && ext != "bin") continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                games.add(Game(prettyName(fileName), uri))
            }
        } ?: throw IllegalStateException("query failed")
        return games.sortedBy { it.name.lowercase() }
    }

    private fun readableFolder(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return id.substringAfter(':', id).ifEmpty { "/" }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** left, top, right, bottom system bar insets. */
    private fun systemBars(insets: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            intArrayOf(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }

    companion object {
        private const val KEY_FOLDER = "folder"
        private const val REQUEST_FOLDER = 1

        private const val BACKGROUND = 0xFF121214.toInt()
        private const val ACCENT = 0xFFE8743B.toInt()
        private const val TEXT = 0xFFEDEDED.toInt()
        private const val TEXT_DIM = 0xFF8E8E94.toInt()

        /** "River Raid (1982) (Activision) [!].a26" -> "River Raid" */
        fun prettyName(fileName: String): String {
            val base = fileName.substringBeforeLast('.')
            val cleaned = base
                .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
                .replace('_', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim()
            return cleaned.ifEmpty { base }
        }
    }
}
