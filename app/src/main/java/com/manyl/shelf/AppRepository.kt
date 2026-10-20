package com.manyl.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class TvApp(
    val key: String,
    val label: String,
    val intent: Intent,
    val tile: ImageBitmap,
    internal val updatedAt: Long,
    internal val info: ActivityInfo?,
    /** The colour the tile zooms into when the app opens: its banner's own background. */
    val color: Int = 0xFF000000.toInt(),
)

/** The grid's apps in order, the ones the user hid, and the tile that opens the hidden list. */
@Immutable
data class Library(val shown: List<TvApp>, val hidden: List<TvApp>, val hiddenTile: TvApp)

/** Settings can be moved but never hidden, so there's always a way into the TV's settings. */
val TvApp.isSettings get() = key == AppRepository.SETTINGS_KEY || key.startsWith(AppRepository.TV_SETTINGS)
val TvApp.isHiddenTile get() = key == AppRepository.HIDDEN_KEY
/** Apps the user installed. System apps (and their updates) stay: Android only rolls those back. */
val TvApp.canUninstall get() = info?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } == true

class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("shelf", Context.MODE_PRIVATE)
    private val cache = HashMap<String, TvApp>()
    val inputs = InputSource(context)
    private var order = emptyList<String>()

    /**
     * The grid, arranged the tvOS way: exactly the order you set, with anything new added at
     * the end. Nothing ever re-sorts itself. The very first order (before Shelf has saved one)
     * puts your most-used apps first, and is saved right away so it never moves on its own.
     * HDMI devices are tiles like apps, and keep their place while unplugged.
     */
    suspend fun load(): Library = withContext(Dispatchers.IO) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName && it.packageName !in SYSTEM_ONLY }
            .distinctBy { it.packageName }
            .mapNotNull { info -> runCatching { appFor(info) }.getOrNull() } +
            runCatching { inputs.devices() }.getOrDefault(emptyList())

        val known = manualOrder() ?: run {
            val counts = apps.associate { it.key to prefs.getInt(countKey(it.key), 0) }
            apps.sortedWith(
                compareByDescending<TvApp> { counts[it.key] }.thenBy { defaultRank(it.key) }.thenBy { it.label.lowercase() },
            ).map { it.key }
        }
        val position = known.withIndex().associate { (i, k) -> k to i }
        val installed = apps.associate { it.key to installTime(it) }
        val sorted = apps.sortedWith(compareBy<TvApp> { position[it.key] ?: Int.MAX_VALUE }.thenBy { installed[it.key] })
        order = known + sorted.map { it.key }.filter { it !in position }
        if (order != manualOrder()) prefs.edit { putString(ORDER, order.joinToString("\n")) }

        val hidden = hiddenKeys()
        val (gone, shown) = sorted.partition { it.key in hidden }
        Library(shown, gone.sortedBy { it.label.lowercase() }, hiddenTile())
    }

    private fun installTime(app: TvApp): Long =
        app.info?.packageName?.let { runCatching { pm.getPackageInfo(it, 0).firstInstallTime }.getOrNull() } ?: Long.MAX_VALUE

    /** Saves the grid exactly as arranged. */
    fun saveOrder(shownKeys: List<String>) {
        val shown = shownKeys.toSet()
        val rest = order.filter { it !in shown }
        order = shownKeys + rest
        prefs.edit { putString(ORDER, order.joinToString("\n")) }
    }

    fun setHidden(key: String, hidden: Boolean) {
        val keys = hiddenKeys().toMutableSet()
        if (hidden) keys += key else keys -= key
        prefs.edit {
            putStringSet(HIDDEN, keys)
            // A restored app comes back at the end of a hand-arranged grid.
            val manual = manualOrder()
            if (!hidden && manual != null) putString(ORDER, (manual - key + key).joinToString("\n"))
        }
    }

    private fun manualOrder(): List<String>? =
        prefs.getString(ORDER, null)?.split('\n')?.filter { it.isNotEmpty() }

    private fun hiddenKeys(): Set<String> = prefs.getStringSet(HIDDEN, null).orEmpty()

    fun recordLaunch(app: TvApp) {
        prefs.edit { putInt(countKey(app.key), prefs.getInt(countKey(app.key), 0) + 1) }
    }

    /** [app]'s own logo, cut from its banner and made legible on dark; null if it can't be cut cleanly. */
    suspend fun brandLogo(app: TvApp): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // A few logos are bundled at full resolution, because the app's own banner is too
            // small to show large without blurring.
            BUNDLED_LOGOS[app.info?.packageName]?.let { return@runCatching android.graphics.BitmapFactory.decodeResource(context.resources, it) }
            val banner = app.info?.let { bannerFor(it, DisplayMetrics.DENSITY_XXXHIGH) } ?: return@runCatching null
            val (w, h) = nativeSize(banner)
            Artwork.logoCutout(Artwork.render(banner, w, h))?.let(Artwork::legibleLogo)
        }.getOrNull()
    }

    /**
     * The Top Shelf for an app that publishes nothing: a glow of its brand colours with its
     * logo set like a title. The logo is cut from the sharpest banner the app ships and never
     * drawn bigger than that, so it stays crisp.
     */
    suspend fun brandHero(app: TvApp): Hero? = withContext(Dispatchers.IO) {
        runCatching {
            val info = app.info
            val tile = app.tile.asAndroidBitmap()
            var card = false
            val logo = when {
                info != null -> bannerFor(info, DisplayMetrics.DENSITY_XXXHIGH)?.let { banner ->
                    val (w, h) = nativeSize(banner)
                    val bitmap = Artwork.render(banner, w, h)
                    Artwork.logoCutout(bitmap)?.let(Artwork::legibleLogo) ?: Artwork.bannerCard(bitmap).also { card = true }
                }
                app.isHiddenTile -> Artwork.render(ContextCompat.getDrawable(context, R.drawable.ic_hidden)!!, 256, 256)
                else -> inputs.logoFor(app) // An HDMI device: its mark, else its name as the title.
            }
            val full = (if (app.isInput) inputs.productHero(app) else null) ?: Artwork.brandHero(tile)
            Hero.of(full, logo, card)
        }.getOrNull()
    }

    private fun appFor(info: ActivityInfo): TvApp {
        val key = "${info.packageName}/${info.name}"
        val updatedAt = runCatching { pm.getPackageInfo(info.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        cache[key]?.takeIf { it.updatedAt == updatedAt }?.let { return it }

        val banner = bannerFor(info, DisplayMetrics.DENSITY_XXHIGH)
        val tile = if (banner != null) {
            Artwork.render(banner, Artwork.TILE_W, Artwork.TILE_H).let { Artwork.reframe(it) ?: it }
        } else {
            Artwork.iconCard(iconFor(info), Artwork.TILE_W, Artwork.TILE_H)
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(ComponentName(info.packageName, info.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        return TvApp(
            key = key,
            label = info.loadLabel(pm).toString(),
            intent = intent,
            tile = Artwork.tileFace(tile).asImageBitmap(),
            updatedAt = updatedAt,
            color = Artwork.launchColor(tile),
            info = info,
        ).also { cache[key] = it }
    }

    private fun hiddenTile(): TvApp = cache.getOrPut(HIDDEN_KEY) {
        val tile = hiddenCard(Artwork.TILE_W, Artwork.TILE_H)
        TvApp(
            key = HIDDEN_KEY,
            label = context.getString(R.string.hidden_apps),
            intent = Intent(),
            tile = Artwork.tileFace(tile).asImageBitmap(),
            updatedAt = 0L,
            info = null,
        )
    }

    private fun hiddenCard(w: Int, h: Int) = Artwork.iconCard(
        icon = ContextCompat.getDrawable(context, R.drawable.ic_hidden)!!,
        w = w, h = h, iconFraction = 0.36f, base = 0xFF2C2C2E.toInt(),
    )

    /**
     * The size to render a banner at: its own pixels when it's a bitmap. (Android reports a
     * low-density bitmap at the requested density's size, which would only blur it bigger.)
     * Vectors render at their reported size.
     */
    private fun nativeSize(banner: Drawable): Pair<Int, Int> {
        (banner as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { return it.width to it.height }
        val w = banner.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(1280) ?: Artwork.TILE_W
        val h = banner.intrinsicHeight.takeIf { it > 0 }?.let { it * w / banner.intrinsicWidth } ?: Artwork.TILE_H
        return w to h
    }

    private fun bannerFor(info: ActivityInfo, density: Int): Drawable? {
        val res = pm.getResourcesForApplication(info.applicationInfo)
        val id = if (info.banner != 0) info.banner else info.applicationInfo.banner
        if (id == 0) return null
        return runCatching { res.getDrawableForDensity(id, density, null) }.getOrNull()
            ?: info.loadBanner(pm)
    }

    private fun iconFor(info: ActivityInfo): Drawable {
        val res = pm.getResourcesForApplication(info.applicationInfo)
        val id = info.iconResource
        return (if (id != 0) runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull() else null)
            ?: info.loadIcon(pm)
    }

    private fun countKey(key: String) = "launches:$key"

    private fun defaultRank(key: String): Int {
        if (key == SETTINGS_KEY || key.startsWith(TV_SETTINGS)) return Int.MAX_VALUE
        val i = FAVORITES.indexOf(key.substringBefore('/'))
        return if (i >= 0) i else FAVORITES.size
    }

    companion object {
        internal const val SETTINGS_KEY = "shelf:settings"
        internal const val TV_SETTINGS = "com.android.tv.settings"
        internal const val HIDDEN_KEY = "shelf:hidden"

        /**
         * Installed and needed, but not apps you open: Settings lives top right instead, and TCL's
         * TV player and picture menu are reached through inputs and the remote's own buttons.
         */
        private val BUNDLED_LOGOS = mapOf("com.google.android.youtube.tv" to R.drawable.logo_youtube)

        private val SYSTEM_ONLY = setOf(TV_SETTINGS, "com.tcl.tv", "com.tcl.suspension")
        private const val ORDER = "order"
        private const val HIDDEN = "hidden"

        /** Until usage takes over, well-known streaming apps lead and everything else is alphabetical. */
        private val FAVORITES = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.apple.atve.androidtv.appletv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "com.plexapp.android",
            "org.jellyfin.androidtv",
            "com.spotify.tv.android",
            "com.crunchyroll.crunchyroid",
            "tv.twitch.android.app",
            "com.google.android.youtube.tvmusic",
        )
    }
}

