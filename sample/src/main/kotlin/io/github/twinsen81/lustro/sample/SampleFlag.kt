package io.github.twinsen81.lustro.sample

/**
 * A feature flag as the app reads it. In the debug build the Feature Flags tab
 * owns the values, so a toggle in the browser console changes the app at once.
 * The release build has no flags and uses the defaults.
 */
internal data class SampleFlag(val id: String, val description: String, val enabled: Boolean)

internal object SampleFlags {
    /** Switches the sample app to its dark theme. */
    const val DARK_THEME: String = "dark-mode-v2"

    /** Shows the intro text on the home screen. */
    const val HOME_INTRO: String = "new-onboarding"

    private val defaults = mapOf(DARK_THEME to false, HOME_INTRO to true)

    fun isOn(flags: List<SampleFlag>?, id: String): Boolean = flags?.firstOrNull { it.id == id }?.enabled ?: defaults.getValue(id)

    /** What a flag changes in the app, or null when the app does not read it. */
    fun effect(id: String): String? =
        when (id) {
            DARK_THEME -> "Switches this app to the dark theme"
            HOME_INTRO -> "Shows the intro on the home screen"
            else -> null
        }
}
