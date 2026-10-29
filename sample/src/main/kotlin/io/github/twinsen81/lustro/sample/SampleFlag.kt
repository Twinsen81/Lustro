package io.github.twinsen81.lustro.sample

import android.content.res.Configuration

/**
 * A feature flag as the app reads it. In the debug build the Feature Flags tab
 * owns the values, so a toggle in the browser console changes the app at once.
 * The release build has no flags and uses the defaults.
 */
internal data class SampleFlag(val id: String, val description: String, val enabled: Boolean)

internal object SampleFlags {
    /** Switches the sample app to its dark theme. Without the flag, the system setting decides. */
    const val DARK_THEME: String = "dark-mode-v2"

    /** Shows the intro text on the home screen. */
    const val HOME_INTRO: String = "new-onboarding"

    private val defaults = mapOf(HOME_INTRO to true)

    /** [default] applies when the flag is missing, in place of the built-in default. */
    fun isOn(flags: List<SampleFlag>?, id: String, default: Boolean? = null): Boolean =
        flags?.firstOrNull { it.id == id }?.enabled ?: default ?: defaults.getValue(id)

    fun isSystemDark(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** What a flag changes in the app, or null when the app does not read it. */
    fun effect(id: String): String? =
        when (id) {
            DARK_THEME -> "Switches this app to the dark theme"
            HOME_INTRO -> "Shows the intro on the home screen"
            else -> null
        }
}
