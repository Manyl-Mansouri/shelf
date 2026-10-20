package com.manyl.shelf

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One thing the Top Shelf can show for an app: something it published to Android TV's
 * "Watch Next" or preview rows, or (when it publishes nothing) the app's own brand treatment.
 */
@Immutable
data class ShelfItem(
    /** Stable id: the image URI for content, `brand:<app key>` for the brand treatment. */
    val id: String,
    val title: String?,
    val subtitle: String?,
    val imageUri: String?,
    /** The show's own title treatment (transparent PNG), when it could be identified. */
    val logoUri: String? = null,
    /** True for a series or episode, false for a film, null when the app didn't say. */
    val series: Boolean? = null,
    /** Shown exactly as the app's picture, with no words over it (video thumbnails carry their own). */
    val bare: Boolean = false,
    /** For a wall: the pictures on it (video thumbnails, for instance). */
    val wall: List<String> = emptyList(),
) {
    val isBrand get() = imageUri == null
}

/**
 * Rendered Top Shelf art: the full-screen image, and two tiny blurred copies for the glass
 * (lifted for the dock over art, darkened for the dock and backdrop while browsing), plus the
 * title logo. The tints are baked so drawing them needs no colour filter.
 */
@Immutable
data class Hero(
    val full: ImageBitmap,
    val frost: ImageBitmap,
    val wash: ImageBitmap,
    val deep: ImageBitmap,
    val logo: ImageBitmap? = null,
    val logoIsCard: Boolean = false,
) {
    companion object {
        /** Builds a hero from full-screen art, with its glass baked and everything sent to the GPU early. */
        fun of(
            full: android.graphics.Bitmap,
            logo: android.graphics.Bitmap? = null,
            logoIsCard: Boolean = false,
            /** What the browse backdrop is blurred from, when it shouldn't carry [full]'s shading. */
            browse: android.graphics.Bitmap = full,
        ): Hero {
            val (frost, wash, deep) = Artwork.glass(full, browse)
            fun up(b: android.graphics.Bitmap) = Artwork.ready(b).asImageBitmap()
            return Hero(up(full), up(frost), up(wash), up(deep), logo?.let(::up), logoIsCard)
        }
    }
}

/**
 * Reads what apps publish for the launcher: Watch Next ("continue watching") first, then their
 * preview channels. Needs READ_TV_LISTINGS, a runtime permission granted once.
 */
class ContentSource(private val context: Context) {
    val granted get() =
        ContextCompat.checkSelfPermission(context, READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    /** The one thing to show for [pkg]: what you're partway through, else its top pick. */
    suspend fun first(pkg: String): ShelfItem? = items(pkg, limit = 1).firstOrNull()

    suspend fun items(pkg: String, limit: Int = 6): List<ShelfItem> = withContext(Dispatchers.IO) {
        if (!granted) return@withContext emptyList()
        val next = runCatching { watchNext(pkg) }.getOrDefault(emptyList())
        val preview = runCatching { previews(pkg) }.getOrDefault(emptyList())
        (next + preview).distinctBy { it.id }.take(limit)
    }

    private fun watchNext(pkg: String): List<ShelfItem> =
        query(WATCH_NEXT, pkg) { c ->
            val engaged = c.long("last_engagement_time_utc_millis") ?: 0L
            item(c, label = context.getString(R.string.continue_watching))?.let { it to engaged }
        }.sortedByDescending { it.second }.map { it.first }

    private fun previews(pkg: String): List<ShelfItem> {
        // Rows the home screen was asked to show ("browsable") come first. Google TV's own home
        // doesn't show app rows, so on Google TV that flag is often never set; use them all.
        val channels = query(CHANNELS, pkg) { c ->
            val id = c.long("_id") ?: return@query null
            id to (c.long("browsable") == 1L)
        }.toMap()
        if (channels.isEmpty()) return emptyList()
        return query(PREVIEW, pkg) { c ->
            val channel = c.long("channel_id") ?: return@query null
            if (channel !in channels) return@query null
            val weight = c.long("weight") ?: 0L
            item(c, label = null)?.let { Triple(it, channels[channel] == true, weight) }
        }.sortedWith(compareByDescending<Triple<ShelfItem, Boolean, Long>> { it.second }.thenByDescending { it.third }).map { it.first }
    }

    private fun item(c: Cursor, label: String?): ShelfItem? {
        val image = landscapeArt(c) ?: return null
        val title = c.string("title") ?: return null
        val season = c.string("season_display_number")
        val episode = c.string("episode_display_number")
        val where = season?.let { "S$it" }?.plus(episode?.let { ", E$it" }.orEmpty())
        // TvContract program types: 0 movie; 1 series, 2 season, 3 episode.
        val series = when (c.long("type")) {
            0L -> false
            1L, 2L, 3L -> true
            else -> if (season != null || episode != null) true else null
        }
        return ShelfItem(
            id = image,
            title = title,
            subtitle = listOfNotNull(label, where).joinToString(" · ").ifEmpty { null },
            imageUri = image,
            series = series,
        )
    }

    /** Prefer wide art (16:9 or 3:2); fall back to whatever the app provides. */
    private fun landscapeArt(c: Cursor): String? {
        val poster = c.string("poster_art_uri")
        val thumb = c.string("thumbnail_uri")
        fun wide(ratio: Long?) = ratio == null || ratio == 0L || ratio == 1L
        return when {
            poster != null && wide(c.long("poster_art_aspect_ratio")) -> poster
            thumb != null && wide(c.long("thumbnail_aspect_ratio")) -> thumb
            else -> poster ?: thumb
        }
    }

    /**
     * Rows [pkg] published. The TV provider refuses selections and sort orders from apps
     * without system privileges, so every row is read and filtered here.
     */
    private fun <T> query(uri: Uri, pkg: String, row: (Cursor) -> T?): List<T> =
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) if (c.string("package_name") == pkg) row(c)?.let(::add) }
        }.orEmpty()

    private fun Cursor.string(col: String) = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getString)?.ifBlank { null }
    private fun Cursor.long(col: String) = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    companion object {
        /** Not in the public SDK constants, but a regular runtime permission. */
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private val CHANNELS = Uri.parse("content://android.media.tv/channel")
        private val PREVIEW = Uri.parse("content://android.media.tv/preview_program")
        private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")
    }
}

/**
 * Identifies a show or film by its title using Cinemeta (the free catalogue Stremio-style apps
 * use) and returns its artwork from Metahub: a clean 1920×1080 background and
 * the show's own logo. Answers are remembered, including "no match".
 */
class MetaSource(context: Context) {
    private val prefs = context.getSharedPreferences("meta", Context.MODE_PRIVATE)

    suspend fun enrich(item: ShelfItem): ShelfItem = withContext(Dispatchers.IO) {
        val title = item.title ?: return@withContext item
        val key = "${item.series}|${norm(title)}"
        val id = prefs.getString(key, null) ?: (runCatching { lookup(title, item.series) }.getOrNull() ?: "-").also {
            prefs.edit().putString(key, it).apply()
        }
        if (id == "-") item else item.copy(
            imageUri = "https://images.metahub.space/background/medium/$id/img",
            logoUri = "https://images.metahub.space/logo/medium/$id/img",
        )
    }

    /** Exact title match only, so a YouTube video called "Dune" never borrows the film's art. */
    private fun lookup(title: String, series: Boolean?): String? {
        val types = when (series) { true -> listOf("series"); false -> listOf("movie"); null -> listOf("series", "movie") }
        val q = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        return types.firstNotNullOfOrNull { type ->
            val json = (URL("https://v3-cinemeta.strem.io/catalog/$type/top/search=$q.json").openConnection() as HttpURLConnection).run {
                connectTimeout = 5000; readTimeout = 8000
                inputStream.bufferedReader().use { it.readText() }
            }
            val metas = org.json.JSONObject(json).optJSONArray("metas") ?: return@firstNotNullOfOrNull null
            (0 until metas.length()).map { metas.getJSONObject(it) }
                .filter { norm(it.optString("name")) == norm(title) && it.optString("id").startsWith("tt") }
                // Several with the same name: the most recent is almost always the one on TV now.
                .maxByOrNull { it.optString("releaseInfo").take(4).toIntOrNull() ?: 0 }
                ?.optString("id")
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}

/** How Shelf introduces itself to web services. */
internal const val USER_AGENT = "Shelf/1.0 (personal Android TV launcher; Android)"

/**
 * For streaming apps that publish nothing to the home screen: what's popular on that service
 * right now, from the public "Streaming Catalogs" Stremio add-on (no account or key). Each
 * title comes with its IMDb id, so the art is the same clean background and logo as above.
 * Catalogues are re-read every six hours.
 */
class TrendingSource(private val context: Context) {
    private val prefs = context.getSharedPreferences("trending", Context.MODE_PRIVATE)

    suspend fun forPackage(pkg: String, appLabel: String): ShelfItem? = withContext(Dispatchers.IO) {
        val catalog = CATALOGS[pkg] ?: return@withContext null
        runCatching {
            // Series first (what people are watching week to week), then films.
            listOf("series", "movie").firstNotNullOfOrNull { type -> top(type, catalog) }?.let { (id, name) ->
                ShelfItem(
                    id = "https://images.metahub.space/background/medium/$id/img",
                    title = name,
                    subtitle = context.getString(R.string.popular_on, appLabel),
                    imageUri = "https://images.metahub.space/background/medium/$id/img",
                    logoUri = "https://images.metahub.space/logo/medium/$id/img",
                )
            }
        }.getOrNull()
    }

    private fun top(type: String, catalog: String): Pair<String, String>? {
        val key = "$type/$catalog"
        val fresh = System.currentTimeMillis() - prefs.getLong("$key:at", 0) < 6 * 3600_000L
        val json = prefs.getString(key, null)?.takeIf { fresh } ?: runCatching {
            (URL("$BASE/catalog/$key.json").openConnection() as HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 10000
                inputStream.bufferedReader().use { it.readText() }
            }.also { prefs.edit().putString(key, it).putLong("$key:at", System.currentTimeMillis()).apply() }
        }.getOrNull() ?: prefs.getString(key, null) ?: return null
        val metas = org.json.JSONObject(json).optJSONArray("metas") ?: return null
        val first = (0 until metas.length()).map { metas.getJSONObject(it) }.firstOrNull { it.optString("id").startsWith("tt") } ?: return null
        return first.getString("id") to first.optString("name")
    }

    companion object {
        private const val BASE = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club"
        private val CATALOGS = mapOf(
            "com.netflix.ninja" to "nfx",
            "com.disney.disneyplus" to "dnp",
            "com.amazon.amazonvideo.livingroom" to "amp",
            "com.apple.atve.androidtv.appletv" to "atp",
            "com.wbd.stream" to "hbm",
        )
    }
}

/**
 * This week's most-played albums in Canada, from Apple's public charts feed (no account or
 * key), re-read once a day: cover art for the YouTube Music wall.
 */
class MusicChart(context: Context) {
    private val prefs = context.getSharedPreferences("charts", Context.MODE_PRIVATE)

    suspend fun covers(count: Int = 18): List<String> = withContext(Dispatchers.IO) {
        val fresh = System.currentTimeMillis() - prefs.getLong("albums:at", 0) < 24 * 3600_000L
        val json = prefs.getString("albums", null)?.takeIf { fresh } ?: runCatching {
            (URL(FEED).openConnection() as HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 10000
                inputStream.bufferedReader().use { it.readText() }
            }.also { prefs.edit().putString("albums", it).putLong("albums:at", System.currentTimeMillis()).apply() }
        }.getOrNull() ?: prefs.getString("albums", null) ?: return@withContext emptyList()
        runCatching {
            val results = org.json.JSONObject(json).getJSONObject("feed").getJSONArray("results")
            (0 until minOf(count, results.length())).map {
                results.getJSONObject(it).getString("artworkUrl100").replace("100x100bb", "400x400bb")
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val FEED = "https://rss.marketingtools.apple.com/api/v2/ca/music/most-played/25/albums.json"
    }
}

/**
 * Box art for a console's wall: covers of well-loved games on that platform, from Wikipedia's
 * public API (no key). The lists are hand-picked; the cover addresses are looked up once a week.
 */
class GameCovers(context: Context) {
    private val prefs = context.getSharedPreferences("games", Context.MODE_PRIVATE)

    suspend fun covers(platform: String): List<String> = withContext(Dispatchers.IO) {
        val titles = GAMES[platform] ?: return@withContext emptyList()
        val fresh = System.currentTimeMillis() - prefs.getLong("$platform:at", 0) < 7 * 24 * 3600_000L
        prefs.getString(platform, null)?.takeIf { fresh }?.split('\n')?.filter { it.isNotEmpty() }?.let { return@withContext it }
        runCatching {
            val q = java.net.URLEncoder.encode(titles.joinToString("|"), "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages&piprop=thumbnail" +
                "&pithumbsize=500&pilicense=any&redirects=1&titles=$q"
            val json = (URL(url).openConnection() as HttpURLConnection).run {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 6000; readTimeout = 10000
                inputStream.bufferedReader().use { it.readText() }
            }
            val pages = org.json.JSONObject(json).getJSONObject("query").getJSONObject("pages")
            pages.keys().asSequence().mapNotNull { pages.getJSONObject(it).optJSONObject("thumbnail")?.optString("source") }
                .filter { it.isNotEmpty() }.toList()
                .also { prefs.edit().putString(platform, it.joinToString("\n")).putLong("$platform:at", System.currentTimeMillis()).apply() }
        }.getOrNull() ?: prefs.getString(platform, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    }

    companion object {
        private val GAMES = mapOf(
            "ps5" to listOf(
                "Astro Bot", "Marvel's Spider-Man 2", "God of War Ragnarök", "Ghost of Yōtei", "Elden Ring",
                "Gran Turismo 7", "Horizon Forbidden West", "Final Fantasy VII Rebirth", "Ratchet & Clank: Rift Apart",
                "Returnal", "Death Stranding 2: On the Beach", "Stellar Blade", "Black Myth: Wukong", "Baldur's Gate 3",
                "Demon's Souls (2020 video game)", "It Takes Two (video game)",
            ),
            "switch" to listOf(
                "The Legend of Zelda: Tears of the Kingdom", "The Legend of Zelda: Breath of the Wild", "Mario Kart 8 Deluxe",
                "Super Mario Odyssey", "Animal Crossing: New Horizons", "Super Smash Bros. Ultimate", "Splatoon 3",
                "Super Mario Bros. Wonder", "Metroid Dread", "Kirby and the Forgotten Land", "Pikmin 4",
                "Luigi's Mansion 3", "Xenoblade Chronicles 3", "Hades (video game)", "Hollow Knight", "Pokémon Scarlet and Violet",
            ),
        )
    }
}

/** Fetches artwork over HTTPS or from a content URI, with a small disk cache. */
class ArtLoader(private val context: Context) {
    private val dir = File(context.cacheDir, "art").apply { mkdirs() }
    private val wikimedia = kotlinx.coroutines.sync.Mutex()

    /**
     * Apps publish small thumbnails. YouTube serves every video's thumbnail at several sizes
     * under the same address, so ask for the 1280×720 one first, then 640×480, then what the
     * app gave.
     */
    suspend fun loadBest(uri: String, maxWidth: Int): Bitmap? {
        val yt = Regex("""^(https?://i\d?\.ytimg\.com/vi(?:_webp)?/[^/]+/)[^/?]+""").find(uri)
        val candidates = if (yt != null) {
            val base = yt.groupValues[1].replace("/vi_webp/", "/vi/")
            listOf("${base}maxresdefault.jpg", "${base}sddefault.jpg", uri)
        } else {
            listOf(uri)
        }
        return candidates.firstNotNullOfOrNull { load(it, maxWidth)?.takeIf { b -> b.width > 160 } }
    }

    suspend fun load(uri: String, maxWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(dir, sha1(uri))
            if (!file.exists() || file.length() == 0L) fetch(uri, file)
            decode(file, maxWidth)
        }.getOrNull()
    }

    /**
     * Downloads to the cache. Wikimedia (game box art) rate-limits bursts, so its downloads go
     * one at a time with a short gap, and a "too many requests" answer is retried after the
     * wait it asks for. It only matters the first time: everything is cached on disk after.
     */
    private suspend fun fetch(uri: String, file: File) {
        val polite = "wikimedia.org" in uri
        if (polite) wikimedia.lock()
        try {
            repeat(4) { attempt ->
                val tmp = File(dir, "${file.name}.part")
                val wait = open(uri, tmp)
                if (wait == null) { tmp.renameTo(file); if (polite) kotlinx.coroutines.delay(250); return }
                kotlinx.coroutines.delay(wait * (attempt + 1))
            }
        } finally {
            if (polite) wikimedia.unlock()
        }
    }

    /** Writes [uri] to [to]; returns null when done, or how long to wait before retrying (ms). */
    private fun open(uri: String, to: File): Long? {
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            (context.contentResolver.openInputStream(Uri.parse(uri)) ?: error("No stream for $uri"))
                .use { input -> to.outputStream().use { input.copyTo(it) } }
            return null
        }
        val c = (URL(uri).openConnection() as HttpURLConnection).apply {
            // Image hosts like Wikimedia throttle anonymous clients; say who's asking.
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 6000
            readTimeout = 10000
            instanceFollowRedirects = true
        }
        if (c.responseCode == 429 || c.responseCode == 503) {
            return (c.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: 1500L).coerceIn(500L, 10_000L)
        }
        c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        return null
    }

    private fun decode(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
