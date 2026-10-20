package com.manyl.shelf

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)
    private val content = ContentSource(app)
    private val meta = MetaSource(app)
    private val trending = TrendingSource(app)
    private val music = MusicChart(app)
    private val games = GameCovers(app)
    private val loader = ArtLoader(app)

    var apps by mutableStateOf<List<TvApp>>(emptyList())
        private set

    /** Apps the user hid from the grid, and the tile at the end of the grid that lists them. */
    var hidden by mutableStateOf<List<TvApp>>(emptyList())
        private set
    var hiddenTile by mutableStateOf<TvApp?>(null)
        private set

    /** What each top-row app's Top Shelf shows, keyed by [TvApp.key]. */
    val shelves = mutableStateMapOf<String, ShelfItem>()

    /** Rendered art per [ShelfItem.id]. Photos are kept to a small working set. */
    val art = mutableStateMapOf<String, Hero>()
    private val recentArt = ArrayDeque<String>()
    private val loadingArt = HashSet<String>()
    private val shelfLoadedAt = HashMap<String, Long>()

    /** Bumped when you come back from an app Shelf opened: its zoom plays in reverse. */
    var returnSignal by mutableIntStateOf(0)
        private set

    fun returned() { returnSignal++ }

    /** Bumped when Home is pressed while Shelf is already showing: jump back to the first app. */
    var homeSignal by mutableIntStateOf(0)
        private set

    /**
     * Bumped the moment Home is pressed on Shelf. Mid-zoom it calls off the open; otherwise the
     * home screen goes back to the first app ([goHome]).
     */
    var homePress by mutableIntStateOf(0)
        private set

    fun pressedHome() { homePress++ }

    private var loadJob: Job? = null
    private var heroJob: Job? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val inputCallback = repo.inputs.register { refresh() }

    init {
        refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun refresh(thenGoHome: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val library = repo.load()
            apps = library.shown
            hidden = library.hidden
            hiddenTile = library.hiddenTile
            if (thenGoHome) homeSignal++
            loadHeroes()
        }
    }

    private fun loadHeroes() {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            val top = (apps + listOfNotNull(hiddenTile?.takeIf { hidden.isNotEmpty() })).take(TOP_ROW)
            top.forEach { loadShelf(it) }
        }
    }

    /**
     * Fills [app]'s Top Shelf with one thing: what you're partway through, else what the app
     * features, identified where possible so it shows the show's own logo over clean, sharp
     * art. Apps that publish nothing get their brand treatment. Re-read at most every couple of
     * minutes, since watching something changes what "continue watching" should show.
     */
    suspend fun loadShelf(app: TvApp, maxAgeMs: Long = 120_000) {
        val now = System.currentTimeMillis()
        if (now - (shelfLoadedAt[app.key] ?: 0L) < maxAgeMs && app.key in shelves) return
        shelfLoadedAt[app.key] = now
        if (app.key !in shelves) shelves[app.key] = brandItem(app)
        val pkg = app.info?.packageName
        val item = when {
            pkg == null -> null
            // Video apps: a wall of what they recommend, rather than one thumbnail.
            pkg in VIDEO_APPS -> content.items(pkg, limit = 14).mapNotNull { it.imageUri }.takeIf { it.size >= 4 }?.let { thumbs ->
                ShelfItem("wall:video:${thumbs.hashCode()}", title = app.label, subtitle = null, imageUri = null, wall = thumbs)
            }
            else -> content.first(pkg)?.let { meta.enrich(it) } ?: trending.forPackage(pkg, app.label)
        }
        shelves[app.key] = item ?: brandItem(app)
        ensureArt(app, shelves.getValue(app.key))
    }

    /** Renders [item]'s art if it isn't ready. An item whose image won't load falls back to the brand. */
    fun ensureArt(app: TvApp, item: ShelfItem) {
        if (item.id in art || !loadingArt.add(item.id)) return
        viewModelScope.launch {
            val hero = if (item.id.startsWith("wall:")) {
                // A wall can take a while the first time (its pictures download one by one), so
                // show the quick version right away (the console shot, or the app's logo) and
                // crossfade to the wall once it's ready.
                val quick = launch { repo.brandHero(app)?.let { if (item.id !in art) art[item.id] = it } }
                wallHero(app, item).also { quick.cancel() } ?: art[item.id] ?: repo.brandHero(app)
            } else if (item.isBrand) {
                repo.brandHero(app)
            } else {
                loader.loadBest(item.imageUri!!, maxWidth = 1920)?.let { src ->
                    // The show's own logo, trimmed so its left edge lines up with the caption.
                    val logo = item.logoUri?.let { loader.load(it, maxWidth = 1000) }?.let(Artwork::trim)?.let(Artwork::legibleLogo)
                    withContext(Dispatchers.Default) { Hero.of(Artwork.heroFromPhoto(Artwork.unletterbox(src), titleShade = !item.bare), logo) }
                }
            }
            loadingArt.remove(item.id)
            when {
                hero == null && !item.isBrand -> {
                    shelves[app.key] = brandItem(app)
                    ensureArt(app, brandItem(app))
                }
                hero != null -> {
                    art[item.id] = hero
                    if (!item.isBrand) {
                        recentArt.remove(item.id)
                        recentArt.addLast(item.id)
                        while (recentArt.size > MAX_PHOTOS) art.remove(recentArt.removeFirst())
                    }
                }
            }
        }
    }

    /** Coming back to Home: what's "continue watching" may have changed. */
    fun refreshShelves(maxAgeMs: Long = 30_000) {
        viewModelScope.launch {
            (apps + listOfNotNull(hiddenTile)).take(TOP_ROW).forEach { loadShelf(it, maxAgeMs) }
        }
    }

    private fun brandItem(app: TvApp) = when (app.info?.packageName) {
        // Apps with a world of content but nothing published get a wall of it instead of a logo.
        MUSIC -> ShelfItem("wall:music", title = app.label, subtitle = null, imageUri = null)
        STORE -> ShelfItem("wall:apps", title = app.label, subtitle = null, imageUri = null)
        else -> repo.inputs.platformOf(app)?.takeIf { app.isInput }
            ?.let { ShelfItem("wall:$it", title = app.label, subtitle = null, imageUri = null) }
            ?: ShelfItem("brand:${app.key}", title = app.label, subtitle = null, imageUri = null)
    }

    /**
     * A wall under the app's logo: album covers (YouTube Music), your own apps (Play Store),
     * game box art (PS5, Switch) or recommended videos (YouTube).
     */
    private suspend fun wallHero(app: TvApp, item: ShelfItem): Hero? {
        val (images, aspect) = when {
            item.id == "wall:music" -> music.covers().mapNotNull { loader.load(it, maxWidth = 400) } to 1f
            item.id == "wall:apps" -> apps.filter { it.key != app.key && !it.isHiddenTile }.map { it.tile.asAndroidBitmap() } to 5f / 3f
            item.id == "wall:ps5" || item.id == "wall:switch" ->
                games.covers(item.id.removePrefix("wall:")).mapNotNull { loader.load(it, maxWidth = 500) } to 0.75f
            else -> item.wall.mapNotNull { loader.loadBest(it, maxWidth = 640) } to 16f / 9f
        }
        if (images.size < 4) return null
        val logo = if (app.isInput) repo.inputs.logoFor(app) else repo.brandLogo(app)
        return withContext(Dispatchers.Default) {
            val (full, plain) = Artwork.wall(images, aspect)
            Hero.of(full, logo, browse = plain)
        }
    }

    /** The user arranged the grid by hand: show it right away and remember it. */
    fun setOrder(arranged: List<TvApp>) {
        val keys = arranged.mapTo(HashSet()) { it.key }
        val current = apps.mapTo(HashSet()) { it.key }
        // Apps installed or removed while the user was arranging still land correctly.
        apps = arranged.filter { it.key in current } + apps.filter { it.key !in keys }
        repo.saveOrder(apps.map { it.key })
        loadHeroes()
    }

    fun hide(app: TvApp) {
        if (app.isSettings || app.isHiddenTile) return
        repo.setHidden(app.key, true)
        apps = apps.filter { it.key != app.key }
        hidden = (hidden.filter { it.key != app.key } + app).sortedBy { it.label.lowercase() }
        loadHeroes()
    }

    fun unhide(app: TvApp) {
        repo.setHidden(app.key, false)
        hidden = hidden.filter { it.key != app.key }
        refresh()
    }

    fun ports(): List<Int> = repo.inputs.ports().keys.sorted()
    fun portOf(app: TvApp): Int? = repo.inputs.portOf(app)

    /** Pins an HDMI device to the port it's really on, and rebuilds its tile's link. */
    fun setPort(app: TvApp, port: Int) {
        repo.inputs.setPort(app, port)
        refresh()
    }

    /** Home pressed on Home: back to the first app (the order itself never changes on its own). */
    fun goHome() = refresh(thenGoHome = true)

    fun recordLaunch(app: TvApp) = repo.recordLaunch(app)

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(packageReceiver)
        repo.inputs.unregister(inputCallback)
    }

    companion object {
        const val TOP_ROW = 6
        private const val MAX_PHOTOS = 10

        /** Their items are videos, not shows: keep their own thumbnails and titles. */
        private const val MUSIC = "com.google.android.youtube.tvmusic"
        private const val STORE = "com.android.vending"
        private val VIDEO_APPS = setOf("com.google.android.youtube.tv", "com.google.android.youtube.tvmusic")
    }
}
