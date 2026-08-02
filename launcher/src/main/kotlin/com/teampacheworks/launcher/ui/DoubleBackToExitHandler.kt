package com.teampacheworks.launcher.ui

import android.os.SystemClock

/**
 * "Press Back again to exit" - a generic double-tap-within-a-window helper, engine- and
 * Activity-agnostic. A host's game Activity calls [onBackPressed] from wherever it intercepts the
 * BACK key (`dispatchKeyEvent`, `onKeyDown`, or `OnBackPressedCallback` - whichever its engine
 * integration needs), typically before handing BACK to the engine at all, since most engines
 * either consume BACK themselves or need it to not reach them mid-double-tap.
 *
 * ```
 * private val doubleBack = DoubleBackToExitHandler(
 *     onFirstPress = { Toast.makeText(this, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show() },
 *     onConfirmedExit = { finish() }
 * )
 *
 * override fun dispatchKeyEvent(event: KeyEvent): Boolean {
 *     if (event.keyCode == KeyEvent.KEYCODE_BACK) {
 *         if (event.action == KeyEvent.ACTION_UP) doubleBack.onBackPressed()
 *         return true // swallow BACK entirely; the engine never sees it directly
 *     }
 *     return super.dispatchKeyEvent(event)
 * }
 * ```
 */
class DoubleBackToExitHandler(
    private val windowMs: Long = 2000L,
    private val onFirstPress: () -> Unit,
    private val onConfirmedExit: () -> Unit,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastPressAt: Long? = null

    /** Call once per BACK press. Always "handles" the press - there is nothing to return. */
    fun onBackPressed() {
        val now = elapsedRealtime()
        val previous = lastPressAt
        if (previous != null && now - previous <= windowMs) {
            lastPressAt = null
            onConfirmedExit()
        } else {
            lastPressAt = now
            onFirstPress()
        }
    }
}
