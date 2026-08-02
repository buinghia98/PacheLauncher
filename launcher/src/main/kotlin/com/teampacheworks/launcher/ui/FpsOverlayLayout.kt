package com.teampacheworks.launcher.ui

/**
 * Where an FPS counter should sit inside a root frame that may be letterboxing its content.
 *
 * Split out as pure arithmetic (no View/Activity dependency) purely so it can be unit-tested.
 *
 * A counter that lives in the root of a window (rather than as a child of the letterboxed content
 * itself, which is sometimes not even a `ViewGroup` - e.g. a raw `GLSurfaceView`/`SurfaceView`)
 * needs an explicit offset with plain TOP|END gravity, or it parks in the black letterbox bar
 * instead of the corner of the actual picture. Offsetting by half the difference between the
 * container and the content puts it back on the corner of the picture.
 */
object FpsOverlayLayout {

    /**
     * @param parentW/[parentH] the window/container's size.
     * @param surfaceW/[surfaceH] the letterboxed content's actual size within that container.
     * @param base the plain inset wanted from the picture's edge (e.g. 8dp in px).
     * @return (rightMargin, topMargin) in the same unit as [base], for a TOP|END-gravity overlay.
     */
    fun margins(parentW: Int, parentH: Int, surfaceW: Int, surfaceH: Int, base: Int): Pair<Int, Int> {
        val right = base + ((parentW - surfaceW) / 2).coerceAtLeast(0)
        val top = base + ((parentH - surfaceH) / 2).coerceAtLeast(0)
        return right to top
    }
}
