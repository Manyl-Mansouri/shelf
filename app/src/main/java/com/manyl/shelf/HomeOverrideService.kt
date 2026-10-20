package com.manyl.shelf

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import androidx.annotation.RequiresApi

/**
 * Shelf's helper for the moments between Shelf and an app, and the fallback for Google TV
 * setups that won't let you pick a different home app.
 *
 * - **Leaving an app:** as you press Home, it takes a picture of the screen first (see
 *   [AppSnapshots]), so the home screen can shrink that picture back into the app's tile.
 * - **Opening an app:** it lays the app's last picture (or its dark shade) over the screen,
 *   above the app, and lifts it only once the app has drawn something real. Apps like YouTube
 *   redraw from black on every reopen; you never see that.
 * - **Stock launcher:** if the stock home screen comes up anyway, it hands over to Shelf.
 *
 * Nothing on screen is read beyond which app is in front and whether its window is still blank,
 * and nothing leaves the TV.
 */
class HomeOverrideService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastBounce = 0L
    private var cover: Cover? = null
    private var launched: Launched? = null
    private val watchTask = Runnable { watch() }
    /** A Home press we took is still down: keep its release from reaching Android as a second Home. */
    private var homeHeld = false
    private var filterWanted = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        filterHome(!shelfInFront)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        uncover()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() !in STOCK_LAUNCHERS) return
        // The stock launcher fires a burst of window events as it comes up; bounce once.
        val now = SystemClock.uptimeMillis()
        if (now - lastBounce < 800) return
        lastBounce = now
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .putExtra(EXTRA_REDIRECT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    override fun onInterrupt() = Unit

    /** Home is only held back (for the picture) while an app is in front, never on Shelf itself. */
    private fun filterHome(on: Boolean) {
        filterWanted = on
        // Shelf is often back before the button is released; stop filtering only after it is.
        if (!on && homeHeld) return
        applyFilter(on)
    }

    private fun applyFilter(on: Boolean) {
        val info = serviceInfo ?: return
        val flags = if (on) info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        else info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        if (flags != info.flags) serviceInfo = info.apply { this.flags = flags }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            homeHeld = true
            // Still covered: the app hasn't drawn anything worth a picture. Go home under the
            // cover; Shelf, already showing the same picture, lifts it once it has the screen.
            if (cover != null) goHomeNow() else snapThenGoHome()
        } else if (event.action == KeyEvent.ACTION_UP && homeHeld) {
            homeHeld = false
            if (!filterWanted) applyFilter(false)
        }
        return true
    }

    private fun goHomeNow() {
        main.removeCallbacks(watchTask)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun snapThenGoHome() {
        var gone = false
        val goHome = Runnable {
            if (gone) return@Runnable
            gone = true
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        val pkg = frontApp()?.root?.packageName?.toString()
        if (pkg == null || pkg == packageName) return goHome.run()
        // Home never waits long on a slow picture.
        main.postDelayed(goHome, 220)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = result.hardwareBuffer.use { Bitmap.wrapHardwareBuffer(it, result.colorSpace) }
                if (!gone && shot != null) {
                    // The tile that led here shares this app's picture from now on (see [Launched]).
                    val tile = launched?.takeIf { it.landed == pkg }?.tile
                    AppSnapshots.put(pkg, shot, from = tile ?: pkg)
                    if (tile != null) AppSnapshots.link(tile, pkg)
                }
                main.removeCallbacks(goHome)
                goHome.run()
            }

            override fun onFailure(errorCode: Int) {
                main.removeCallbacks(goHome)
                goHome.run()
            }
        })
    }

    private fun frontApp(): AccessibilityWindowInfo? =
        runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } }.getOrNull()

    /** [blindMs]: lift after this long instead of watching (for screens that can't be looked at). */
    private class Cover(val pkg: String, val view: View, val since: Long, val blindMs: Long) {
        var appSeenAt = 0L
    }

    /**
     * The tile Shelf last opened, and the app that actually came up for it. They differ for
     * apps that hand straight off to another (YouTube Music on Google TV opens YouTube's music
     * section), and the tile should still remember where you left off.
     */
    private class Launched(val tile: String) {
        var landed: String? = null
    }


    /**
     * Covers the screen with [shot] (else [shade]) until [pkg] has drawn, then fades it away.
     * [onShown] runs once the cover is on screen, which is when the app should be started.
     */
    internal fun cover(pkg: String, shot: Bitmap?, shade: Int, blindMs: Long = 0, onShown: () -> Unit) {
        uncover()
        val view = ImageView(this).apply {
            if (shot != null) setImageBitmap(shot) else setImageDrawable(ColorDrawable(shade))
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // Translucent, so fading the cover reveals the app rather than black.
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }
        val wm = getSystemService(WindowManager::class.java)
        try {
            wm.addView(view, params)
        } catch (e: RuntimeException) {
            return onShown()
        }
        cover = Cover(pkg, view, SystemClock.uptimeMillis(), blindMs)
        launched = Launched(pkg)
        // Start the app once the cover is really on screen, so the app only ever appears under it.
        var started = false
        val start = Runnable { if (!started) { started = true; onShown() } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.viewTreeObserver.registerFrameCommitCallback { main.post(start) }
        }
        main.postDelayed(start, 120)
        main.postDelayed(watchTask, 160)
    }

    /**
     * Lifts the cover the moment the app that came up shows something other than black. That's
     * whichever app window is in front (other than Shelf), which isn't always the tile's own app.
     */
    private fun watch() {
        val c = cover ?: return
        val now = SystemClock.uptimeMillis()
        if (now - c.since > MAX_COVER_MS) return reveal()
        if (c.blindMs > 0) {
            if (now - c.since >= c.blindMs) reveal() else main.postDelayed(watchTask, 40)
            return
        }
        val window = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != packageName }
        }.getOrNull()
        if (window == null) return again()
        launched?.takeIf { it.tile == c.pkg }?.landed = runCatching { window.root?.packageName?.toString() }.getOrNull()
        if (c.appSeenAt == 0L) c.appSeenAt = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return byTime(c, now)
        takeScreenshotOfWindow(window.id, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val frame = result.hardwareBuffer.use { Bitmap.wrapHardwareBuffer(it, result.colorSpace) }
                if (cover !== c) return
                if (frame != null && !Artwork.isDark(frame)) reveal() else again()
            }

            override fun onFailure(errorCode: Int) {
                if (cover !== c) return
                // Asked too soon after the last one: just ask again. A protected window
                // (some streaming apps) can't be looked at, so fall back to a fixed wait.
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) again() else byTime(c, SystemClock.uptimeMillis())
            }
        })
    }

    private fun again() {
        main.postDelayed(watchTask, 90)
    }

    private fun byTime(c: Cover, now: Long) {
        if (now - c.appSeenAt >= BLIND_WAIT_MS) reveal() else main.postDelayed(watchTask, 120)
    }

    private fun reveal() {
        val c = cover ?: return
        cover = null
        launched?.let { l -> l.landed?.let { AppSnapshots.link(l.tile, it) } }
        main.removeCallbacks(watchTask)
        // Timed against the system's animation speed (TCL halves it), so it really takes REVEAL_MS.
        val scale = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale == 0f) return remove(c.view)
        c.view.animate().alpha(0f).setDuration((REVEAL_MS / scale).toLong()).setInterpolator(Dissolve)
            .withEndAction { remove(c.view) }.start()
    }

    internal fun uncover() {
        val c = cover ?: return
        cover = null
        main.removeCallbacks(watchTask)
        remove(c.view)
    }

    private fun remove(view: View) {
        runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
    }

    companion object {
        /** Marks a bounce from the stock launcher, which is not a Home press on Shelf. */
        const val EXTRA_REDIRECT = "com.manyl.shelf.REDIRECT"
        private val STOCK_LAUNCHERS = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")
        /** Never keep the screen covered longer than this, whatever the app is doing. */
        private const val MAX_COVER_MS = 2600L
        /** For a window that can't be looked at: about how long a reopening app takes to redraw. */
        private const val BLIND_WAIT_MS = 700L

        /**
         * How long an HDMI input takes to show a picture, measured on this TV (TCL's player
         * reported video ~1.13 s after the switch for a console that was awake). Its screen
         * can't be watched, so its cover lifts on time.
         */
        internal const val HDMI_WAIT_MS = 1250L
        /** The cover's fade into the app, in real time. */
        private const val REVEAL_MS = 700f

        /** Eases in and lands softly, so the app seems to surface rather than switch on. */
        private val Dissolve = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

        /** The running service, when it's switched on in Accessibility settings. */
        internal var instance: HomeOverrideService? = null
            private set

        internal var shelfInFront = false
            set(value) {
                field = value
                instance?.filterHome(!value)
            }
    }
}
