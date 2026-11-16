package io.github.gativrek.retro2600

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.gativrek.retro2600.emu.Cartridge
import io.github.gativrek.retro2600.emu.ScoreReader

/** Game library: the folder bar, one cartridge card per game, and a first-run guide. */
class MainActivity : Activity() {

    private class Game(val name: String, val fileName: String, val uri: Uri, val record: Int)

    private lateinit var library: LinearLayout
    private lateinit var onboarding: LinearLayout
    private lateinit var folderPath: TextView
    private lateinit var countLabel: TextView
    private lateinit var list: LinearLayout
    private lateinit var listHint: TextView
    private lateinit var message: LinearLayout
    private lateinit var messageTitle: TextView
    private lateinit var messageText: TextView

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

    // ---- layout ------------------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.GROUND)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = systemBars(insets)
            v.setPadding(bars[0], bars[1], bars[2], bars[3])
            insets
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
            addView(text(getString(R.string.brand_tag), 12f, Style.mono(context), Style.ACCENT).apply {
                letterSpacing = 0.18f
            })
            addView(text(getString(R.string.app_name), 34f, Style.display(context), Style.TEXT).apply {
                letterSpacing = -0.01f
                setPadding(0, dp(4), 0, 0)
            })
        })

        val body = FrameLayout(this)
        library = buildLibrary()
        onboarding = buildOnboarding()
        body.addView(library, FrameLayout.LayoutParams(-1, -1))
        body.addView(onboarding, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildLibrary() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // Folder bar
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = rounded(Style.SURFACE, dp(14), Style.BORDER)
        }
        bar.addView(icon(R.drawable.ic_folder, Style.TEXT_DIM, 22))
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(text(getString(R.string.folder_bar_label), 12f, Style.body(context), Style.TEXT_DIM))
            folderPath = text("", 14f, Style.mono(context), Style.TEXT).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            addView(folderPath)
        }
        bar.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pillButton(getString(R.string.open_folder), null, 44) { openFolder() })
        bar.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_swap)
            imageTintList = ColorStateList.valueOf(Style.TEXT)
            contentDescription = getString(R.string.change_folder)
            background = ripple(rounded(0, dp(22), Style.BORDER_STRONG), dp(22))
            setOnClickListener { pickFolder() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), 0, dp(20), 0) })

        // Section header
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(20), dp(28), dp(20), dp(10))
            addView(text(getString(R.string.your_games), 15f, Style.displaySemi(context), Style.TEXT_DIM).apply {
                letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            countLabel = text("", 12f, Style.mono(context), Style.TEXT_DIM)
            addView(countLabel)
        })

        // Games (or a message in their place)
        val frame = FrameLayout(context)
        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(28))
        }
        listHint = text(getString(R.string.list_hint), 13f, Style.body(context), Style.TEXT_DIM).apply {
            setLineSpacing(0f, 1.3f)
            setPadding(dp(4), dp(18), dp(4), 0)
        }
        frame.addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }, FrameLayout.LayoutParams(-1, -1))
        message = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), dp(48))
            messageTitle = text("", 20f, Style.displaySemi(context), Style.TEXT).apply { gravity = Gravity.CENTER }
            messageText = text("", 15f, Style.body(context), Style.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(8), 0, 0)
            }
            addView(messageTitle)
            addView(messageText)
        }
        frame.addView(message, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun buildOnboarding() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(8), dp(28), dp(8))
        }
        center.addView(ImageView(context).apply {
            setImageResource(R.drawable.illustration_slot)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(168), dp(128)))
        center.addView(text(getString(R.string.no_folder_title), 24f, Style.displaySemi(context), Style.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
        center.addView(text(getString(R.string.no_folder_subtitle), 15f, Style.body(context), Style.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        })
        val mono = Style.mono(context)
        center.addView(step(1, getString(R.string.step_1), "Download⁠/⁠Atari", mono))
        center.addView(step(2, getString(R.string.step_2), ".a26", mono))
        center.addView(step(3, getString(R.string.step_3), getString(R.string.choose_folder), Style.bodySemi(context)))

        addView(ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(context).apply {
                addView(center, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        addView(pillButton(getString(R.string.choose_folder), R.drawable.ic_folder, 52) { pickFolder() },
            LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(dp(20), dp(8), dp(20), dp(32)) })
    }

    /** A numbered first-run step; [highlight] is set in [face] (mono for paths, semibold for buttons). */
    private fun step(number: Int, line: String, highlight: String, face: Typeface): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        row.addView(text(number.toString(), 13f, Style.mono(this), Style.TEXT).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Style.SURFACE_HIGH) }
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val styled = SpannableString(line)
        val at = line.indexOf(highlight)
        if (at >= 0) styled.setSpan(FontSpan(face), at, at + highlight.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        row.addView(text("", 15f, Style.body(this), Style.TEXT).apply {
            text = styled
            setLineSpacing(0f, 1.25f)
            setPadding(dp(14), dp(3), 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun gameCard(game: Game, index: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(76)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = ripple(rounded(Style.SURFACE, dp(14), Style.BORDER), dp(14))
        isClickable = true
        contentDescription = game.name

        addView(View(context).apply {
            background = CartridgeDrawable(Style.LABELS[index % Style.LABELS.size])
        }, LinearLayout.LayoutParams(dp(40), dp(48)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            addView(text(game.name, 20f, Style.displaySemi(context), Style.TEXT))
            addView(text(game.fileName, 12f, Style.mono(context), Style.TEXT_DIM).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            if (game.record > 0) {
                addView(text(getString(R.string.record_card, Saves.formatScore(game.record)), 12f,
                    Style.mono(context), Style.LABELS[1]).apply {
                    letterSpacing = 0.08f
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ic_play, Style.TEXT, 22))

        setOnClickListener {
            startActivity(
                Intent(this@MainActivity, GameActivity::class.java)
                    .putExtra(GameActivity.EXTRA_URI, game.uri.toString())
                    .putExtra(GameActivity.EXTRA_NAME, game.name)
            )
        }
    }

    // ---- small view helpers ------------------------------------------------------------------

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
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
        onboarding.visibility = if (tree == null) View.VISIBLE else View.GONE
        library.visibility = if (tree == null) View.GONE else View.VISIBLE
        if (tree == null) return
        folderPath.text = readableFolder(tree)
        if (list.childCount == 0) showMessage(getString(R.string.loading), "")
        Thread {
            val games = runCatching { listGames(tree) }.getOrNull()
            runOnUiThread { if (!isDestroyed) show(games) }
        }.start()
    }

    private fun show(games: List<Game>?) {
        list.removeAllViews()
        countLabel.text = games?.let { resources.getQuantityString(R.plurals.cart_count, it.size, it.size) } ?: ""
        when {
            games == null -> showMessage("", getString(R.string.folder_unavailable))
            games.isEmpty() -> showMessage(getString(R.string.empty_folder_title), getString(R.string.empty_folder))
            else -> {
                message.visibility = View.GONE
                games.forEachIndexed { k, game ->
                    list.addView(gameCard(game, k), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                }
                list.addView(listHint)
            }
        }
    }

    private fun showMessage(title: String, text: String) {
        list.removeAllViews()
        messageTitle.text = title
        messageTitle.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
        messageText.text = text
        messageText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        message.visibility = View.VISIBLE
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
                games.add(Game(prettyName(fileName), fileName, uri, recordOf(uri)))
            }
        } ?: throw IllegalStateException("query failed")
        return games.sortedBy { it.name.lowercase() }
    }

    /** Best score saved for this ROM (records are keyed by the ROM's MD5), or 0. */
    private fun recordOf(uri: Uri): Int = runCatching {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > Cartridge.MAX_SIZE) 0 else Saves.record(this, ScoreReader.md5(bytes))
    }.getOrDefault(0)

    private fun readableFolder(tree: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return id.substringAfter(':', id).ifEmpty { "/" }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

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
