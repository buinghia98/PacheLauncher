package com.teampacheworks.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * The one implementation of a [LauncherOption] row: 14sp label -> plain framework `Spinner` -> 11sp
 * hint, in exactly the geometry of the main screen's aspect row (docs/UI-SPEC.md "Host options").
 * A TOGGLE swaps the first two for one `MaterialSwitch`; a SLIDER swaps them for a label row that
 * also carries the selected choice's label, then a `Slider` with its reset icon ([addSlider]).
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

        if (option.control == LauncherOptionControl.SLIDER) {
            addSlider(context, container, option, selectedIndex, onSelected)
            addHint(context, container, option)
            return
        }

        if (option.control == LauncherOptionControl.DROPDOWN) container.addView(TextView(context).apply {
            text = option.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * dp).toInt() }
        })

        // A hint the host emptied removes the whole line, and the Spinner takes over the 12dp that
        // used to sit under the hint so the next row does not ride up against this one.
        val hasHint = !option.hint.isNullOrBlank()

        if (option.control == LauncherOptionControl.TOGGLE) {
            container.addView(MaterialSwitch(context).apply {
                text = option.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minHeight = (48 * dp).toInt()
                // Attach the listener after restoring state: drawing a row is not a user edit.
                isChecked = selectedIndex == 1
                setOnCheckedChangeListener { _, checked -> onSelected(if (checked) 1 else 0) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ((if (hasHint) 8 else 12) * dp).toInt() }
            })
        } else container.addView(Spinner(context).apply {
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

        addHint(context, container, option)
    }

    /** The 11sp hint line under a control, or nothing at all when the host emptied it. */
    private fun addHint(context: Context, container: ViewGroup, option: LauncherOption) {
        if (option.hint.isNullOrBlank()) return
        val dp = context.resources.displayMetrics.density
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
     * The SLIDER control: a label row (14sp label, and right-aligned the selected choice's label
     * in 14sp secondary, bottom margin 0 because the Slider below carries its own touch padding),
     * then a row of `Slider` (weight 1, one step per choice) + `IconButton` reset
     * (`pl_ic_option_reset`, 20dp, 4dp start margin). The row's bottom margin follows the Spinner
     * rule: 8dp over a hint, 12dp when the host emptied the hint and this row has to carry the
     * separation the hint would have.
     *
     * The choices are an ordered scale here, so the value text IS the choice label and nothing
     * else: the library still never interprets a value. The reset icon sets the slider to
     * [LauncherOption.defaultValue]'s index and lets the change listener report it like any drag,
     * so the caller sees one path for every selection. The one programmatic set that must NOT
     * reach the caller is the restore of the saved index while the row is built (drawing a row is
     * not a user edit, same as the toggle), hence the `suppress` latch around it.
     * `Slider.setValue` throws for a value off the step grid, so only whole floats go in.
     */
    private fun addSlider(
        context: Context,
        container: ViewGroup,
        option: LauncherOption,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val dp = context.resources.displayMetrics.density
        val last = option.choices.size - 1
        val initial = selectedIndex.coerceIn(0, last)

        val valueText = TextView(context).apply {
            text = option.choices[initial].label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            secondaryTextColor(context)?.let { setTextColor(it) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                text = option.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 0 }
        })

        var suppress = false
        val slider = Slider(context).apply {
            valueFrom = 0f
            valueTo = last.toFloat()
            stepSize = 1f
            labelBehavior = LabelFormatter.LABEL_GONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addOnChangeListener { _, value, _ ->
                if (suppress) return@addOnChangeListener
                val index = value.roundToInt().coerceIn(0, last)
                valueText.text = option.choices[index].label
                onSelected(index)
            }
        }
        suppress = true
        slider.value = initial.toFloat()
        suppress = false

        val reset = MaterialButton(
            context, null, com.google.android.material.R.attr.materialIconButtonStyle
        ).apply {
            icon = AppCompatResources.getDrawable(context, R.drawable.pl_ic_option_reset)
            iconSize = (20 * dp).toInt()
            contentDescription = context.getString(R.string.pl_option_reset, option.label)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (4 * dp).toInt() }
            setOnClickListener { slider.value = option.indexOf(option.defaultValue).toFloat() }
        }

        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(slider)
            addView(reset)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ((if (option.hint.isNullOrBlank()) 12 else 8) * dp).toInt() }
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
