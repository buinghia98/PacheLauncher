package com.teampacheworks.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.teampacheworks.launcher.log.LauncherLog
import com.google.android.material.materialswitch.MaterialSwitch
import com.teampacheworks.launcher.mods.ModEnabledStore
import com.teampacheworks.launcher.mods.ModEntry
import com.teampacheworks.launcher.mods.ModMetadata

/**
 * Manage Mods - a per-mod on/off list, plus the master "Enable mods" switch.
 *
 * Shaped after [GpuDriverManagementActivity] (nullable host config, `finish()` when absent) and
 * [SaveManagementActivity] (standalone toolbar, `removeAllViews()` + one built row per item). The
 * only new thing is the row control: a [MaterialSwitch] where the save screen puts a right-hand
 * TextView.
 *
 * Every toggle persists immediately - there is no Apply button - because the state that matters is
 * read at the *next game launch*, not at the moment of the tap, and a screen that can be left by
 * the system Back gesture must not be able to lose a change.
 */
class ModManagementActivity : AppCompatActivity() {

    private val config get() = requireNotNull(LauncherHost.config.modManagement)
    private lateinit var prefs: SharedPreferences

    private lateinit var master: MaterialSwitch
    private lateinit var list: LinearLayout
    private lateinit var libraries: TextView

    private val rowSwitches = LinkedHashMap<String, MaterialSwitch>()

    /** Recomputed in [buildRows] from the tree on disk; a partial install has fewer than all. */
    private var available: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LauncherHost.config.modManagement == null) { finish(); return }
        setContentView(R.layout.activity_mod_management)
        prefs = getSharedPreferences(LauncherHost.config.prefsName, Context.MODE_PRIVATE)

        findViewById<MaterialToolbar>(R.id.pl_mods_toolbar).setNavigationOnClickListener { finish() }

        master = findViewById(R.id.pl_mods_master)
        list = findViewById(R.id.pl_mods_list)
        libraries = findViewById(R.id.pl_mods_libraries)

        master.isChecked = ModEnabledStore.masterEnabled(prefs, config)
        master.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(config.masterPrefsKey, checked).apply()
            persist()
        }

        buildRows()
        refresh()
    }

    private fun buildRows() {
        list.removeAllViews()
        rowSwitches.clear()
        available = config.availableMods(this)
        // A vanilla install carries no mod stack at all, and that is a supported package rather
        // than a broken one. One sentence beats five rows greyed out for the same reason; a
        // PARTIAL install still shows every row, because there the difference between them is
        // exactly what the player needs to see.
        if (available.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.pl_mods_none_installed)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(secondaryTextColor())
            })
            return
        }
        for (mod in config.mods) list.addView(row(mod))
    }

    /**
     * One mod row: icon on the left, then a column of [name in bold] / [author, version] /
     * [description], with the switch on the right.
     *
     * Everything textual except the switch comes from the MOD's own metadata where it has any
     * ([ModMetadata]); the [ModEntry] supplies only fallbacks. That is why the row is built here
     * rather than inflated from a layout - the pieces that exist vary per mod, and a row for a mod
     * with no icon and no description must collapse cleanly instead of leaving gaps.
     *
     * Built in code and sized off the display density rather than a dimen resource, so a host
     * cannot restyle this list out of alignment with the rest of the library's screens.
     */
    private fun row(mod: ModEntry): View {
        val dp = resources.displayMetrics.density
        val meta = ModMetadata.read(config.modFiles(this, mod.guid), resources)

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        }

        // Icon, only when the mod actually ships one. No placeholder: a grey square for the mods
        // that have no icon looks like a failed load, and an absent icon is not a failure.
        meta.icon?.let { art ->
            line.addView(ImageView(this).apply {
                setImageDrawable(art)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                rightMargin = (12 * dp).toInt()
            })
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(TextView(this).apply {
            text = mod.label.ifBlank { meta.name ?: mod.guid }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        })

        // "Author - v1.2.0", dropping whichever half is unknown rather than printing "null" or a
        // bare "v". The version is the PINNED one the staging step wrote, not the package's own
        // string, because several packages ship a version number that lags what was pinned.
        val author = meta.author ?: mod.author
        val version = meta.version ?: mod.version
        listOfNotNull(author, version?.let { getString(R.string.pl_mods_version, it) })
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" • ")
            ?.let { byline ->
                column.addView(TextView(this).apply {
                    text = byline
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(secondaryTextColor())
                })
            }

        // The mod's own description. Capped at three lines and ellipsized: these are Thunderstore
        // blurbs written to no length limit, and one long one must not push every other row off
        // the screen.
        (meta.description ?: mod.hint.takeIf { it.isNotBlank() })?.let { desc ->
            column.addView(TextView(this).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(secondaryTextColor())
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )).also { layoutParams = it.apply { topMargin = (2 * dp).toInt() } }
            })
        }

        // Cross-mod interactions, which no package blurb can know about. Emphasised rather than
        // folded into the description above, because it is the one line on this screen that is
        // about a DIFFERENT row than the one it appears under.
        mod.note?.takeIf { it.isNotBlank() }?.let { note ->
            column.addView(TextView(this).apply {
                text = note
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.ITALIC)
                (LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )).also { layoutParams = it.apply { topMargin = (4 * dp).toInt() } }
            })
        }

        // Why this row cannot be switched on. Shown INSTEAD of leaving a dead control unexplained:
        // the mod is in this build's list but its data is not in this install's package, and the
        // player has no other way to find that out.
        if (mod.guid !in available) {
            column.addView(TextView(this).apply {
                text = mod.unavailableNote ?: getString(R.string.pl_mods_unavailable)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                (LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )).also { layoutParams = it.apply { topMargin = (4 * dp).toInt() } }
            })
        }

        val sw = MaterialSwitch(this).apply {
            isChecked = prefs.getBoolean(config.prefsKeyFor(mod.guid), mod.defaultOn)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(config.prefsKeyFor(mod.guid), checked).apply()
                persist()
            }
        }
        rowSwitches[mod.guid] = sw

        line.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (12 * dp).toInt()
        })
        return line
    }

    /**
     * Writes the enabled-set file and re-renders everything derived from it.
     *
     * Called on EVERY switch change, master included - which is what keeps the required-libraries
     * line honest. That line is a function of the current selection (the dependency closure of the
     * checked rows), not of what was on screen when it was drawn, so it has to be recomputed with
     * the rest rather than written once in onCreate.
     *
     * A failed write is reported through the log rather than an on-screen line: the only way it
     * fails is external storage being unavailable, in which case the game cannot be launched
     * either, and a permanent status paragraph under the list cost more space than it earned.
     */
    private fun persist() {
        if (!ModEnabledStore.write(this, prefs, config)) {
            LauncherLog.w("mods") { "could not write the enabled-mods file" }
        }
        refresh()
    }

    private fun refresh() {
        val on = master.isChecked
        // Two independent reasons a switch is dead, and they compose: the master is off, or this
        // particular mod's files are not in this install. Only the second survives turning the
        // master back on, which is why it is not folded into the list-wide alpha below.
        for ((guid, sw) in rowSwitches) sw.isEnabled = on && guid in available
        list.alpha = if (on) 1f else 0.5f

        // Recomputed here, not cached: the base runtime is unconditional but the rest is the
        // dependency closure of whatever is checked right now, so this line changes on every tap.
        val active = ModEnabledStore.activeLibraries(this, prefs, config)
        libraries.text = if (!on || active.isEmpty()) {
            getString(R.string.pl_mods_libraries_none)
        } else {
            getString(
                R.string.pl_mods_libraries,
                active.size,
                active.joinToString(", ") { config.libraryLabels[it] ?: it }
            )
        }
    }


    /** `?android:attr/textColorSecondary`, read as a ColorStateList - it is never a plain int. */
    private fun secondaryTextColor(): android.content.res.ColorStateList {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        val csl = ta.getColorStateList(0)
        ta.recycle()
        return csl ?: android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
    }
}
