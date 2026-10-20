package com.manyl.shelf

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.asImageBitmap

/**
 * HDMI devices as apps. Anything that announces itself over HDMI-CEC (a PlayStation, an Xbox,
 * an Apple TV…) gets a tile named after the device, so it sits in the grid and can be arranged
 * or hidden like any app. Empty ports and audio gear (a soundbar on ARC) never show.
 *
 * Devices are remembered once seen: a console that's asleep or undocked drops off HDMI-CEC,
 * but its tile stays where you put it, like an app. Hide it to get rid of it.
 */
class InputSource(private val context: Context) {
    private val tv = context.getSystemService(TvInputManager::class.java)
    private val prefs = context.getSharedPreferences("inputs", Context.MODE_PRIVATE)
    private val cache = HashMap<String, TvApp>()

    fun devices(): List<TvApp> {
        val manager = tv ?: return emptyList()
        val ports = ports()
        val known = loadKnown()
        // Earlier versions could remember a placeholder; forget those.
        known.keys.removeAll { isPlaceholder(it) }
        // A CEC device is listed as its own input, under the HDMI port it says it's on.
        manager.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId != null }.forEach { info ->
            val name = pretty((info.loadCustomLabel(context) ?: info.loadLabel(context)).toString().trim())
            // Right after a device is plugged in, the TV lists it under a stand-in name ("Playback
            // 1") until the device says what it is. Only a real name is worth a tile.
            if (isPlaceholder(name)) return@forEach
            // Anything remembered under another name for this same input was a stand-in for
            // this device; the real name replaces it.
            val id = "$KEY_PREFIX${info.id}"
            known.entries.removeAll { (other, k) -> other != name && k.key == id }
            val reported = ports.entries.firstOrNull { it.value == info.parentId }?.key ?: 0
            known[name] = Known(known[name]?.key ?: "$KEY_PREFIX${info.id}", reported)
        }
        // Two names on one input: keep the one the TV is reporting now (else the first).
        val live = manager.tvInputList.mapNotNull { info ->
            pretty((info.loadCustomLabel(context) ?: info.loadLabel(context)).toString().trim()).takeIf { info.parentId != null }
        }.toSet()
        known.entries.groupBy { it.value.key }.values.filter { it.size > 1 }.forEach { same ->
            val keep = same.firstOrNull { it.key in live } ?: same.first()
            same.filter { it !== keep }.forEach { known.remove(it.key) }
        }
        saveKnown(known)
        return known.mapNotNull { (name, k) -> runCatching { tileFor(name, k, ports) }.getOrNull() }
    }

    private fun isPlaceholder(name: String) =
        name.isBlank() || PLACEHOLDER.matches(name.trim())

    /** A device seen over HDMI-CEC: its tile's key (kept for good) and the port it announced. */
    private data class Known(val key: String, val reported: Int)

    private fun loadKnown(): MutableMap<String, Known> {
        val json = org.json.JSONObject(prefs.getString("known", "{}") ?: "{}")
        return json.keys().asSequence().associateWith { name ->
            json.getJSONObject(name).let { Known(it.getString("key"), it.optInt("reported")) }
        }.toMutableMap()
    }

    private fun saveKnown(known: Map<String, Known>) {
        val json = org.json.JSONObject()
        known.forEach { (name, k) -> json.put(name, org.json.JSONObject().put("key", k.key).put("reported", k.reported)) }
        prefs.edit().putString("known", json.toString()).apply()
    }

    /**
     * The TV's HDMI ports, numbered 1–4, mapped to their inputs. Ports are listed as inputs with
     * no parent; their label carries the number ("HDMI 2"), else their order does.
     */
    fun ports(): Map<Int, String> {
        val hw = tv?.tvInputList.orEmpty().filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
        val byLabel = hw.mapNotNull { info ->
            Regex("(\\d)").find(info.loadLabel(context).toString())?.groupValues?.get(1)?.toInt()?.let { it to info.id }
        }.toMap()
        if (byLabel.size == hw.size && byLabel.isNotEmpty()) return byLabel
        return hw.sortedWith(compareBy({ it.id.length }, { it.id })).withIndex().associate { (i, info) -> i + 1 to info.id }
    }

    /** The port a device is on: the one you picked, else the one it announces over HDMI-CEC. */
    fun portOf(app: TvApp): Int? = loadKnown()[app.label]?.let { pinned(app.label, it) ?: it.reported }

    /**
     * Your pick, if it still applies. A pin remembers what the device was announcing when you
     * set it; once the device announces a different port (it was moved to another socket), the
     * pin is stale and is dropped, so the tile follows the device again.
     */
    private fun pinned(name: String, known: Known): Int? {
        val port = prefs.getInt("port:$name", 0).takeIf { it > 0 } ?: return null
        // A pin saved without its context (from before pins had one) can't be trusted either.
        val against = prefs.getInt("pinnedAgainst:$name", -1)
        if (against != known.reported) {
            prefs.edit().remove("port:$name").remove("pinnedAgainst:$name").apply()
            return null
        }
        return port
    }

    /**
     * Some devices announce the wrong port (a Nintendo Switch dock often claims the port it
     * was last plugged into), so you can set it by hand from the tile's menu.
     */
    fun setPort(app: TvApp, port: Int) {
        val reported = loadKnown()[app.label]?.reported ?: 0
        prefs.edit().putInt("port:${app.label}", port).putInt("pinnedAgainst:${app.label}", reported).apply()
        cache.remove(app.key)
    }

    private fun tileFor(name: String, known: Known, ports: Map<Int, String>): TvApp {
        val key = known.key
        val port = pinned(name, known) ?: known.reported
        val target = ports[port] ?: key.removePrefix(KEY_PREFIX)
        cache[key]?.takeIf { it.label == name && it.intent.data == TvContract.buildChannelUriForPassthroughInput(target) }?.let { return it }
        // Switch to the device's port; the TV's own player handles this link.
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val mark = markFor(name)
        val tile = Artwork.inputTile(context, name, colorsFor(name), mark?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) })
        // What the tile opens into: the device's own screen can't be pictured (HDMI is copy-protected).
        AppSnapshots.standIn(key, Artwork.inputScreen(colorsFor(name), mark))
        return TvApp(
            key = key,
            label = name,
            intent = intent,
            tile = tile.asImageBitmap(),
            updatedAt = 0L,
            info = null,
            color = colorsFor(name).second,
        ).also { cache[key] = it }
    }

    /**
     * "NintendoSwitch" → "Nintendo Switch": some devices squeeze their CEC name. Names that
     * already have spaces ("PlayStation 5") are left alone.
     */
    private fun pretty(name: String) = if (' ' in name) name else name.replace(Regex("([a-z])([A-Z])"), "$1 $2")

    private fun markFor(name: String): android.graphics.Bitmap? = when {
        isPlayStation(name) -> android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.logo_ps5)
        isNintendo(name) -> android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.logo_switch)
        else -> null
    }

    /** Each platform's own colours, so a console reads as itself at a glance. */
    private fun colorsFor(name: String): Pair<Int, Int> = when {
        isPlayStation(name) -> 0xFF0070D1.toInt() to 0xFF00246B.toInt()
        name.contains("xbox", ignoreCase = true) -> 0xFF1DB024.toInt() to 0xFF0B4A0D.toInt()
        isNintendo(name) -> 0xFFE60012.toInt() to 0xFF8A000B.toInt()
        name.contains("apple", ignoreCase = true) -> 0xFF2C2C2E.toInt() to 0xFF0A0A0A.toInt()
        name.contains("roku", ignoreCase = true) -> 0xFF6F1AB1.toInt() to 0xFF2E0A4A.toInt()
        else -> 0xFF48484A.toInt() to 0xFF1C1C1E.toInt()
    }

    /** A product shot for the device's Top Shelf, for platforms Shelf has a photo of. */
    fun productHero(app: TvApp): android.graphics.Bitmap? {
        val res = when {
            isPlayStation(app.label) -> R.drawable.hero_ps5
            isNintendo(app.label) -> R.drawable.hero_switch
            else -> return null
        }
        val photo = android.graphics.BitmapFactory.decodeResource(context.resources, res) ?: return null
        return Artwork.productHero(photo, colorsFor(app.label))
    }

    /** Which console this is, for the platforms Shelf has a games wall for. */
    fun platformOf(app: TvApp): String? = when {
        isPlayStation(app.label) -> "ps5"
        isNintendo(app.label) -> "switch"
        else -> null
    }

    /** The platform's mark as a Top Shelf title logo, when there is one. */
    fun logoFor(app: TvApp): android.graphics.Bitmap? = markFor(app.label)

    private fun isPlayStation(name: String) = name.replace(" ", "").contains("playstation", ignoreCase = true) || name.startsWith("PS")
    private fun isNintendo(name: String) = name.contains("nintendo", ignoreCase = true) || name.contains("switch", ignoreCase = true)

    fun register(onChange: () -> Unit): TvInputManager.TvInputCallback? {
        val manager = tv ?: return null
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputAdded(inputId: String) = onChange()
            override fun onInputRemoved(inputId: String) = onChange()
            override fun onInputUpdated(inputId: String) = onChange()
            override fun onInputStateChanged(inputId: String, state: Int) = onChange()
        }
        manager.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        return callback
    }

    fun unregister(callback: TvInputManager.TvInputCallback?) {
        if (callback != null) tv?.unregisterCallback(callback)
    }

    companion object {
        const val KEY_PREFIX = "input:"

        /** The TV's stand-in names for a device that hasn't named itself (CEC device types, ports). */
        private val PLACEHOLDER = Regex(
            "(?i)^(playback( device)?|recorder|recording device|tuner|audio( system)?|tv|hdmi|hdmi[ -]?cec|device|input|unknown|reserved|video processor)( ?\\d+)?$",
        )
    }
}

val TvApp.isInput get() = key.startsWith(InputSource.KEY_PREFIX)
