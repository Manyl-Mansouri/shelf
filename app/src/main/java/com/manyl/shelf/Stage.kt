package com.manyl.shelf

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.roundToInt

/** What the Top Shelf is showing: the app, the item, and its rendered art. */
@Immutable
internal class Shown(val app: TvApp, val item: ShelfItem, val hero: Hero)

/**
 * Everything behind the icons, in one draw pass: the Top Shelf art (crossfading between apps),
 * the dark blurred wash it becomes while browsing, and the glass dock under the top row.
 *
 * Every tint is baked into the bitmaps ahead of time (see [Artwork.glass]): the screen draws
 * plain images, never a per-pixel colour filter. The glass is a pre-blurred copy of the art,
 * mapped to screen coordinates and painted only inside the dock, so it lines up with whatever
 * is behind it without any live blur.
 */
@Composable
internal fun Stage(shown: Shown?, m: Metrics, browse: () -> Float, rowShift: () -> Float) {
    var current by remember { mutableStateOf(shown) }
    var previous by remember { mutableStateOf<Shown?>(null) }
    val fade = remember { Animatable(1f) }
    // New art arrives a touch closer and eases back, once, so the screen is still at rest.
    val settle = remember { Animatable(1f) }
    LaunchedEffect(shown?.hero) {
        val next = shown ?: return@LaunchedEffect
        val was = current
        if (was == null || was.hero === next.hero) {
            current = next
            return@LaunchedEffect
        }
        previous = was
        current = next
        fade.snapTo(0f)
        launch { settle.snapTo(1.025f); settle.animateTo(1f, tween(1800, easing = LinearOutSlowInEasing)) }
        fade.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        previous = null
    }

    val d = LocalDensity.current
    val side = with(d) { (m.side - m.dockPadX).toPx() }
    val dockTop = with(d) { (m.restY - m.dockPadY).toPx() }
    val dockH = with(d) { (m.tileH + m.dockPadY * 2).toPx() }
    val dockR = with(d) { m.dockRadius.toPx() }
    val rim = with(d) { 1.dp.toPx() }
    val glass = remember { GlassPaint() }
    val glassBefore = remember { GlassPaint() }

    Box(
        Modifier.fillMaxSize().drawBehind {
            val cur = current ?: return@drawBehind drawRect(Color.Black)
            val b = browse()
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            if (b < 0.999f) {
                previous?.let { drawImage(it.hero.full, dstSize = full, filterQuality = FilterQuality.Low) }
                scale(settle.value) {
                    drawImage(cur.hero.full, dstSize = full, alpha = if (previous != null) fade.value else 1f, filterQuality = FilterQuality.Low)
                }
            }
            if (b > 0.001f) drawImage(cur.hero.wash, dstSize = full, alpha = b, filterQuality = FilterQuality.Low)
            // The dock travels with the top row.
            val top = dockTop + rowShift()
            if (top < size.height) {
                val dock = Rect(side, top, size.width - side, top + dockH)
                // The glass shows the art behind it, so it crossfades with the art, not ahead of it.
                val before = previous
                if (before != null) glassBefore.panel(this, dock, dockR, if (b > 0.5f) before.hero.deep else before.hero.frost)
                glass.panel(this, dock, dockR, if (b > 0.5f) cur.hero.deep else cur.hero.frost, alpha = if (before != null) fade.value else 1f)
                glass.rim(this, dock, dockR, rim)
            }
        },
    )
}

/** Paints frosted glass: the blurred, pre-tinted art behind it, edged with a fine light rim. */
private class GlassPaint {
    private var source: ImageBitmap? = null
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun panel(scope: DrawScope, r: Rect, radius: Float, glass: ImageBitmap, alpha: Float = 1f) = with(scope) {
        if (glass !== source) {
            fill.shader = BitmapShader(glass.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            source = glass
        }
        matrix.setScale(size.width / glass.width, size.height / glass.height)
        fill.shader.setLocalMatrix(matrix)
        fill.alpha = (alpha * 255).roundToInt()
        drawIntoCanvas { it.nativeCanvas.drawRoundRect(r.left, r.top, r.right, r.bottom, radius, radius, fill) }
    }

    fun rim(scope: DrawScope, r: Rect, radius: Float, rim: Float) = with(scope) {
        drawRoundRect(
            Brush.verticalGradient(listOf(Colors.rimTop, Colors.rimBottom), startY = r.top, endY = r.bottom),
            r.topLeft, r.size, CornerRadius(radius), style = Stroke(rim),
        )
    }
}

/**
 * The Top Shelf's words, top left: the show's own logo when it could be found (or the app's,
 * for apps that publish nothing), else its title, with one quiet line underneath. They
 * crossfade with the art and step aside while you browse.
 */
@Composable
internal fun ShelfTitle(shown: Shown?, m: Metrics, browse: () -> Float) {
    // Old and new titles crossfade. Alpha is applied to each draw (ModulateAlpha), not
    // through an offscreen buffer, so the fade costs nothing extra on the GPU.
    var current by remember { mutableStateOf(shown) }
    var previous by remember { mutableStateOf<Shown?>(null) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(shown) {
        if (shown === current) return@LaunchedEffect
        previous = current
        current = shown
        t.snapTo(0f)
        t.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        previous = null
    }
    listOf(previous to true, current to false).forEach { (s, leaving) ->
        if (s == null) return@forEach
        key(s) { Title(s, m, alpha = { (if (leaving) 1f - t.value else if (previous != null) t.value else 1f) * (1f - browse()) }) }
    }
}

@Composable
private fun Title(s: Shown, m: Metrics, alpha: () -> Float) {
    val d = LocalDensity.current
    val item = s.item
    if (item.bare) return
    Column(
        Modifier
            .graphicsLayer {
                this.alpha = alpha()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .padding(start = m.titleX, top = m.titleY)
            .width(m.w * 0.42f),
    ) {
        val logo = s.hero.logo
        if (logo != null) {
            // A whole-banner card gets more height than a wordmark.
            val card = s.hero.logoIsCard
            if (card) {
                Logo(logo, maxW = with(d) { (m.w * 0.24f).toPx() }, maxH = with(d) { (m.h * 0.24f).toPx() })
            } else {
                // Optical sizing: a wide wordmark (PS5) gets less height than a compact one, so
                // every logo carries about the same visual weight; and none runs wider than 30%.
                val aspect = logo.width.toFloat() / logo.height
                val height = (m.h * 0.2f / kotlin.math.sqrt(aspect)).coerceAtMost(m.h * 0.17f)
                Logo(logo, maxW = with(d) { (m.w * 0.3f).toPx() }, maxH = with(d) { height.toPx() })
            }
        } else {
            BasicText(
                text = item.title ?: s.app.label,
                style = TextStyle(
                    fontFamily = Type.display,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = with(d) { (m.h * 0.058f).toSp() },
                    lineHeight = with(d) { (m.h * 0.066f).toSp() },
                    letterSpacing = (-0.012f).em,
                    color = Color.White,
                    shadow = Shadow(Color.Black.copy(alpha = 0.3f), Offset(0f, 2f), 18f),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.subtitle?.let {
            BasicText(
                text = it,
                modifier = Modifier.padding(top = m.h * 0.018f),
                style = TextStyle(
                    fontFamily = Type.text,
                    fontWeight = FontWeight.Medium,
                    fontSize = with(d) { (m.h * 0.025f).toSp() },
                    letterSpacing = 0.005.em,
                    color = Colors.secondary,
                    shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 8f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Logo(logo: ImageBitmap, maxW: Float, maxH: Float) {
    // Never drawn more than 1.25× the pixels it was made from, so it stays crisp.
    val scale = minOf(maxW / logo.width, maxH / logo.height, 1.25f)
    val d = LocalDensity.current
    Image(
        bitmap = logo,
        contentDescription = null,
        modifier = Modifier.size(with(d) { (logo.width * scale).toDp() }, with(d) { (logo.height * scale).toDp() }),
        filterQuality = FilterQuality.High,
    )
}

/**
 * Top right, on the Top Shelf: the time, and Settings as a small glass button. Reached by
 * pressing up from the top row; like tvOS buttons it turns white when focused. Both step aside
 * while browsing.
 */
@Composable
internal fun TopBar(
    m: Metrics,
    browse: () -> Float,
    settings: FocusRequester,
    canFocus: Boolean,
    onSettings: () -> Unit,
    onDown: () -> Unit,
) {
    val context = LocalContext.current
    val d = LocalDensity.current
    val time by produceState(DateFormat.getTimeFormat(context).format(Date())) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            value = DateFormat.getTimeFormat(context).format(Date())
        }
    }
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 380f), label = "settings")
    val h = m.h * 0.054f
    val rim = Brush.verticalGradient(listOf(Colors.rimTop, Colors.rimBottom))
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = m.titleY * 0.85f, end = m.edge)
                .graphicsLayer { alpha = 1f - browse() },
            horizontalArrangement = Arrangement.spacedBy(m.w * 0.008f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = time,
                modifier = Modifier
                    .height(h)
                    .background(Colors.pill, RoundedCornerShape(50))
                    .border(1.dp, rim, RoundedCornerShape(50))
                    .padding(horizontal = m.w * 0.014f, vertical = h * 0.2f),
                style = TextStyle(
                    fontFamily = Type.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = with(d) { (h * 0.44f).toSp() },
                    color = Color.White.copy(alpha = 0.92f),
                ),
            )
            Box(
                Modifier
                    .size(h)
                    .focusRequester(settings)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { e ->
                        when {
                            e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter -> {
                                if (e.type == KeyEventType.KeyUp) onSettings()
                                true
                            }
                            e.key == Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) onDown(); true }
                            e.key == Key.DirectionUp || e.key == Key.DirectionRight -> true
                            else -> false
                        }
                    }
                    .focusProperties { this.canFocus = canFocus }
                    .focusable()
                    .graphicsLayer {
                        val k = 1f + 0.18f * lift
                        scaleX = k; scaleY = k
                    }
                    .drawBehind {
                        val r = CornerRadius(size.minDimension / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.16f + 0.84f * lift), cornerRadius = r)
                        drawRoundRect(rim, cornerRadius = r, style = Stroke(1.dp.toPx()), alpha = 1f - lift)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_settings_gear),
                    contentDescription = context.getString(R.string.settings),
                    modifier = Modifier.size(h * 0.56f),
                    colorFilter = ColorFilter.tint(lerp(Color.White.copy(alpha = 0.9f), Color(0xFF1C1C1E), lift)),
                )
            }
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)
