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
import android.widget.Toast
import io.github.gativrek.retro2600.emu.Atari2600
import io.github.gativrek.retro2600.emu.Cartridge

/** Full-screen game: touch controls, plus any gamepad or keyboard that happens to be connected. */
class GameActivity : Activity() {

    private lateinit var console: Atari2600
    private lateinit var view: GameView
    private var loop: EmulationLoop? = null
    private var keyInput = 0
    private var axisInput = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        console = created.getOrThrow()
        view = GameView(this) { finish() }
        title = intent.getStringExtra(EXTRA_NAME)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        hideSystemBars()
        loop = EmulationLoop(console, view).also { it.start() }
    }

    override fun onPause() {
        loop?.shutdown()
        loop = null
        super.onPause()
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
        val bit = when (event.keyCode) {
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
    }
}
