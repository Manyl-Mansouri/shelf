package com.manyl.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import kotlin.math.sqrt

/**
 * Every bitmap the home screen draws, prepared once on a background thread. TV GPUs are
 * fill-rate bound, so gradients, scrims, blur and shadows are baked into bitmaps here rather
 * than stacked as live layers.
 */
object Artwork {
    const val TILE_W = 480
    const val TILE_H = 270
    const val FACE_W = 450
    const val FACE_H = 270
    const val FACE_RADIUS = 0.17f
    private const val HERO_W = 1600
    private const val HERO_H = 900

    fun render(drawable: Drawable, w: Int, h: Int): Bitmap =
        drawable.copy().toBitmap(w, h, Bitmap.Config.ARGB_8888)

    /**
     * For apps that ship only a square icon: the icon centered on a soft gradient
     * built from its own colors, so it still reads as a full-bleed tile.
     */
    fun iconCard(icon: Drawable, w: Int, h: Int, iconFraction: Float = 0.52f, base: Int? = null): Bitmap {
        val color = base ?: dominantColor(icon)
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                ColorUtils.blendARGB(color, Color.WHITE, 0.10f),
                ColorUtils.blendARGB(color, Color.BLACK, 0.28f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val size = (h * iconFraction).toInt()
        val d = icon.copy()
        d.setBounds((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)
        d.draw(canvas)
        return out
    }

    private fun dominantColor(icon: Drawable): Int {
        val sample = render(icon, 96, 96)
        val palette = Palette.from(sample).maximumColorCount(12).generate()
        val c = palette.getDarkVibrantColor(palette.getDominantColor(0xFF2C2C2E.toInt()))
        // Keep it deep enough for a white icon or label to sit on.
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }
        hsl[2] = hsl[2].coerceIn(0.16f, 0.42f)
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * A grid tile as the screen draws it: 5:3 with tvOS's corner radius and a fine glass rim
     * baked in. Two dozen of these then draw as plain bitmaps, with no live clipping or strokes.
     */
    fun tileFace(src: Bitmap): Bitmap {
        val w = FACE_W
        val h = FACE_H
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = h * FACE_RADIUS
        drawRounded(canvas, src, rect, radius)
        val rim = h * 0.009f
        canvas.drawRoundRect(
            RectF(rim / 2, rim / 2, w - rim / 2, h - rim / 2), radius - rim / 2, radius - rim / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rim
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(62, 255, 255, 255), Color.argb(12, 255, 255, 255), Shader.TileMode.CLAMP)
            },
        )
        return out
    }

    /**
     * A tile for an HDMI device: its platform's colours and its name, set in Inter, with the
     * same corners and rim as every app tile.
     */
    fun inputTile(context: Context, name: String, colors: Pair<Int, Int>, mark: Drawable? = null): Bitmap {
        val w = FACE_W * 2
        val h = FACE_H * 2
        val art = createBitmap(w, h)
        val canvas = Canvas(art)
        inputBackground(canvas, w, h, colors)
        if (mark != null) {
            // The platform's mark, centred with generous room, the way tvOS app icons sit.
            val ratio = mark.intrinsicWidth.toFloat() / mark.intrinsicHeight.coerceAtLeast(1)
            // A wide wordmark is set by width, a square symbol by height; both with room around.
            val mw = minOf(w * 0.56f, h * 0.44f * ratio)
            val mh = mw / ratio
            val m = mark.copy()
            m.setBounds(((w - mw) / 2).toInt(), ((h - mh) / 2).toInt(), ((w + mw) / 2).toInt(), ((h + mh) / 2).toInt())
            m.draw(canvas)
            return tileFace(art)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = ResourcesCompat.getFont(context, R.font.inter_display_semibold)
            textAlign = Paint.Align.CENTER
            textSize = h * 0.2f
            letterSpacing = -0.01f
        }
        // Shrink long names to fit; never smaller than about half size.
        while (text.measureText(name) > w * 0.84f && text.textSize > h * 0.11f) text.textSize *= 0.94f
        val y = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(name, w / 2f, y, text)
        return tileFace(art)
    }

    /** An HDMI device's brand colours, lit from the top left: the tile's face, at any size. */
    private fun inputBackground(canvas: Canvas, w: Int, h: Int, colors: Pair<Int, Int>) {
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), colors.first, colors.second, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w * 0.2f, 0f, w * 0.9f, Color.argb(60, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
    }

    /**
     * An HDMI device's tile as a full screen, with its mark centred at launch-screen size: what
     * the tile opens into and closes from, since the device's own screen can't be pictured.
     */
    fun inputScreen(colors: Pair<Int, Int>, mark: Bitmap?, w: Int = 1920, h: Int = 1080): Bitmap {
        val screen = createBitmap(w, h)
        val canvas = Canvas(screen)
        inputBackground(canvas, w, h, colors)
        if (mark != null) {
            val ratio = mark.width.toFloat() / mark.height.coerceAtLeast(1)
            val mw = minOf(w * 0.3f, h * 0.24f * ratio)
            val mh = mw / ratio
            val dst = android.graphics.RectF((w - mw) / 2, (h - mh) / 2, (w + mw) / 2, (h + mh) / 2)
            canvas.drawBitmap(mark, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        return ready(screen)
    }

    /**
     * The glass and the browse backdrop for a piece of Top Shelf art: one tiny blurred copy,
     * tinted three ways ahead of time so the screen never runs a colour filter per pixel.
     * Returns frost (dock over art), wash (backdrop while browsing), deep (dock over the wash).
     */
    fun glass(full: Bitmap, browse: Bitmap = full): Triple<Bitmap, Bitmap, Bitmap> {
        val base = blurred(full)
        val shaded = browse === full
        val browseBase = if (shaded) base else blurred(browse)
        // The browse backdrop and its glass come from a richer copy: saturation up and a lift in
        // brightness, so browsing keeps the art's colour instead of sinking to near-black.
        val vivid = createBitmap(browseBase.width, browseBase.height).also {
            Canvas(it).drawBitmap(browseBase, 0f, 0f, Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                    setSaturation(1.9f)
                    postConcat(android.graphics.ColorMatrix().apply { setScale(1.45f, 1.45f, 1.45f, 1f) })
                })
            })
        }
        fun tinted(src: Bitmap, color: Int) = src.copy(Bitmap.Config.ARGB_8888, true).also {
            Canvas(it).drawColor(color, PorterDuff.Mode.SRC_ATOP)
        }
        // An unshaded source is brighter to begin with, so it's dimmed a little more.
        val dim = if (shaded) 105 else 135
        return Triple(tinted(base, Color.argb(38, 255, 255, 255)), tinted(vivid, Color.argb(dim, 0, 0, 0)), tinted(vivid, Color.argb(dim - 45, 0, 0, 0)))
    }

    /**
     * The colour an app opens into: the banner's edge colour (its background, usually the same
     * as the app's own launch screen), from a tile before its corners are rounded.
     */
    fun launchColor(tile: Bitmap): Int {
        val w = tile.width
        val h = tile.height
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until w step 4) for (y in listOf(1, h - 2)) {
            val p = tile.getPixel(x, y); r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        for (y in 0 until h step 4) for (x in listOf(1, w - 2)) {
            val p = tile.getPixel(x, y); r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * A dark version of an app's colour for the screen it opens into: same hue, never brighter
     * than a deep shade. White and pale banners become a near-black grey.
     */
    fun deepShade(color: Int): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
        hsl[2] = hsl[2].coerceAtMost(0.1f)
        hsl[1] = hsl[1] * 0.85f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Whether a screen is (nearly) all black: a protected video, or an app that hasn't drawn
     * yet. Samples a coarse grid; a screenshot straight from the GPU is copied down first.
     */
    fun isDark(screen: Bitmap): Boolean = litFraction(screen) < 0.03f

    /** How much of a screen is lit at all (anything brighter than near-black), 0–1, on a coarse grid. */
    fun litFraction(screen: Bitmap): Float {
        val b = if (screen.config == Bitmap.Config.HARDWARE) screen.copy(Bitmap.Config.ARGB_8888, false) ?: return 1f else screen
        var lit = 0
        var n = 0
        for (gy in 0 until 18) for (gx in 0 until 32) {
            val p = b.getPixel((gx + 0.5f) * b.width / 32f, (gy + 0.5f) * b.height / 18f)
            if (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) > 60) lit++
            n++
        }
        if (b !== screen) b.recycle()
        // A dark UI still has text, thumbnails and highlights; under 3% lit is a blank screen.
        return lit.toFloat() / n
    }

    private fun Bitmap.getPixel(x: Float, y: Float) = getPixel(x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1))

    /** Uploads [b] to the GPU in the background now, rather than mid-animation on first draw. */
    fun ready(b: Bitmap): Bitmap = b.also { it.prepareToDraw() }

    /**
     * Some banners run their logo almost to the edges, which looks cramped once the tile is
     * rounded. Those are re-set: the logo lifted off, scaled down and centred on the banner's
     * own background colour, with tvOS-like breathing room. Null when the banner is fine as is
     * (or can't be cut cleanly).
     */
    fun reframe(banner: Bitmap): Bitmap? {
        val c = cutout(banner, flipDark = false) ?: return null
        val w = banner.width.toFloat()
        val h = banner.height.toFloat()
        val cramped = c.bounds.width() > w * 0.8f || c.bounds.left < w * 0.07f || c.bounds.right > w * 0.93f
        if (!cramped) return null
        val out = createBitmap(banner.width, banner.height)
        val canvas = Canvas(out)
        canvas.drawColor(c.background)
        val scale = minOf(w * 0.74f / c.logo.width, h * 0.48f / c.logo.height, 1f)
        val lw = c.logo.width * scale
        val lh = c.logo.height * scale
        canvas.drawBitmap(c.logo, null, RectF((w - lw) / 2, (h - lh) / 2, (w + lw) / 2, (h + lh) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Crops away fully transparent margins, so a logo's left edge is where its ink starts. */
    fun trim(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (i in px.indices) if ((px[i] ushr 24) > 12) {
            val x = i % w; val y = i / w
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0 || (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1)) return src
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** A banner shown whole, as a rounded card, when its logo can't be cut out cleanly. */
    fun bannerCard(src: Bitmap): Bitmap {
        val out = createBitmap(src.width, src.height)
        drawRounded(Canvas(out), src, RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), src.height * 0.09f)
        return out
    }

    private fun drawRounded(canvas: Canvas, src: Bitmap, rect: RectF, radius: Float) {
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply {
            setRectToRect(RectF(coverCrop(src, rect)), rect, Matrix.ScaleToFit.FILL)
        })
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
    }

    /**
     * Full-screen Top Shelf art from an app's artwork. Wide art fills the screen; a portrait
     * poster sits on the right over a blurred wash of itself. The scrims that keep the title
     * and the dock legible are painted in, so the screen draws this as one opaque bitmap.
     */
    fun heroFromPhoto(src: Bitmap, titleShade: Boolean = true): Bitmap {
        val out = createBitmap(HERO_W, HERO_H)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val full = RectF(0f, 0f, HERO_W.toFloat(), HERO_H.toFloat())
        val ratio = src.width.toFloat() / src.height
        if (ratio >= 1.3f) {
            canvas.drawBitmap(src, coverCrop(src, full), full, paint)
        } else {
            val wash = blurred(src, 64, 36)
            canvas.drawBitmap(wash, null, RectF(-HERO_W * 0.05f, -HERO_H * 0.05f, HERO_W * 1.05f, HERO_H * 1.05f), paint)
            canvas.drawColor(black(0.3f))
            val ph = HERO_H * 0.8f
            val pw = ph * ratio
            val card = RectF(HERO_W * 0.92f - pw, HERO_H * 0.07f, HERO_W * 0.92f, HERO_H * 0.07f + ph)
            drawSoftShadow(canvas, card, pw * 0.05f)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(card, pw * 0.035f, pw * 0.035f, Path.Direction.CW) })
            canvas.drawBitmap(src, null, card, paint)
            canvas.restore()
        }
        if (!titleShade) {
            // Nothing is written over it: only the shade behind the dock.
            bottomScrim(canvas, HERO_W, HERO_H)
            out.setHasAlpha(false)
            return out
        }
        // A bright corner (sky, ceiling lights) gets extra shade so a white logo still reads.
        val corner = luminance(out, Rect(0, 0, (HERO_W * 0.45f).toInt(), (HERO_H * 0.32f).toInt()))
        val extra = ((corner - 0.32f) * 1.3f).coerceIn(0f, 0.45f)
        if (extra > 0f) {
            canvas.drawRect(0f, 0f, HERO_W.toFloat(), HERO_H.toFloat(), Paint().apply {
                shader = RadialGradient(HERO_W * 0.12f, HERO_H * 0.1f, HERO_W * 0.55f, black(extra), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }
        scrims(canvas, HERO_W, HERO_H)
        out.setHasAlpha(false)
        return out
    }

    /**
     * Logos are drawn for all sorts of backgrounds. One that is mostly dark would vanish on the
     * dark Top Shelf, so its neutral (black and grey) parts are turned white, the way brands show
     * themselves on dark screens, while its colours (a green lock, a red play button) stay.
     */
    fun legibleLogo(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        var sum = 0.0
        var n = 0
        for (p in px) if ((p ushr 24) > 128) { sum += ColorUtils.calculateLuminance(p or (0xFF shl 24)); n++ }
        if (n == 0 || sum / n > 0.32) return src
        val hsl = FloatArray(3)
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) == 0) continue
            ColorUtils.colorToHSL(p or (0xFF shl 24), hsl)
            if (hsl[1] < 0.3f || hsl[2] < 0.12f) px[i] = (p and (0xFF shl 24)) or 0xF5F5F5
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(b: Bitmap, r: Rect): Float {
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()), 24, 12, true)
        var sum = 0.0
        for (y in 0 until 12) for (x in 0 until 24) sum += ColorUtils.calculateLuminance(small.getPixel(x, y))
        return (sum / (24 * 12)).toFloat()
    }

    /**
     * A product shot for an HDMI device: the console on a deep field of its platform's colour,
     * lit from behind and grounded by a soft floor shadow, leaving the left for its logo.
     */
    fun productHero(photo: Bitmap, colors: Pair<Int, Int>): Bitmap {
        val out = createBitmap(HERO_W, HERO_H)
        val c = Canvas(out)
        val w = HERO_W.toFloat()
        val h = HERO_H.toFloat()
        c.drawColor(tone(colors.second, 0.05f, 0.9f))
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(w * 0.7f, h * 0.36f, w * 0.6f, intArrayOf(withAlpha(tone(colors.first, 0.42f, 1f), 0.85f), withAlpha(tone(colors.first, 0.22f, 1f), 0.4f), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        // Fit inside the space above the dock (the top ~70% of the screen): a tall console by
        // height, a wide one by width, standing on the same floor line.
        val ratio = photo.width.toFloat() / photo.height
        val floor = h * 0.68f
        val (pw, ph) = if (ratio < 1f) (h * 0.6f * ratio) to (h * 0.6f) else minOf(w * 0.44f, h * 0.56f * ratio).let { it to it / ratio }
        val cx = w * 0.7f
        val bottom = floor
        val dst = RectF(cx - pw / 2, bottom - ph, cx + pw / 2, bottom)
        // Floor shadow, then the product.
        c.drawOval(RectF(dst.left - pw * 0.1f, bottom - ph * 0.04f, dst.right + pw * 0.1f, bottom + ph * 0.06f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, bottom + ph * 0.01f, pw * 0.7f, black(0.6f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        c.drawBitmap(photo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        scrims(c, HERO_W, HERO_H)
        out.setHasAlpha(false)
        return out
    }

    /**
     * A wall of pictures (album covers, app tiles) set at a slight angle across the screen,
     * dimmed so a logo reads on top: Top Shelf art for apps that have a world of content but
     * publish none of it.
     */
    fun wall(images: List<Bitmap>, aspect: Float): Pair<Bitmap, Bitmap> {
        val out = createBitmap(HERO_W, HERO_H)
        val c = Canvas(out)
        val w = HERO_W.toFloat()
        val h = HERO_H.toFloat()
        c.drawColor(Color.rgb(10, 10, 12))
        val cellH = if (aspect >= 1.2f) h * 0.24f else h * 0.34f
        val cellW = cellH * aspect
        val gap = cellH * 0.08f
        val radius = cellH * if (aspect >= 1.2f) 0.17f else 0.05f
        c.save()
        c.rotate(-9f, w / 2, h / 2)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var i = 0
        var row = 0
        var y = -h * 0.35f
        while (y < h * 1.35f) {
            // Alternate rows are offset by half a cell, like bricks.
            var x = -w * 0.25f + (if (row % 2 == 1) (cellW + gap) / 2 else 0f)
            while (x < w * 1.25f) {
                val img = images[i++ % images.size]
                val r = RectF(x, y, x + cellW, y + cellH)
                val shader = BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setRectToRect(RectF(coverCrop(img, r)), r, Matrix.ScaleToFit.FILL) })
                }
                paint.shader = shader
                c.drawRoundRect(r, radius, radius, paint)
                x += cellW + gap
            }
            y += cellH + gap
            row++
        }
        c.restore()
        // Kept before any shading: browsing blurs this one, so the dark side behind the logo
        // fades away as you scroll down instead of following you into the grid.
        val plain = out.copy(Bitmap.Config.ARGB_8888, false)
        // Dim the wall, more so on the left where the logo sits.
        c.drawColor(black(0.38f))
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.7f, 0f, intArrayOf(black(0.75f), black(0.35f), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        scrims(c, HERO_W, HERO_H)
        out.setHasAlpha(false)
        return out to plain
    }

    /**
     * Top Shelf art for an app that publishes nothing: a deep field lit by soft glows of its
     * brand colors. Pure gradients, so a small bitmap upscales without a visible pixel.
     */
    fun brandHero(tile: Bitmap): Bitmap {
        val w = 640
        val h = 360
        val palette = Palette.from(tile).maximumColorCount(16).generate()
        val dominant = palette.getDominantColor(0xFF3A3A3C.toInt())
        val glow = listOf(palette.vibrantSwatch, palette.lightVibrantSwatch, palette.darkVibrantSwatch, palette.dominantSwatch)
            .firstOrNull { it != null && saturation(it.rgb) > 0.25f }?.rgb ?: dominant
        val second = palette.mutedSwatch?.rgb ?: palette.darkMutedSwatch?.rgb ?: glow
        val out = createBitmap(w, h)
        val canvas = Canvas(out)
        canvas.drawColor(tone(dominant, lightness = 0.055f, satScale = 0.7f))
        fun glowAt(cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(withAlpha(tone(color, 0.45f, 1.1f), alpha), withAlpha(tone(color, 0.3f, 1f), alpha * 0.35f), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            })
        }
        glowAt(w * 0.78f, h * 0.12f, w * 0.85f, glow, 0.62f)
        glowAt(w * 0.08f, h * 1.05f, w * 0.7f, second, 0.32f)
        scrims(canvas, w, h)
        out.setHasAlpha(false)
        return out
    }

    /**
     * The logo lifted off its banner: the banner's flat background keyed out so the mark can be
     * set like a title. Dark lettering made for a light banner is flipped to white, the way
     * brands do it themselves on dark backgrounds. Null when the banner has no flat background
     * (a photo or a gradient), which can't be keyed cleanly.
     */
    fun logoCutout(src: Bitmap): Bitmap? = cutout(src, flipDark = true)?.logo

    /** A banner's logo lifted off its background, where it sat, and what the background was. */
    private class Cutout(val logo: Bitmap, val bounds: Rect, val background: Int)

    private fun cutout(src: Bitmap, flipDark: Boolean): Cutout? {
        val w = src.width
        val h = src.height
        if (w < 32 || h < 18) return null
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }

        // The background is whatever colour the banner's edges are.
        var n = 0; var sr = 0.0; var sg = 0.0; var sb = 0.0
        val ring = ArrayList<Int>()
        for (x in 0 until w) for (y in listOf(0, 1, h - 2, h - 1)) ring += px[y * w + x]
        for (y in 0 until h) for (x in listOf(0, 1, w - 2, w - 1)) ring += px[y * w + x]
        for (p in ring) { sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; n++ }
        val br = sr / n; val bg = sg / n; val bb = sb / n
        var spread = 0.0
        for (p in ring) spread += dist((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, br, bg, bb)
        if (spread / n > 18) return null
        val lightBackground = ColorUtils.calculateLuminance(Color.rgb(br.toInt(), bg.toInt(), bb.toInt())) > 0.5

        val out = IntArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (i in px.indices) {
            val p = px[i]
            val r0 = (p shr 16) and 0xFF; val g0 = (p shr 8) and 0xFF; val b0 = p and 0xFF
            // Colour-to-alpha: the least opacity at which this pixel is some colour laid over the
            // background. Anti-aliased edges come out as the logo's own colour, partly transparent,
            // instead of keeping a fringe of the old background.
            var a = maxOf(unmix(r0, br), unmix(g0, bg), unmix(b0, bb))
            a = ((a - 0.04) / 0.96).coerceIn(0.0, 1.0) * ((p ushr 24) / 255.0)
            if (a < 0.02) continue
            var r = ((r0 - (1 - a) * br) / a).coerceIn(0.0, 255.0)
            var g = ((g0 - (1 - a) * bg) / a).coerceIn(0.0, 255.0)
            var b = ((b0 - (1 - a) * bb) / a).coerceIn(0.0, 255.0)
            if (flipDark && lightBackground) {
                val max = maxOf(r, g, b); val min = minOf(r, g, b)
                val sat = if (max == 0.0) 0.0 else (max - min) / max
                if (sat < 0.25 && max < 110) { r = 245.0; g = 245.0; b = 245.0 }
            }
            out[i] = ((a * 255).toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            val x = i % w; val y = i / w
            if (a > 0.1) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
        }
        if (maxX < 0) return null
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        if (cw * ch < w * h * 0.01 || cw * ch > w * h * 0.92) return null
        val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        return Cutout(
            logo = Bitmap.createBitmap(full, minX, minY, cw, ch),
            bounds = Rect(minX, minY, maxX + 1, maxY + 1),
            background = Color.rgb(br.toInt(), bg.toInt(), bb.toInt()),
        )
    }

    /** A tiny, heavily blurred copy: the source for every piece of "glass" and the browse backdrop. */
    fun blurred(src: Bitmap, w: Int = 96, h: Int = 54): Bitmap {
        val small = createBitmap(w, h)
        Canvas(small).drawBitmap(src, coverCrop(src, RectF(0f, 0f, w.toFloat(), h.toFloat())), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        // Three box passes approximate a gaussian.
        repeat(3) { boxBlur(px, w, h, radius = maxOf(2, w / 24)) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The focused tile's shadow, the way tvOS draws it: a soft pool directly beneath the icon,
     * a little narrower than it, rather than a dark halo around it. The tile is the middle
     * [SHADOW_INSET] of this bitmap; the rest is blur falloff.
     */
    val tileShadow: Bitmap by lazy {
        val w = 300
        val h = 220
        val out = createBitmap(w, h)
        // The tile's footprint (5:3), pulled in a little at the sides and barely lowered, so the
        // shadow sits under the icon rather than hanging below it.
        val tw = w * (1 - 2 * SHADOW_INSET)
        val th = tw * 0.6f
        val left = w * SHADOW_INSET + tw * 0.04f
        val top = (h - th) / 2f + th * 0.04f
        Canvas(out).drawRoundRect(RectF(left, top, left + tw * 0.92f, top + th), th * 0.17f, th * 0.17f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        val px = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
        repeat(3) { boxBlur(px, w, h, radius = 13) }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    const val SHADOW_INSET = 0.2f

    /** Darkens where the title sits (top left) and behind the dock (bottom). */
    private fun scrims(canvas: Canvas, w: Int, h: Int) {
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(0f, 0f, w * 0.72f, intArrayOf(black(0.55f), black(0.22f), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = RadialGradient(w.toFloat(), 0f, w * 0.3f, black(0.3f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        bottomScrim(canvas, w, h)
    }

    private fun bottomScrim(canvas: Canvas, w: Int, h: Int) {
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, h * 0.48f, 0f, h.toFloat(), intArrayOf(Color.TRANSPARENT, black(0.45f), black(0.82f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
    }

    /**
     * Crops black bars baked into a picture (YouTube's 4:3 thumbnails letterbox 16:9 video), so
     * the Top Shelf fills with the picture, not a black band.
     */
    fun unletterbox(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        fun dark(y: Int): Boolean {
            var sum = 0
            val step = maxOf(1, w / 48)
            var n = 0
            for (x in 0 until w step step) { val p = src.getPixel(x, y); sum += maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF); n++ }
            return sum / n < 18
        }
        var top = 0
        while (top < h / 3 && dark(top)) top++
        var bottom = h - 1
        while (bottom > h * 2 / 3 && dark(bottom)) bottom--
        // Only real bars: a few pixels of dark sky isn't letterboxing.
        if (top < h / 40 && h - 1 - bottom < h / 40) return src
        return Bitmap.createBitmap(src, 0, top, w, bottom - top + 1)
    }

    private fun drawSoftShadow(canvas: Canvas, r: RectF, spread: Float) {
        val dst = RectF(r.left - spread * 2, r.top - spread, r.right + spread * 2, r.bottom + spread * 3)
        canvas.drawBitmap(tileShadow, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 })
    }

    /** Source rect that fills [dst] without distortion, biased a little above center where faces sit. */
    private fun coverCrop(src: Bitmap, dst: RectF): Rect {
        val target = dst.width() / dst.height()
        val ratio = src.width.toFloat() / src.height
        return if (ratio > target) {
            val cw = (src.height * target).toInt()
            val x = (src.width - cw) / 2
            Rect(x, 0, x + cw, src.height)
        } else {
            val ch = (src.width / target).toInt()
            val y = ((src.height - ch) * 0.4f).toInt()
            Rect(0, y, src.width, y + ch)
        }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(px.size)
        blurPass(px, tmp, w, h, radius, horizontal = true)
        blurPass(tmp, px, w, h, radius, horizontal = false)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        val div = 2 * r + 1
        fun idx(o: Int, i: Int) = if (horizontal) o * w + i else i * w + o
        for (o in 0 until outer) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) {
                val p = src[idx(o, k.coerceIn(0, inner - 1))]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until inner) {
                dst[idx(o, i)] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[idx(o, (i - r).coerceAtLeast(0))]
                val inc = src[idx(o, (i + r + 1).coerceAtMost(inner - 1))]
                a += (inc ushr 24) - (out ushr 24)
                rr += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun tone(color: Int, lightness: Float, satScale: Float): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
        hsl[1] = (hsl[1] * satScale).coerceIn(0f, 1f)
        hsl[2] = lightness
        return ColorUtils.HSLToColor(hsl)
    }

    private fun saturation(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }[1]

    private fun withAlpha(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt().coerceIn(0, 255))

    private fun black(alpha: Float) = Color.argb((alpha * 255).toInt(), 0, 0, 0)

    private fun dist(r: Int, g: Int, b: Int, br: Double, bg: Double, bb: Double): Double {
        val dr = r - br; val dg = g - bg; val db = b - bb
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** How opaque a foreground must be to move one channel from [bg] to [c]. */
    private fun unmix(c: Int, bg: Double): Double = when {
        c < bg -> (bg - c) / bg
        c > bg -> (c - bg) / (255 - bg)
        else -> 0.0
    }

    private fun Drawable.copy(): Drawable = constantState?.newDrawable()?.mutate() ?: this
}
