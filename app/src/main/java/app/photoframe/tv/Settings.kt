package app.photoframe.tv

import android.content.SharedPreferences

class Settings(private val prefs: SharedPreferences) {

    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 10)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var transition: String
        get() = prefs.getString(KEY_TRANSITION, "fade") ?: "fade"
        set(value) = prefs.edit().putString(KEY_TRANSITION, value).apply()

    var fitMode: String
        get() = prefs.getString(KEY_FIT, "blur") ?: "blur"
        set(value) = prefs.edit().putString(KEY_FIT, value).apply()

    /** A slight, very slow zoom-in on every photo, whatever the transition. */
    var slowZoom: Boolean
        get() = prefs.getBoolean(KEY_SLOW_ZOOM, true)
        set(value) = prefs.edit().putBoolean(KEY_SLOW_ZOOM, value).apply()

    var shuffle: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var refreshMinutes: Int
        get() = prefs.getInt(KEY_REFRESH, 30)
        set(value) = prefs.edit().putInt(KEY_REFRESH, value).apply()

    var showClock: Boolean
        get() = prefs.getBoolean(KEY_CLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOCK, value).apply()

    var showDate: Boolean
        get() = prefs.getBoolean(KEY_DATE, false)
        set(value) = prefs.edit().putBoolean(KEY_DATE, value).apply()

    companion object {
        const val KEY_INTERVAL = "interval"
        const val KEY_TRANSITION = "transition"
        const val KEY_FIT = "fit"
        const val KEY_SLOW_ZOOM = "slowZoom"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REFRESH = "refreshMinutes"
        const val KEY_CLOCK = "showClock"
        const val KEY_DATE = "showDate"

        val INTERVALS = listOf(3, 5, 8, 10, 15, 20, 30, 60, 120, 300)
        val REFRESH_MINUTES = listOf(15, 30, 60, 180, 720)

        val TRANSITIONS = linkedMapOf(
            "fade" to "Crossfade",
            "kenburns" to "Ken Burns (slow zoom and pan)",
            "slide" to "Slide",
            "zoom" to "Zoom",
            "black" to "Fade through black",
            "random" to "Random mix",
        )

        val FIT_MODES = linkedMapOf(
            "blur" to "Whole photo, blurred edges",
            "smart" to "Smart: fill landscape, fit portrait",
            "fill" to "Fill the screen (crop)",
        )

        fun intervalLabel(seconds: Int) = when {
            seconds < 60 -> "$seconds seconds"
            seconds == 60 -> "1 minute"
            else -> "${seconds / 60} minutes"
        }

        fun refreshLabel(minutes: Int) = when {
            minutes < 60 -> "Every $minutes minutes"
            minutes == 60 -> "Every hour"
            else -> "Every ${minutes / 60} hours"
        }
    }
}
