package com.music.spotui.data.preferences

import android.content.Context
import androidx.compose.runtime.mutableStateOf

private const val LYRICS_PREF = "lyrics_display"
private const val KEY_FONT_SCALE = "font_scale"
private const val KEY_COLOR = "color_argb"
private const val KEY_OFFSET_MS = "offset_ms"
private const val KEY_TITLE = "title_shown"

private fun lyricsPrefs(c: Context) = c.getSharedPreferences(LYRICS_PREF, Context.MODE_PRIVATE)

/** User-adjusted lyrics type scale, 0.7–1.8. */
object LyricsFontScalePref {
    var value by mutableStateOf(1f)
        private set

    fun load(c: Context) { value = lyricsPrefs(c).getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.7f, 1.8f) }
    fun set(c: Context, value: Float) {
        this.value = value.coerceIn(0.7f, 1.8f)
        lyricsPrefs(c).edit().putFloat(KEY_FONT_SCALE, this.value).apply()
    }
}

/** Active lyrics colour; null keeps the player's own white. */
object LyricsColorPref {
    var value by mutableStateOf<Int?>(null)
        private set

    fun load(c: Context) {
        val p = lyricsPrefs(c)
        value = if (p.contains(KEY_COLOR)) p.getInt(KEY_COLOR, 0) else null
    }
    fun set(c: Context, argb: Int?) {
        value = argb
        lyricsPrefs(c).edit().apply {
            if (argb == null) remove(KEY_COLOR) else putInt(KEY_COLOR, argb)
            apply()
        }
    }
    fun clear(c: Context) = set(c, null)
}

/** A fixed correction added to the playback position when the source's timings are early/late. */
object LyricsOffsetPref {
    var ms by mutableStateOf(0L)
        private set

    fun load(c: Context) { ms = lyricsPrefs(c).getLong(KEY_OFFSET_MS, 0L).coerceIn(-60_000L, 60_000L) }
    fun set(c: Context, value: Long) {
        ms = value.coerceIn(-60_000L, 60_000L)
        lyricsPrefs(c).edit().putLong(KEY_OFFSET_MS, ms).apply()
    }
}

/** Show the song title above the Immersive lyrics. */
object LyricsTitlePref {
    var shown by mutableStateOf(false)
        private set

    fun load(c: Context) { shown = lyricsPrefs(c).getBoolean(KEY_TITLE, false) }
    fun set(c: Context, value: Boolean) {
        shown = value
        lyricsPrefs(c).edit().putBoolean(KEY_TITLE, value).apply()
    }
}

fun loadLyricsDisplayPrefs(c: Context) {
    LyricsFontScalePref.load(c)
    LyricsColorPref.load(c)
    LyricsOffsetPref.load(c)
    LyricsTitlePref.load(c)
}
