package com.bcam.app

/** Keeps a dragged overlay inside the available display, even after rotation. */
internal object OverlayDragPosition {
    data class Position(val x: Int, val y: Int)
    fun clamp(x: Int, y: Int, screenWidth: Int, screenHeight: Int,
              overlayWidth: Int, overlayHeight: Int): Position =
        Position(
            x.coerceIn(0, (screenWidth - overlayWidth).coerceAtLeast(0)),
            y.coerceIn(0, (screenHeight - overlayHeight).coerceAtLeast(0)),
        )
}
