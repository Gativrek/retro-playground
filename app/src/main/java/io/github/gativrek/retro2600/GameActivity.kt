package io.github.gativrek.retro2600

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import io.github.gativrek.retro2600.emu.Atari2600
import io.github.gativrek.retro2600.emu.Cartridge
import io.github.gativrek.retro2600.emu.FlickerFilter
import io.github.gativrek.retro2600.emu.ScoreReader

/**
 * Full-screen game: touch controls, plus any gamepad or keyboard that happens to be connected.
 *
 * Leaving the game in any way (pause menu, home button, a phone call) saves a snapshot, and the
 * next time the game is opened it comes back paused at exactly that moment.
 */
class GameActivity : Activity() {

    private lateinit var rom: ByteArray
    private lateinit var saves: Saves
    private var scoreReader: ScoreReader? = null
    private lateinit var console: Atari2600
    private lateinit var view: GameView
    private lateinit var menu: PauseMenu
    private val filter = FlickerFilter(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
    private var loop: EmulationLoop? = null
    private var paused = false
    private var best = 0
    private var recordBefore = 0
    private var keyInput = 0
    private var axisInput = 0
    private var firstMenu = true
    private var exiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in_fast, R.anim.fade_out_fast)
        }

        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val rom = runCatching {
            requireNotNull(uri)
            contentResolver.openInputStream(uri)!!.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= Cartridge.MAX_SIZE) { "arquivo grande demais" }
                bytes
            }
        }
        val created = rom.mapCatching { Atari2600(it) }
        created.exceptionOrNull()?.let { error ->
            Toast.makeText(this, getString(R.string.load_error, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        this.rom = rom.getOrThrow()
        console = created.getOrThrow()
        val md5 = ScoreReader.md5(this.rom)
        saves = Saves(this, md5)
        scoreReader = ScoreReader.forRom(md5)
        recordBefore = saves.record
        best = recordBefore

        // Pick up where the last session left off, paused so the player can get ready.
        saves.read(saves.auto)?.let { data ->
            val restored = restore(data)
            if (restored != null) {
                console = restored
                paused = true
            } else {
                saves.auto.delete()
            }
        }

        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        title = name
        view = GameView(this) { pauseGame() }
        menu = PauseMenu(this, name,
            onContinue = { resumeGame() },
            onSave = { saveSlot() },
            onLoad = { loadSlot() },
            onExit = { exitGame() },
        )
        setContentView(FrameLayout(this).apply {
            addView(view)
            addView(menu)
        })
        // The picture warms up like an old TV once the screen has faded in.
        view.powerOn(TV_ON_DELAY)
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_fast)
        }
    }

    /** Leave the game with the TV switching off; it's saved in onPause as always. */
    private fun exitGame() {
        if (exiting) return
        exiting = true
        stopLoop()
        menu.hide()
        view.powerOff { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        hideSystemBars()
        if (paused) showMenu() else startLoop()
    }

    override fun onPause() {
        if (::menu.isInitialized) {
            // Coming back after an interruption always lands on the pause menu.
            if (loop != null) paused = true
            stopLoop()
            persist()
        }
        super.onPause()
    }

    @Deprecated("Framework Activity API; no AndroidX in this app")
    override fun onBackPressed() {
        // Back pauses; back again from the menu leaves (the game is saved either way).
        if (menu.isShowing) exitGame() else pauseGame()
    }

    // ---- running, pausing, saving ----------------------------------------------------------------

    private fun startLoop() {
        menu.hide()
        loop = EmulationLoop(console, view, filter, scoreReader, best).also { it.start() }
    }

    private fun stopLoop() {
        val l = loop ?: return
        l.shutdown()
        best = maxOf(best, l.best)
        loop = null
    }

    private fun pauseGame() {
        if (loop == null) return
        stopLoop()
        paused = true
        showMenu()
    }

    private fun resumeGame() {
        if (loop != null || exiting) return
        paused = false
        startLoop()
    }

    private fun showMenu() {
        keyInput = 0
        axisInput = 0
        view.hardwareInput = 0
        view.releaseTouches()
        showCurrentFrame()
        // The loop only reads scores while running; a just-loaded game may already be ahead.
        scoreReader?.read(console)?.let { if (it > best) best = it }
        val recordText = when {
            scoreReader == null || best <= 0 -> null
            best > recordBefore -> getString(R.string.new_record, Saves.formatScore(best))
            else -> getString(R.string.record, Saves.formatScore(best))
        }
        // Opening a saved game: let the TV warm up first, then bring in the menu.
        val delay = if (firstMenu) TV_ON_DELAY + 300 else 0
        firstMenu = false
        menu.show(recordText, best > recordBefore, saves.slot.isFile, delay)
    }

    private fun showCurrentFrame() {
        val screen = IntArray(Atari2600.SCREEN_WIDTH * Atari2600.SCREEN_HEIGHT)
        console.copyScreen(screen)
        view.publishFrame(screen)
    }

    /** A fresh console with [data] loaded, or null if the save is damaged or from another game. */
    private fun restore(data: ByteArray): Atari2600? =
        runCatching { Atari2600(rom).also { it.loadState(data) } }.getOrNull()

    private fun persist() {
        runCatching { saves.write(saves.auto, console.saveState()) }
        if (best > saves.record) saves.record = best
    }

    private fun saveSlot() {
        val ok = runCatching { saves.write(saves.slot, console.saveState()) }.isSuccess
        if (ok) {
            menu.flashSaved()
            menu.setCanLoad(true)
        } else {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadSlot() {
        val restored = saves.read(saves.slot)?.let(::restore)
        if (restored == null) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
            return
        }
        console = restored
        filter.reset()
        resumeGame()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---- physical controllers ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::menu.isInitialized) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_MODE ||
            (menu.isShowing && code == KeyEvent.KEYCODE_BUTTON_START)
        ) {
            if (event.action == KeyEvent.ACTION_UP) if (menu.isShowing) resumeGame() else pauseGame()
            return true
        }
        // In the menu, keys move the focus and press buttons as usual.
        if (menu.isShowing) return super.dispatchKeyEvent(event)
        val bit = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Atari2600.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Atari2600.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Atari2600.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Atari2600.RIGHT
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_X -> Atari2600.FIRE
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_F2 -> Atari2600.RESET
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_F1 -> Atari2600.SELECT
            else -> return super.dispatchKeyEvent(event)
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyInput = keyInput or bit
            KeyEvent.ACTION_UP -> keyInput = keyInput and bit.inv()
        }
        view.hardwareInput = keyInput or axisInput
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!::menu.isInitialized || menu.isShowing) return super.onGenericMotionEvent(event)
        val joystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!joystick || event.action != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(event)
        val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var bits = 0
        if (x < -0.5f) bits = bits or Atari2600.LEFT
        if (x > 0.5f) bits = bits or Atari2600.RIGHT
        if (y < -0.5f) bits = bits or Atari2600.UP
        if (y > 0.5f) bits = bits or Atari2600.DOWN
        axisInput = bits
        view.hardwareInput = keyInput or axisInput
        return true
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"

        /** Lets the activity fade-in finish before the TV turns on. */
        private const val TV_ON_DELAY = 150L
    }
}
