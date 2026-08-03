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
 * A host-declared, engine-neutral enumerated setting rendered on the Settings card as one more
 * Spinner row, in exactly the shape the aspect-ratio row already has (14sp label -> plain
 * framework `Spinner` -> 11sp hint; docs/UI-SPEC.md "Main screen").
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
 *   inside the Settings card that can be misread has one, and a bare dropdown with no hint is the
 *   one way a new row can break the Dustaet standard while still compiling.
 * @param choices The dropdown contents, in display order. Must not be empty.
 * @param defaultValue Must equal one of [choices]'s values; defaults to the first choice.
 */
data class LauncherOption(
    val key: String,
    val label: String,
    val hint: String,
    val choices: List<LauncherOptionChoice>,
    val defaultValue: String = choices.first().value
) {
    init {
        require(choices.isNotEmpty()) { "LauncherOption '$key' has no choices" }
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
