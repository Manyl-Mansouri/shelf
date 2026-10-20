package com.manyl.shelf

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * What each app looked like when you last left it, the way tvOS remembers it: taken as you press
 * Home (see [HomeOverrideService]). Opening the app again zooms into this picture and holds it over
 * the app until the app has really drawn, so a reopen never shows the app's own black first frame.
 *
 * Only main-thread access; the map is Compose state so a fresh picture reaches a running zoom.
 */
internal object AppSnapshots {
    /** [from]: the tile whose app you were in when this was taken (see [resumeInto]). */
    class Shot(val bitmap: Bitmap, val image: ImageBitmap, val at: Long, val from: String)

    private val shots = mutableStateMapOf<String, Shot>()

    /** A picture older than this is likely of an app Android has since closed, which reopens fresh. */
    private const val FRESH_MS = 30 * 60_000L
    private const val KEEP = 4

    fun put(pkg: String, bitmap: Bitmap, from: String = pkg) {
        // A protected screen (a DRM video) comes back black: not worth showing.
        if (Artwork.isDark(bitmap)) {
            shots.remove(pkg)
            return
        }
        shots[pkg] = Shot(bitmap, bitmap.asImageBitmap(), SystemClock.elapsedRealtime(), from)
        // Each one is a full screen of memory; keep the few most recent.
        if (shots.size > KEEP) shots.entries.minByOrNull { it.value.at }?.let { shots.remove(it.key) }
    }

    /**
     * Tiles whose app hands straight off to another app (YouTube Music on Google TV opens
     * YouTube's music section) share that app's picture: it's the same screen either way.
     */
    private val opensInto = HashMap<String, String>()

    fun link(tile: String, app: String) {
        if (tile == app) opensInto.remove(tile) else opensInto[tile] = app
    }

    fun of(pkg: String?): Shot? {
        val shot = shots[opensInto[pkg] ?: pkg ?: return null] ?: return null
        return shot.takeIf { SystemClock.elapsedRealtime() - it.at < FRESH_MS }
    }

    /**
     * The app to bring back instead of [tile]'s own, when you left that app from this same tile.
     * YouTube Music's tile hands YouTube a link to its music page, and YouTube reloads the page
     * for it, blanking the screen; if you left YouTube on that page, just bring YouTube back.
     */
    fun resumeInto(tile: String): String? = opensInto[tile]?.takeIf { of(it)?.from == tile }

    /** Pictures for HDMI devices, whose screens can't be captured: their tile, full screen. Set while loading apps, off the main thread. */
    private val standIns = java.util.concurrent.ConcurrentHashMap<String, Shot>()

    fun standIn(key: String, bitmap: Bitmap) {
        standIns[key] = Shot(bitmap, bitmap.asImageBitmap(), SystemClock.elapsedRealtime(), key)
    }

    fun of(app: TvApp): Shot? = if (app.isInput) standIns[app.key] else of(app.packageName)
}

internal val TvApp.packageName: String? get() = intent.component?.packageName ?: intent.`package` ?: info?.packageName
