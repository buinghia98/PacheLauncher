package com.teampacheworks.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * A [LinearLayout] that honours `android:maxWidth`.
 *
 * The UI spec's screens are a centred 560dp column, but a fixed `layout_width="560dp"` overflows
 * (and is cropped by) any screen narrower than that - which is most phones in portrait. Layouts
 * should instead use `layout_width="match_parent"` with `android:maxWidth="560dp"` on this class:
 * the column fills narrow screens edge-to-edge and caps at the spec width on wide ones. Keep
 * `layout_gravity="center_horizontal"` so the capped column stays centred.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val maxWidth: Int

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxWidth))
        maxWidth = a.getDimensionPixelSize(0, Int.MAX_VALUE)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var spec = widthMeasureSpec
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        if (mode != MeasureSpec.UNSPECIFIED && size > maxWidth) {
            spec = MeasureSpec.makeMeasureSpec(maxWidth, mode)
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}
