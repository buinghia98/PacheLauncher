package com.teampacheworks.launcher

/**
 * A sub-screen that a group of [LauncherOption]s lives on instead of the Video settings card.
 *
 * The Video settings card is a single scrolling column, and it stays readable only while every row on it
 * is about a different subject. A feature that needs four dropdowns of its own (an on-screen
 * gamepad, say) pushes everything else off the screen and reads as noise to the player who does not
 * use it. Such a feature gets one navigation button and a screen behind it - the pattern
 * [GpuDriverManagementActivity] and [ModManagementActivity] already establish - rather than four
 * more rows.
 *
 * Nothing about persistence or the launch handoff changes when an option moves onto a screen: it is
 * still a [LauncherConfig.gameOptions] entry, still persisted under [LauncherOption.prefsKey], and
 * still handed to the game activity as [LauncherOption.extraName]. Only where the Spinner is drawn
 * differs, which is why moving an existing option onto a screen cannot reset a player's choice.
 *
 * @param key Stable identifier, matched by [LauncherOption.screenKey]. Not persisted anywhere - it
 *   only travels in the Intent that opens [OptionScreenActivity] - but keep it unique.
 * @param title The screen's toolbar title AND the label of the button on the Controls card, so the
 *   row the player taps and the screen they land on carry the same words (e.g. "Virtual gamepad").
 * @param hint Optional 12sp paragraph at the top of the screen, in the position
 *   [ModManagementActivity] puts its own. Use it for what the dropdowns below cannot say - where
 *   the rest of the feature's controls live, typically.
 * @param actionLabel Label of an optional outlined button drawn under the rows. The one escape
 *   hatch from "pick one of N": a feature whose remaining settings are continuous (a drag-and-drop
 *   layout, say) can hand them to a host screen of its own instead of pretending they are a
 *   dropdown. Null (the default) draws no button.
 * @param actionHint Optional 11sp line under that button, in the geometry of an option's hint.
 * @param actionActivityClass The host Activity the button starts. Required when [actionLabel] is
 *   set; the library starts it with a bare Intent and reads nothing back.
 */
data class LauncherOptionScreen(
    val key: String,
    val title: String,
    val hint: String? = null,
    val actionLabel: String? = null,
    val actionHint: String? = null,
    val actionActivityClass: Class<out android.app.Activity>? = null
) {
    init {
        require(key.isNotBlank()) { "LauncherOptionScreen key must not be blank" }
        require(title.isNotBlank()) { "LauncherOptionScreen '$key' has a blank title" }
        require((actionLabel == null) == (actionActivityClass == null)) {
            "LauncherOptionScreen '$key' needs actionLabel and actionActivityClass together"
        }
    }
}
