package com.teampacheworks.launcher.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.teampacheworks.launcher.LauncherContract
import kotlin.math.roundToInt

/**
 * Engine-agnostic letterboxing helper: the largest centred rect of a requested aspect ratio that
 * fits a container's real bounds, applied to any [View] whose parent is a [FrameLayout] (a plain
 * `GLSurfaceView`/`SurfaceView` works fine here - it only needs to be *a* View, not a `ViewGroup`).
 *
 * This library does not require a host to use this helper - a game Activity is free to letterbox
 * however its own engine prefers - but most engines render into a surface that scales to fill
 * whatever `ViewGroup.LayoutParams` size it is given, which is exactly what [apply] computes and
 * sets, so this covers the common case with a couple of lines from the host's game Activity:
 *
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     val aspect = intent.getStringExtra(LauncherContract.EXTRA_ASPECT)
 *     ...
 *     AspectFit.apply(gameSurfaceView, containerWidth, containerHeight, aspect)
 * }
 * ```
 */
object AspectFit {

    /** Largest centred `w x h` of [ratio] (width/height) that fits inside `containerW x containerH`. */
    fun fit(containerW: Int, containerH: Int, ratio: Float): Pair<Int, Int> {
        val heightIfWidthBound = containerW / ratio
        return if (heightIfWidthBound <= containerH) {
            containerW to heightIfWidthBound.roundToInt()
        } else {
            (containerH * ratio).roundToInt() to containerH
        }
    }

    /**
     * Resolves one of [LauncherContract]'s standard aspect values (or any other host-defined
     * value, via [customRatios]) to a ratio, or null for "fill the container" (the `"full"` value,
     * and anything unrecognised).
     */
    fun ratioOf(aspectValue: String?, customRatios: Map<String, Float> = emptyMap()): Float? =
        when (aspectValue) {
            LauncherContract.ASPECT_16_9 -> 16f / 9f
            LauncherContract.ASPECT_4_3 -> 4f / 3f
            else -> customRatios[aspectValue]
        }

    /**
     * Sets [view]'s `FrameLayout.LayoutParams` to the largest centred rect of [ratio] that fits
     * `containerW x containerH`, or to `MATCH_PARENT` when [ratio] is null. A no-op when the
     * computed size already matches, so it is safe to call from a layout listener on every pass.
     *
     * @return the applied (width, height) in px, useful for positioning an [FpsOverlayLayout].
     */
    fun apply(view: View, containerW: Int, containerH: Int, ratio: Float?): Pair<Int, Int> {
        val (targetW, targetH) = if (ratio == null) {
            ViewGroup.LayoutParams.MATCH_PARENT to ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            fit(containerW, containerH, ratio)
        }
        val lp = view.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(targetW, targetH)
        if (lp.width != targetW || lp.height != targetH || lp.gravity != Gravity.CENTER) {
            lp.width = targetW
            lp.height = targetH
            lp.gravity = Gravity.CENTER
            view.layoutParams = lp
        }
        val resolvedW = if (targetW > 0) targetW else containerW
        val resolvedH = if (targetH > 0) targetH else containerH
        return resolvedW to resolvedH
    }
}
