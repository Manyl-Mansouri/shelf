package com.manyl.shelf

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer

class MainActivity : ComponentActivity() {
    private val vm by viewModels<HomeViewModel>()
    private var onScreen = false
    /** True from coming back on screen until fully resumed: a Home press in that window came from inside an app. */
    private var returning = false
    private var launched = false

    @OptIn(InternalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(this)
        // Shelf's motion is tuned at normal speed. TCL ships the TV with every animation at half
        // speed, which turns springs into jolts, so Shelf runs on its own clock. If animations
        // are switched off entirely (an accessibility choice), Shelf respects that.
        val systemScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (systemScale != 0f) {
            view.setParentCompositionContext(view.createLifecycleAwareWindowRecomposer(DesignedMotion, lifecycle))
        }
        view.setContent { HomeScreen(vm = vm, onLaunch = ::launch, onSettings = ::openSettings) }
        setContentView(view)
        // Home always paints an opaque picture, so the window's own black fill underneath is a
        // wasted full-screen pass on every frame. (The theme's black still shows while starting.)
        window.setBackgroundDrawable(null)
        // Lets the Top Shelf show what apps publish for the home screen. Asked once; until it's
        // granted (or if it's declined) every app gets its brand treatment instead.
        if (checkSelfPermission(ContentSource.READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(ContentSource.READ_TV_LISTINGS), 1)
        }
    }

    private object DesignedMotion : MotionDurationScale {
        override val scaleFactor = 1f
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) vm.refreshShelves(maxAgeMs = 0)
    }

    override fun onStart() {
        super.onStart()
        onScreen = true
        returning = true
        vm.refreshShelves()
    }

    override fun onResume() {
        super.onResume()
        returning = false
        HomeOverrideService.shelfInFront = true
        // Back from an app: the screen it zoomed into shrinks back into its tile. Checked here,
        // not in onStart: leave an app within a moment of opening it and Shelf was never
        // stopped, so it never "starts" again, only resumes.
        if (launched) {
            launched = false
            vm.returned()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Shelf has the screen again, showing the same picture as any cover the helper still
        // has up (Home pressed before the app had drawn). Lift it so the shrink back shows.
        if (hasFocus) HomeOverrideService.instance?.uncover()
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
    }

    override fun onPause() {
        super.onPause()
        // An app is coming up: from here Home goes through the helper, which pictures the app.
        HomeOverrideService.shelfInFront = false
    }

    /**
     * Like tvOS: Home from inside an app lands back on that app's tile;
     * Home while already on the home screen jumps to the first app.
     *
     * Coming back from an app, Android delivers the Home press after Shelf has already started
     * again, so "on screen" alone can't tell the two apart: a press that arrives before Shelf
     * has resumed came from inside the app.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val homePress = intent.hasCategory(Intent.CATEGORY_HOME) &&
            !intent.getBooleanExtra(HomeOverrideService.EXTRA_REDIRECT, false)
        if (onScreen && !returning && !launched && homePress) vm.pressedHome()
    }

    private fun openSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Opens [app]. Shelf has already zoomed its tile to fill the screen with the app's last
     * picture (or its dark shade). With Shelf's accessibility helper on, that picture is held
     * over the app until the app has drawn, the way tvOS does it; without it, the app fades in
     * over the zoom after a short hold. Returns false if it couldn't be opened.
     */
    private fun launch(app: TvApp): Boolean {
        val pkg = app.packageName
        val helper = HomeOverrideService.instance
        if (helper != null && app.isInput) {
            // An HDMI device: its games wall stands in for its screen, on a timer.
            helper.cover(app.key, AppSnapshots.of(app)?.bitmap, Artwork.deepShade(app.color), HomeOverrideService.HDMI_WAIT_MS) {
                if (!start(app, ActivityOptions.makeCustomAnimation(this, 0, 0))) {
                    helper.uncover()
                    vm.returned()
                }
            }
            return true
        }
        if (helper == null || pkg == null) {
            return start(app, ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.hold))
        }
        // Left from this tile into another app (YouTube Music into YouTube)? Bring that app back
        // as it was, rather than having the tile send it somewhere it already is.
        val intent = AppSnapshots.resumeInto(pkg)?.let { into ->
            (packageManager.getLeanbackLaunchIntentForPackage(into) ?: packageManager.getLaunchIntentForPackage(into))
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: app.intent
        // The app appears under the cover all at once; the cover does the fading.
        helper.cover(pkg, AppSnapshots.of(pkg)?.bitmap, Artwork.deepShade(app.color)) {
            if (!start(app, ActivityOptions.makeCustomAnimation(this, 0, 0), intent)) {
                helper.uncover()
                vm.returned()
            }
        }
        return true
    }

    private fun start(app: TvApp, options: ActivityOptions, intent: Intent = app.intent): Boolean = try {
        startActivity(intent, options.toBundle())
        vm.recordLaunch(app)
        launched = true
        true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "${app.label} can't be opened", Toast.LENGTH_SHORT).show()
        vm.refresh()
        false
    } catch (e: SecurityException) {
        Toast.makeText(this, "${app.label} can't be opened", Toast.LENGTH_SHORT).show()
        false
    }
}
