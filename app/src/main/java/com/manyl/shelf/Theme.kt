package com.manyl.shelf

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

internal const val COLUMNS = 6

/** Inter: the closest open typeface to Apple's SF Pro. "Display" cuts are drawn for large sizes. */
internal object Type {
    val text = FontFamily(
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )
    val display = FontFamily(
        Font(R.font.inter_display_semibold, FontWeight.SemiBold),
        Font(R.font.inter_display_bold, FontWeight.Bold),
    )
}

internal object Colors {
    val label = Color.White.copy(alpha = 0.78f)
    val secondary = Color.White.copy(alpha = 0.72f)
    val pill = Color.White.copy(alpha = 0.16f)
    val rimTop = Color.White.copy(alpha = 0.42f)
    val rimBottom = Color.White.copy(alpha = 0.08f)
    val sheet = Color(0xFF1C1C1E)
}

/**
 * Layout measured off tvOS 26 at 1920×1080 and kept proportional to the screen, so it holds at
 * any TV density: six 5:3 icons per row, the top row in a glass dock, the next row peeking.
 */
@Stable
internal class Metrics(val w: Dp, val h: Dp) {
    val side = w * 0.047f
    val gap = w * 0.0255f
    val tileW = (w - side * 2 - gap * (COLUMNS - 1)) / COLUMNS
    val tileH = tileW * 0.6f
    val radius = tileH * 0.17f
    val pitch = h * 0.24f
    /** Top row's y while the Top Shelf is showing. */
    val restY = h * 0.745f
    /** Where the row above the focused one sits while browsing lower rows. */
    val browseTop = h * 0.095f
    val dockPadX = w * 0.021f
    val dockPadY = h * 0.037f
    val dockRadius = radius + dockPadY * 0.85f
    val titleX = w * 0.045f
    val titleY = h * 0.072f
    val edge = w * 0.04f
}
