package com.manyl.shelf

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch



private sealed interface EditAction {
    data object MoveToTop : EditAction
    data object Hide : EditAction
    /** For an HDMI device: which port it's really on (some announce the wrong one). */
    data class Port(val number: Int, val current: Boolean) : EditAction
}

@Composable
private fun EditAction.label(): String = when (this) {
    EditAction.MoveToTop -> stringResource(R.string.move_to_top)
    EditAction.Hide -> stringResource(R.string.hide)
    is EditAction.Port -> stringResource(R.string.hdmi_port, number) + if (current) "  ✓" else ""
}

/**
 * tvOS-style "jiggle" mode: one app lifted out of the grid and carried around with the D-pad.
 * Nothing is saved until the app is dropped, so Back can put everything back as it was.
 */
@Stable
private class Editor {
    /** The app being moved, or null when the grid is at rest. */
    var key by mutableStateOf<String?>(null)
    /** The grid as it's being rearranged. */
    var order by mutableStateOf<List<TvApp>?>(null)
    var menu by mutableStateOf<List<EditAction>?>(null)
    var selected by mutableIntStateOf(0)
    /** A fresh OK press started while editing; its release drops the app (or picks the menu item). */
    var armed = false
}

@Composable
fun HomeScreen(vm: HomeViewModel, onLaunch: (TvApp) -> Boolean, onSettings: () -> Unit) {
    val apps = vm.apps
    val reveal by animateFloatAsState(
        targetValue = if (apps.isEmpty()) 0f else 1f,
        animationSpec = tween(600, easing = LinearOutSlowInEasing),
        label = "reveal",
    )
    // MainActivity drops the window background (a full-screen fill under opaque art), so black
    // is painted here only until Home has faded in.
    Box(Modifier.fillMaxSize().drawBehind { if (reveal < 1f) drawRect(Color.Black) }) {
        if (apps.isNotEmpty()) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
                Home(vm, apps, onLaunch, onSettings)
            }
        }
    }
}

@Composable
private fun Home(
    vm: HomeViewModel,
    apps: List<TvApp>,
    onLaunch: (TvApp) -> Boolean,
    onSettings: () -> Unit,
) {
    val editor = remember { Editor() }
    var launching by remember { mutableStateOf<Launch?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    // True once "Hidden apps" fully covers the grid, so the grid stops drawing underneath it.
    var gridCovered by remember { mutableStateOf(false) }
    var hiddenClosed by remember { mutableIntStateOf(0) }
    var lastRestored by remember { mutableStateOf<String?>(null) }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    // Where focus should land after the next list change. Kept apart from [focusedKey] because
    // Compose re-homes focus on its own when the focused tile leaves the grid.
    val pendingFocus = remember { arrayOfNulls<String>(1) }

    // The grid: the user's apps (or the arrangement in progress), then "Hidden apps" if there are any.
    val shown = editor.order ?: apps
    val hiddenTile = vm.hiddenTile?.takeIf { vm.hidden.isNotEmpty() }
    val tiles = remember(shown, hiddenTile) { if (hiddenTile != null) shown + hiddenTile else shown }

    fun startEdit(app: TvApp) {
        if (app.isHiddenTile) return
        editor.armed = false
        editor.menu = null
        editor.order = apps
        editor.key = app.key
    }
    fun endEdit() {
        editor.key = null
        editor.order = null
        editor.menu = null
    }
    fun commit(arranged: List<TvApp>? = editor.order) {
        if (arranged != null && arranged.map { it.key } != apps.map { it.key }) vm.setOrder(arranged)
    }
    fun moveBy(dx: Int, dy: Int) {
        val list = editor.order?.toMutableList() ?: return
        val i = list.indexOfFirst { it.key == editor.key }
        val last = list.lastIndex
        val col = i % COLUMNS
        val j = when {
            dx < 0 -> if (col > 0) i - 1 else i
            dx > 0 -> if (col < COLUMNS - 1 && i < last) i + 1 else i
            dy < 0 -> if (i >= COLUMNS) i - COLUMNS else i
            dy > 0 -> if (i / COLUMNS < last / COLUMNS) minOf(i + COLUMNS, last) else i
            else -> i
        }
        if (j == i) return
        list.add(j, list.removeAt(i))
        editor.order = list
    }
    fun openMenu() {
        val list = editor.order ?: return
        val i = list.indexOfFirst { it.key == editor.key }
        val items = buildList {
            if (i >= COLUMNS) add(EditAction.MoveToTop)
            if (!list[i].isSettings) add(EditAction.Hide)
            if (list[i].isInput) {
                val current = vm.portOf(list[i])
                vm.ports().forEach { add(EditAction.Port(it, it == current)) }
            }
        }
        if (items.isEmpty()) return
        editor.selected = 0
        editor.menu = items
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // While an app is opening (or closing back into its tile), the remote waits.
            if (launching != null) return@onPreviewKeyEvent true
            if (editor.key == null) return@onPreviewKeyEvent false
            val down = e.type == KeyEventType.KeyDown
            val menu = editor.menu
            when {
                e.key in DPAD -> if (down) {
                    if (menu != null) {
                        val step = if (e.key == Key.DirectionUp) -1 else if (e.key == Key.DirectionDown) 1 else 0
                        editor.selected = (editor.selected + step).coerceIn(0, menu.lastIndex)
                    } else {
                        moveBy(
                            dx = if (e.key == Key.DirectionLeft) -1 else if (e.key == Key.DirectionRight) 1 else 0,
                            dy = if (e.key == Key.DirectionUp) -1 else if (e.key == Key.DirectionDown) 1 else 0,
                        )
                    }
                }
                e.key.isSelect -> if (down) {
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        editor.armed = true
                    } else if (editor.armed && menu == null) {
                        // Holding OK on a lifted app opens its options.
                        editor.armed = false
                        openMenu()
                    }
                } else if (editor.armed) {
                    editor.armed = false
                    val list = editor.order
                    val app = list?.firstOrNull { it.key == editor.key }
                    when (val action = if (menu != null) menu[editor.selected] else null) {
                        null -> commit()
                        EditAction.MoveToTop -> if (list != null && app != null) {
                            commit(listOf(app) + list.filter { it.key != app.key })
                        }
                        EditAction.Hide -> if (list != null && app != null) {
                            val i = list.indexOf(app)
                            commit()
                            // Focus lands on the app that slides into its place.
                            pendingFocus[0] = (tiles.getOrNull(i + 1) ?: tiles.getOrNull(i - 1))?.key
                            vm.hide(app)
                        }
                        is EditAction.Port -> if (app != null) {
                            commit()
                            vm.setPort(app, action.number)
                        }
                    }
                    endEdit()
                }
                e.key == Key.Menu || e.key == Key.MediaPlayPause -> if (down && e.nativeKeyEvent.repeatCount == 0) {
                    if (menu == null) openMenu() else editor.menu = null
                }
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val m = remember(maxWidth, maxHeight) { Metrics(maxWidth, maxHeight) }
        val density = LocalDensity.current
        val shiftPx = with(density) { (m.restY - m.browseTop).toPx() }
        val pitchPx = with(density) { m.pitch.toPx() }
        val stepXPx = with(density) { (m.tileW + m.gap).toPx() }
        val sidePx = with(density) { m.side.toPx() }
        val restYPx = with(density) { m.restY.toPx() }
        fun slotX(i: Int) = sidePx + stepXPx * (i % COLUMNS)
        fun slotY(i: Int) = restYPx + pitchPx * (i / COLUMNS)

        val requesters = remember { HashMap<String, FocusRequester>() }
        fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

        // Derived, so moving along a row doesn't recompose the whole screen: only a change of row
        // does. (A lifted app carries the screen with it as it moves between rows.)
        val focusedRow by remember(tiles) { derivedStateOf { tiles.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0) / COLUMNS } }
        var shelfKey by remember { mutableStateOf(apps.first().key) }

        fun focus(key: String) {
            runCatching { requester(key).requestFocus() }
        }

        // Keep focus on the same app across list refreshes (or its neighbour, if it was just
        // hidden); first app on first launch.
        LaunchedEffect(apps, hiddenTile) {
            if (showHidden) return@LaunchedEffect
            withFrameNanos { }
            val wanted = pendingFocus[0] ?: focusedKey
            pendingFocus[0] = null
            focus(wanted?.takeIf { k -> tiles.any { it.key == k } } ?: tiles.first().key)
        }
        // Home on Home: back to the first app, unless it's calling off an app mid-zoom.
        LaunchedEffect(vm.homePress) {
            if (vm.homePress != 0 && launching == null) vm.goHome()
        }
        LaunchedEffect(vm.homeSignal) {
            if (vm.homeSignal == 0) return@LaunchedEffect
            endEdit()
            showHidden = false
            withFrameNanos { }
            focus(apps.first().key)
        }
        // Back from "Hidden apps": return to its tile, or to the last app put back if the tile is
        // gone. That app may still be loading; if so the refresh above picks up the pending focus.
        LaunchedEffect(hiddenClosed) {
            if (hiddenClosed == 0) return@LaunchedEffect
            withFrameNanos { }
            val pending = pendingFocus[0]
            val target = listOfNotNull(pending, focusedKey).firstOrNull { k -> tiles.any { it.key == k } }
            if (target != null && target == pending) pendingFocus[0] = null
            focus(target ?: tiles.first().key)
        }
        // The Top Shelf follows focus along the top row, with a short settle so fast
        // scrubbing doesn't strobe through artwork.
        LaunchedEffect(Unit) {
            snapshotFlow { focusedKey to focusedRow }.collectLatest { (key, row) ->
                if (key == null || row != 0) return@collectLatest
                delay(110)
                shelfKey = key
            }
        }
        // The Top Shelf: one thing from the app under focus. It's a picture, not a menu.
        val shelfApp = tiles.firstOrNull { it.key == shelfKey } ?: tiles.first()
        val item = vm.shelves[shelfApp.key]
        LaunchedEffect(shelfApp.key) { vm.loadShelf(shelfApp) }
        LaunchedEffect(item?.id) { item?.let { vm.ensureArt(shelfApp, it) } }
        var shown by remember { mutableStateOf<Shown?>(null) }
        val hero = item?.let { vm.art[it.id] }
        LaunchedEffect(item, hero) { if (item != null && hero != null) shown = Shown(shelfApp, item, hero) }
        val settingsRequester = remember { FocusRequester() }

        BackHandler {
            when {
                editor.menu != null -> editor.menu = null
                editor.key != null -> endEdit() // Everything springs back to where it was.
                focusedRow > 0 -> focus(shelfKey)
            }
        }

        // Single vertical offset for the whole screen; only layer properties animate.
        val scroll = remember { Animatable(0f) }
        LaunchedEffect(focusedRow, shiftPx) {
            val target = if (focusedRow == 0) 0f else -(shiftPx + pitchPx * (focusedRow - 1))
            scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 340f))
        }
        val browse = { (-scroll.value / shiftPx).coerceIn(0f, 1f) }
        val rowShift = { scroll.value }

        Stage(shown, m, browse = browse, rowShift = rowShift)
        ShelfTitle(shown, m, browse = browse)
        TopBar(
            m = m,
            browse = browse,
            settings = settingsRequester,
            // Reachable by pressing up from the top row, and only from there.
            canFocus = focusedRow == 0 && editor.key == null && !showHidden,
            onSettings = onSettings,
            onDown = { focus(focusedKey ?: shelfKey) },
        )

        // One flat, keyed list rather than rows, so a tile keeps its state (and focus) when it
        // moves to another row. Each tile springs to its slot on the layer.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = rowShift()
                alpha = if (gridCovered) 0f else 1f
            },
        ) {
            tiles.forEachIndexed { i, app ->
                key(app.key) {
                    Slot(slotX(i), slotY(i)) {
                        AppTile(
                            app = app,
                            m = m,
                            requester = requester(app.key),
                            enabled = !showHidden,
                            jiggling = editor.key == app.key,
                            onFocused = { focusedKey = app.key },
                            onClick = { bounds -> if (app.isHiddenTile) showHidden = true else launching = Launch(app, bounds) },
                            onLongPress = { startEdit(app) },
                            // Like tvOS, icons in the dock go unlabelled.
                            showLabel = i >= COLUMNS,
                        )
                    }
                }
            }
            val menu = editor.menu
            val i = tiles.indexOfFirst { it.key == editor.key }
            if (menu != null && i >= 0) {
                EditMenu(menu, editor.selected, m, tileX = slotX(i), tileY = slotY(i))
            }
        }

        EditHint(visible = editor.key != null, m = m)
        LaunchZoom(launching, m, vm.returnSignal, vm.homePress, onOpen = onLaunch, onDone = { launching = null })

        if (showHidden) {
            HiddenApps(
                apps = vm.hidden,
                m = m,
                onRestore = { app ->
                    lastRestored = app.key
                    vm.unhide(app)
                },
                onCovered = { gridCovered = it },
                onClose = {
                    gridCovered = false
                    pendingFocus[0] = if (vm.hidden.isNotEmpty()) focusedKey else lastRestored
                    showHidden = false
                    hiddenClosed++
                },
            )
        }
    }
}

private val TILE_SHADOW by lazy { Artwork.tileShadow.asImageBitmap() }

private val DPAD = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

private val Key.isSelect get() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter

/** Places [content] at (x, y) on the layer and springs it there when the slot changes. */
@Composable
private fun Slot(x: Float, y: Float, content: @Composable () -> Unit) {
    val target = Offset(x, y)
    val pos = remember { Animatable(target, Offset.VectorConverter) }
    LaunchedEffect(target) {
        if (pos.targetValue != target) pos.animateTo(target, spring(dampingRatio = 0.84f, stiffness = 380f))
    }
    Box(Modifier.graphicsLayer { translationX = pos.value.x; translationY = pos.value.y }) { content() }
}

/** The lifted app's options, floating just above it. */
@Composable
private fun EditMenu(items: List<EditAction>, selected: Int, m: Metrics, tileX: Float, tileY: Float) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
    val highlight by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "highlight",
    )
    val itemH = m.tileH * 0.3f
    val pad = m.tileH * 0.06f
    val w = m.tileW * 1.1f
    val shape = remember(m) { RoundedCornerShape(m.radius * 1.2f) }
    val pill = remember(m) { RoundedCornerShape(m.radius * 0.8f) }
    val textSize = with(LocalDensity.current) { (m.tileW * 0.068f).toSp() }
    Box(
        Modifier
            .width(w)
            .graphicsLayer {
                val height = (itemH * items.size + pad * 2).toPx()
                translationX = tileX - (w - m.tileW).toPx() / 2
                translationY = tileY - height - (m.tileH * 0.16f).toPx()
                val a = appear.value
                alpha = a.coerceIn(0f, 1f)
                scaleX = 0.92f + 0.08f * a
                scaleY = 0.92f + 0.08f * a
                transformOrigin = TransformOrigin(0.5f, 1f)
                shadowElevation = 18.dp.toPx()
                this.shape = shape
                clip = true
            }
            .background(Colors.sheet.copy(alpha = 0.94f))
            .border(1.dp, Brush.verticalGradient(listOf(Colors.rimTop, Colors.rimBottom)), shape)
            .padding(pad),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemH)
                .graphicsLayer { translationY = highlight * itemH.toPx() }
                .background(Color.White, pill),
        )
        Column {
            items.forEachIndexed { i, item ->
                Box(Modifier.fillMaxWidth().height(itemH).padding(horizontal = pad * 2), contentAlignment = Alignment.CenterStart) {
                    BasicText(
                        text = item.label(),
                        style = TextStyle(
                            fontFamily = Type.text,
                            color = if (i == selected) Color.Black else Color.White.copy(alpha = 0.9f),
                            fontSize = textSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** What the remote does while an app is lifted. */
@Composable
private fun EditHint(visible: Boolean, m: Metrics) {
    val shown by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(260, easing = LinearOutSlowInEasing),
        label = "hint",
    )
    val textSize = with(LocalDensity.current) { (m.tileW * 0.056f).toSp() }
    Box(Modifier.fillMaxWidth().padding(top = m.h * 0.045f), contentAlignment = Alignment.TopCenter) {
        BasicText(
            text = stringResource(R.string.edit_hint),
            modifier = Modifier
                .graphicsLayer {
                    alpha = shown
                    translationY = (1f - shown) * -8.dp.toPx()
                }
                .background(Colors.sheet.copy(alpha = 0.82f), RoundedCornerShape(50))
                .border(1.dp, Brush.verticalGradient(listOf(Colors.rimTop, Colors.rimBottom)), RoundedCornerShape(50))
                .padding(horizontal = m.gap * 0.8f, vertical = m.gap * 0.28f),
            style = TextStyle(fontFamily = Type.text, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.88f), fontSize = textSize),
            maxLines = 1,
        )
    }
}

/** A dark glass over Home listing hidden apps; OK puts one back on the grid. */
@Composable
private fun HiddenApps(
    apps: List<TvApp>,
    m: Metrics,
    onRestore: (TvApp) -> Unit,
    onCovered: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    LaunchedEffect(apps.isEmpty()) { if (apps.isEmpty()) onClose() }

    val density = LocalDensity.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(380, easing = LinearOutSlowInEasing))
        onCovered(true)
    }

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val pendingFocus = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(apps) {
        if (apps.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val wanted = pendingFocus[0] ?: focusedKey
        pendingFocus[0] = null
        val key = wanted?.takeIf { k -> apps.any { it.key == k } } ?: apps.first().key
        runCatching { requester(key).requestFocus() }
    }

    val stepXPx = with(density) { (m.tileW + m.gap).toPx() }
    val sidePx = with(density) { m.side.toPx() }
    val topPx = with(density) { (m.h * 0.34f).toPx() }
    val pitchPx = with(density) { m.pitch.toPx() }
    val focusedRow = apps.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0) / COLUMNS
    val scroll = remember { Animatable(0f) }
    LaunchedEffect(focusedRow) {
        scroll.animateTo(-pitchPx * (focusedRow - 1).coerceAtLeast(0), spring(dampingRatio = 1f, stiffness = 340f))
    }
    val titleSize = with(density) { (m.tileW * 0.16f).toSp() }
    val hintSize = with(density) { (m.tileW * 0.065f).toSp() }

    Box(
        Modifier
            .fillMaxSize()
            // Alpha is applied per draw (no offscreen buffer), and the backdrop is opaque so the
            // grid underneath can stop drawing once it's covered.
            .graphicsLayer {
                alpha = appear.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(Color(0xFF0B0B0E)),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = scroll.value }) {
            apps.forEachIndexed { i, app ->
                key(app.key) {
                    Slot(sidePx + stepXPx * (i % COLUMNS), topPx + pitchPx * (i / COLUMNS)) {
                        AppTile(
                            app = app,
                            m = m,
                            requester = requester(app.key),
                            enabled = true,
                            jiggling = false,
                            onFocused = { focusedKey = app.key },
                            onClick = {
                                // Focus moves to the app that slides into its place.
                                pendingFocus[0] = (apps.getOrNull(i + 1) ?: apps.getOrNull(i - 1))?.key
                                onRestore(app)
                            },
                            onLongPress = {},
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(start = m.side, top = m.h * 0.11f)) {
            BasicText(
                text = stringResource(R.string.hidden_apps),
                style = TextStyle(fontFamily = Type.display, color = Color.White, fontSize = titleSize, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = stringResource(R.string.hidden_apps_hint),
                modifier = Modifier.padding(top = m.gap * 0.3f),
                style = TextStyle(fontFamily = Type.text, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.55f), fontSize = hintSize),
            )
        }
    }
}

@Composable
private fun AppTile(
    app: TvApp,
    m: Metrics,
    requester: FocusRequester,
    enabled: Boolean,
    jiggling: Boolean,
    onFocused: () -> Unit,
    onClick: (Rect) -> Unit,
    onLongPress: () -> Unit,
    showLabel: Boolean = true,
) {
    var pressed by remember { mutableStateOf(false) }
    // Set once a held OK has turned into a long press, so its release doesn't also open the app.
    val held = remember { BooleanArray(1) }
    var focused by remember { mutableStateOf(false) }
    // tvOS lifts the focused icon by about a fifth: quick, calm, with barely any overshoot.
    val scale by animateFloatAsState(
        targetValue = when {
            jiggling -> 1.22f
            pressed -> 1.1f
            focused -> 1.2f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 520f),
        label = "scale",
    )
    val lift by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
        label = "lift",
    )
    // The jiggle: a quick rock while the app is lifted; it springs level when dropped.
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(jiggling) {
        if (jiggling) {
            wiggle.animateTo(-1.6f, tween(70))
            while (true) {
                wiggle.animateTo(1.6f, tween(140, easing = FastOutSlowInEasing))
                wiggle.animateTo(-1.6f, tween(140, easing = FastOutSlowInEasing))
            }
        } else if (wiggle.value != 0f) {
            wiggle.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 400f))
        }
    }
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val d = LocalDensity.current
    val labelSize = with(d) { (m.tileH * 0.19f).toSp() }
    val rim = with(d) { 1.2.dp.toPx() }

    Column(Modifier.width(m.tileW), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(m.tileW, m.tileH)
                .onGloballyPositioned { coords[0] = it }
                .focusRequester(requester)
                .onFocusChanged { state ->
                    if (state.isFocused == focused) return@onFocusChanged
                    focused = state.isFocused
                    if (!state.isFocused) { pressed = false; return@onFocusChanged }
                    onFocused()
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = wiggle.value
                    cameraDistance = 14f * density
                }
                // A soft shadow under the lifted icon, as tvOS draws it: a pre-blurred bitmap whose
                // middle is the tile's footprint, centred and only just lower.
                .drawBehind {
                    val l = lift
                    if (l <= 0.01f) return@drawBehind
                    val inset = Artwork.SHADOW_INSET
                    val w = size.width / (1 - 2 * inset)
                    val h = w * TILE_SHADOW.height / TILE_SHADOW.width
                    drawImage(
                        TILE_SHADOW,
                        dstOffset = IntOffset((-w * inset).roundToInt(), ((size.height - h) / 2 + size.height * 0.03f * l).roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        alpha = 0.55f * l,
                        filterQuality = FilterQuality.Low,
                    )
                }
                // OK opens the app on release; held past the long-press timeout it lifts the app
                // instead (the remote starts repeating the key, or adb sends a long-press flag).
                .onKeyEvent { e ->
                    if (!e.key.isSelect) return@onKeyEvent false
                    when (e.type) {
                        KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) {
                            held[0] = false
                            pressed = true
                        } else if (!held[0]) {
                            held[0] = true
                            pressed = false
                            onLongPress()
                        }
                        KeyEventType.KeyUp -> {
                            val click = pressed && !held[0]
                            pressed = false
                            if (click) coords[0]?.takeIf { it.isAttached }?.let { onClick(it.boundsInWindow()) }
                        }
                    }
                    true
                }
                .focusProperties { canFocus = enabled }
                .focusable()
                // The corners and the resting rim are baked into the tile; only the focused tile
                // draws anything extra, and none of it needs a clip.
                .drawWithContent {
                    drawContent()
                    val l = lift
                    if (l <= 0.01f) return@drawWithContent
                    val corner = CornerRadius(m.radius.toPx())
                    // The rim catches more light in focus.
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f * l), Color.White.copy(alpha = 0.08f * l))),
                        cornerRadius = corner,
                        style = Stroke(rim),
                    )
                },
        ) {
            Image(
                bitmap = app.tile,
                contentDescription = app.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
            )
        }
        BasicText(
            text = app.label,
            modifier = Modifier
                .padding(top = m.tileH * 0.2f)
                .graphicsLayer {
                    alpha = if (showLabel) lift else 0f
                    translationY = (1f - lift) * -5.dp.toPx()
                },
            style = TextStyle(
                fontFamily = Type.text,
                fontWeight = FontWeight.Medium,
                color = Colors.label,
                fontSize = labelSize,
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1f), 6f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
