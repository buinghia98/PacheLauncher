package com.teampacheworks.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * The one implementation of a [LauncherOption] row: 14sp label -> plain framework `Spinner` -> 11sp
 * hint, in exactly the geometry of the main screen's aspect row (docs/UI-SPEC.md "Host options").
 *
 * Extracted here because the same row now appears in two places - the Video settings card and any
 * [LauncherOptionScreen] sub-screen - and a second copy of the geometry is precisely how the two
 * would drift apart. The caller owns the selection: this draws the control and reports positions,
 * and knows nothing about SharedPreferences.
 *
 * ## Blank text is "no element", everywhere
 *
 * A host clears a string by emptying it in its `strings.xml`; the launcher must then draw NOTHING,
 * not an empty line. A zero-length 11sp TextView still costs its own bottom margin, so the naive
 * `text = ""` leaves a visible gap exactly where the hint used to be and the column stops looking
 * like a column. Every optional piece of text in this module therefore goes through [goneIfBlank]
 * or the skip below, and the spacing the removed view was carrying is handed to the view above it.
 */
internal object OptionRows {

    /**
     * Give [view] its text, or remove it from the layout entirely when there is none.
     *
     * @param above the view whose bottom margin should absorb [view]'s when [view] disappears, so
     *   the control it described keeps its separation from whatever follows.
     */
    fun goneIfBlank(view: TextView?, text: CharSequence?, above: View? = null) {
        if (view == null) return
        if (text.isNullOrBlank()) {
            val lost = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            view.visibility = View.GONE
            (above?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                if (lost > it.bottomMargin) {
                    it.bottomMargin = lost
                    above.layoutParams = it
                }
            }
        } else {
            view.visibility = View.VISIBLE
            view.text = text
        }
    }

    fun add(
        context: Context,
        container: ViewGroup,
        option: LauncherOption,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val dp = context.resources.displayMetrics.density

        container.addView(TextView(context).apply {
            text = option.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * dp).toInt() }
        })

        // A hint the host emptied removes the whole line, and the Spinner takes over the 12dp that
        // used to sit under the hint so the next row does not ride up against this one.
        val hasHint = !option.hint.isNullOrBlank()

        // Plain framework Spinner, exactly as the aspect row -- NOT an exposed dropdown.
        container.addView(Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                option.choices.map { it.label }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selectedIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    onSelected(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ((if (hasHint) 8 else 12) * dp).toInt() }
        })

        if (!hasHint) return

        container.addView(TextView(context).apply {
            text = option.hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            secondaryTextColor(context)?.let { setTextColor(it) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        })
    }

    /**
     * `?android:attr/textColorSecondary` as the layout XML's hint lines resolve it. Read through
     * `obtainStyledAttributes` rather than `Theme.resolveAttribute` + `getColor` because the
     * attribute is a ColorStateList in every Material theme, and asking for it as a plain colour
     * int throws.
     */
    fun secondaryTextColor(context: Context): ColorStateList? {
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val csl = ta.getColorStateList(0)
        ta.recycle()
        return csl
    }
}
