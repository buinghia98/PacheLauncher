package com.teampacheworks.launcher

/**
 * One selectable value of a [LauncherOption].
 *
 * [value] is what is persisted and handed to the game activity - keep it stable across app
 * versions, exactly as with [AspectOption.value]. [label] is the user-facing string shown in the
 * dropdown (sentence case, docs/UI-SPEC.md "Strings and capitalization policy").
 */
data class LauncherOptionChoice(val value: String, val label: String)

/**
 * Dropdown by default; TOGGLE maps choices[0] to Off and choices[1] to On; SLIDER lays the
 * choices out as the discrete steps of a `Slider`, in list order, with a reset icon beside it.
 */
enum class LauncherOptionControl { DROPDOWN, TOGGLE, SLIDER }

/**
 * A host-declared, engine-neutral enumerated setting rendered on the Video settings card as one more
 * Spinner row by default, or a MaterialSwitch when explicitly declared as a TOGGLE.
 *
 * This exists because [LauncherActivity] is final and every extension point of this library is a
 * [LauncherConfig] field. A port that needs one more "pick one of N" knob - an FPS limiter, a
 * texture-quality level, a scaler mode - would otherwise have to fork the layout XML, which
 * docs/UI-SPEC.md explicitly forbids. The library stays engine-neutral: it knows how to persist a
 * choice, draw the row and forward the selection, and nothing about what the value means.
 *
 * The selection is persisted in [LauncherConfig.prefsName] under `"opt_" + key` and handed to the
 * game activity as the String extra [LauncherContract.extraNameFor], i.e.
 * `"com.teampacheworks.launcher.OPTION." + key`. Both are derived from [key], so [key] must stay
 * stable once players have used the option.
 *
 * @param key Stable, short, lowercase-by-convention identifier (e.g. `"fps_limit"`). Drives both
 *   the SharedPreferences key and the Intent extra name; changing it silently resets the setting.
 * @param label 14sp label drawn above the dropdown (e.g. "FPS limit").
 * @param hint 11sp explanatory line drawn under the dropdown. Required, not optional: every row
 *   inside the Video settings card that can be misread has one, and a bare dropdown with no hint is the
 *   one way a new row can break the Dustaet standard while still compiling.
 * @param choices The dropdown contents, in display order. Must not be empty.
 * @param defaultValue Must equal one of [choices]'s values; defaults to the first choice.
 * @param screenKey When non-null and matching a [LauncherConfig.optionScreens] entry, this row is
 *   drawn on that sub-screen instead of on the Video settings card, and the Controls card carries one navigation
 *   button for the whole group ([LauncherOptionScreen]). Nothing else changes: [prefsKey] and
 *   [extraName] are still derived from [key] alone, so moving an option onto a screen - or back off
 *   one - can never reset a player's choice or alter what the game process receives.
 * @param control TOGGLE requires exactly two distinct choices ordered Off, On. Their values remain
 *   opaque strings; persistence and Intent forwarding are identical to a dropdown. SLIDER requires
 *   at least two choices: they become the slider's discrete steps in list order, the selected
 *   choice's label is shown beside the option label, and a reset icon restores [defaultValue].
 *   Meant for an ordered scale (a size, a percentage, a quality level) where a dropdown hides the
 *   order the values have; again nothing about persistence or the Intent extra changes.
 */
data class LauncherOption(
    val key: String,
    val label: String,
    val hint: String,
    val choices: List<LauncherOptionChoice>,
    val defaultValue: String = choices.first().value,
    val screenKey: String? = null,
    val control: LauncherOptionControl = LauncherOptionControl.DROPDOWN
) {
    init {
        require(choices.isNotEmpty()) { "LauncherOption '$key' has no choices" }
        require(control != LauncherOptionControl.TOGGLE ||
            (choices.size == 2 && choices[0].value != choices[1].value)) {
            "LauncherOption '$key' toggle requires two distinct choices, Off then On"
        }
        require(control != LauncherOptionControl.SLIDER || choices.size >= 2) {
            "LauncherOption '$key' slider requires at least two choices"
        }
        require(choices.any { it.value == defaultValue }) {
            "LauncherOption '$key' defaultValue='$defaultValue' is not one of its choices"
        }
    }

    /** SharedPreferences key this option's selection is persisted under. */
    val prefsKey: String get() = "opt_$key"

    /** Intent extra name the selected [LauncherOptionChoice.value] is handed to the game under. */
    val extraName: String get() = LauncherContract.extraNameFor(key)

    /** Index of [value] in [choices], or the index of [defaultValue] when it is not a valid one. */
    fun indexOf(value: String?): Int {
        val i = choices.indexOfFirst { it.value == value }
        if (i >= 0) return i
        val d = choices.indexOfFirst { it.value == defaultValue }
        return if (d >= 0) d else 0
    }
}
