package com.teampacheworks.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * The screen behind a [LauncherOptionScreen] button: every [LauncherConfig.gameOptions] entry whose
 * [LauncherOption.screenKey] matches, drawn as the same label -> Spinner -> hint rows the Settings
 * card uses ([OptionRows]).
 *
 * Shaped after [ModManagementActivity]: nullable host config (`finish()` when the screen key names
 * nothing this host declared), a standalone toolbar whose navigation arrow is the only way back -
 * no bottom Back button - and every change persisted on the spot rather than behind an Apply, since
 * the system Back gesture must not be able to lose one.
 *
 * It writes ONLY [LauncherOption.prefsKey] entries, and [LauncherActivity] re-reads those in
 * `onResume`, so the two screens cannot fight over who last wrote a value.
 */
class OptionScreenActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = LauncherHost.config
        val key = intent?.getStringExtra(EXTRA_SCREEN_KEY)
        val screen = config.optionScreens.firstOrNull { it.key == key }
        val options = config.gameOptions.filter { it.screenKey != null && it.screenKey == key }
        if (screen == null || options.isEmpty()) { finish(); return }

        setContentView(R.layout.activity_option_screen)
        prefs = getSharedPreferences(config.prefsName, Context.MODE_PRIVATE)

        findViewById<MaterialToolbar>(R.id.pl_option_screen_toolbar).apply {
            title = screen.title
            setNavigationOnClickListener { finish() }
        }

        OptionRows.goneIfBlank(findViewById(R.id.pl_option_screen_hint), screen.hint)

        val container = findViewById<ViewGroup>(R.id.pl_option_screen_container)
        for (option in options) {
            val index = option.indexOf(prefs.getString(option.prefsKey, option.defaultValue))
            OptionRows.add(this, container, option, index) { position ->
                prefs.edit().putString(option.prefsKey, option.choices[position].value).apply()
            }
        }
        addAction(container, screen)
    }

    /** The screen's optional host-supplied action button, and its hint. */
    private fun addAction(container: ViewGroup, screen: LauncherOptionScreen) {
        val label = screen.actionLabel ?: return
        val target = screen.actionActivityClass ?: return
        val button = layoutInflater.inflate(R.layout.pl_option_screen_button, container, false)
            as com.google.android.material.button.MaterialButton
        button.text = label
        button.setOnClickListener { startActivity(android.content.Intent(this, target)) }
        container.addView(button)

        // An emptied actionHint draws no line at all -- see [OptionRows]' "blank text is no element".
        val hint = screen.actionHint?.takeIf { it.isNotBlank() } ?: return
        container.addView(TextView(this).apply {
            text = hint
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            OptionRows.secondaryTextColor(this@OptionScreenActivity)?.let { setTextColor(it) }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
        })
    }

    companion object {
        /** [LauncherOptionScreen.key] of the screen to draw. */
        const val EXTRA_SCREEN_KEY = "com.teampacheworks.launcher.OPTION_SCREEN"
    }
}
