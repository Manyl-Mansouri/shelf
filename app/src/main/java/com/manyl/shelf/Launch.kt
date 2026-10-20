package com.manyl.shelf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** An app being opened: which, and where its tile sat on screen (unscaled, in window pixels). */
@Immutable
internal class Launch(val app: TvApp, val from: Rect)

/** Ease in and out: a soft start, then a long, gentle landing, the way tvOS opens apps. */
private val Zoom = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)

/**
 * Opening an app the tvOS way. The focused tile grows to fill the screen, keeping its rounded
 * corners all the way, and turns into the app as you left it ([AppSnapshots]), or the first
 * time, a deep shade of its colour. The shade is always dark: a white banner (YouTube) never
 * floods a dark room with white. Coming back, the app's picture shrinks back into the tile.
 *
 * Everything is one rounded rectangle and one bitmap drawn per frame, so it costs the GPU
 * about one extra full-screen fill while it runs, and nothing at rest.
 */
@Composable
internal fun LaunchZoom(
    launch: Launch?,
    m: Metrics,
    returnSignal: Int,
    homePress: Int,
    onOpen: (TvApp) -> Boolean,
    onDone: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val radius = with(LocalDensity.current) { m.radius.toPx() }

    LaunchedEffect(launch) {
        if (launch == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(480, easing = Zoom))
        // Couldn't open it: shrink back as if returning.
        if (!onOpen(launch.app)) {
            progress.animateTo(0f, tween(320, easing = Zoom))
            onDone()
        }
    }
    val window = LocalWindowInfo.current
    LaunchedEffect(returnSignal) {
        if (launch == null || progress.value < 1f) return@LaunchedEffect
        // Shelf hears it's back before the system's return transition has shown it; shrink only
        // once Shelf actually has the screen, or the shrink plays unseen behind the transition.
        // Never wait forever, or a missed focus change would leave the screen covered.
        withTimeoutOrNull(1500) { snapshotFlow { window.isWindowFocused }.first { it } }
        progress.animateTo(0f, tween(480, easing = Zoom))
        onDone()
    }
    // Home while the tile is still growing: call the open off and shrink back. (This takes over
    // the zoom, which ends the opening effect before it starts the app.)
    LaunchedEffect(homePress) {
        if (homePress == 0 || launch == null || progress.value >= 1f) return@LaunchedEffect
        progress.animateTo(0f, tween(320, easing = Zoom))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().drawBehind {
            val l = launch ?: return@drawBehind
            val p = progress.value
            if (p <= 0.001f) return@drawBehind
            // Start from the tile as you see it: lifted to 1.2× in focus.
            val start = l.from.let { r -> Rect(r.center - Offset(r.width * 0.6f, r.height * 0.6f), Size(r.width * 1.2f, r.height * 1.2f)) }
            val r = lerp(start, Rect(Offset.Zero, size), p)
            // Rounded all the way: the tile's corners grow into a screen-sized curve, and only
            // the final, full-screen frame is square.
            val screenCorner = size.minDimension * 0.035f
            val corner = CornerRadius(if (p >= 0.999f) 0f else radius * 1.2f + (screenCorner - radius * 1.2f) * p)
            // The home screen recedes behind it.
            drawRect(Color.Black, alpha = 0.55f * p)
            clipPath(Path().apply { addRoundRect(RoundRect(r, corner)) }) {
                drawImage(
                    l.app.tile,
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low,
                )
                // The tile gives way early, before it's big enough to glare, to where you left
                // the app (like tvOS) or, the first time, to the app's deep shade.
                val t = ((p - 0.04f) / 0.36f).coerceIn(0f, 1f)
                val a = t * t * (3 - 2 * t)
                val shot = AppSnapshots.of(l.app)
                if (shot != null) {
                    drawImage(
                        shot.image,
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
                        alpha = a,
                        filterQuality = FilterQuality.Low,
                    )
                } else {
                    drawRect(Color(Artwork.deepShade(l.app.color)), alpha = a)
                }
            }
        },
    )
}

private fun lerp(a: Rect, b: Rect, t: Float) = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
