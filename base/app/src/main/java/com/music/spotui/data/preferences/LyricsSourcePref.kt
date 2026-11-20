package com.music.spotui.data.preferences

import android.content.Context

/**
 * Per-source control for lyrics retrieval, in the spirit of "three-party online sources":
 * every built-in source can be switched on/off, reordered, and there is an "auto pick the best
 * source" switch. When auto is on the built-in best order is used (the user order is ignored);
 * when off, the user's order decides which source is tried first.
 *
 * Stored in the same "settings_prefs" as the other lyrics switches. The runtime ([LyricsApi])
 * reads [enabled] / [ordered] so the toggles and the order really take effect.
 */
object LyricsSourcePref {
    /** Built-in sources, in the resolver's default (best) order. */
    val ALL: List<String> = listOf(
        "amll", "lunabeat", "netease", "qqmusic", "kuwo", "kugou", "genius"
    )

    /** Human-readable names (brands, not translated), matching LyricsApi.Source.name. */
    val NAMES: Map<String, String> = mapOf(
        "amll" to "AMLL TTML DB",
        "lunabeat" to "LunaBeat",
        "netease" to "NetEase",
        "qqmusic" to "QQ Music",
        "kuwo" to "KuWo",
        "kugou" to "Kugou",
        "genius" to "Genius",
    )

    private const val PREF = "settings_prefs"
    private const val KEY_ORDER = "lyrics_src_order"
    private const val KEY_AUTO = "lyrics_src_auto"
    private const val KEY_PREFIX = "lyrics_src_on_"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Auto pick best source: on by default. */
    fun isAuto(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO, true)
    fun setAuto(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_AUTO, value).apply()

    /** A source is on by default (all built-ins on, same as the old behaviour). */
    fun isEnabled(c: Context, id: String): Boolean {
        // Installs that turned off the removed standalone Genius row keep that choice.
        if (id == "genius" && !prefs(c).contains(KEY_PREFIX + id) && prefs(c).contains("genius"))
            return prefs(c).getBoolean("genius", true)
        return prefs(c).getBoolean(KEY_PREFIX + id, true)
    }

    fun setEnabled(c: Context, id: String, value: Boolean) {
        // Never let the user turn every source off.
        if (!value && enabledIds(c).size <= 1 && isEnabled(c, id)) return
        prefs(c).edit().apply {
            putBoolean(KEY_PREFIX + id, value)
            // GeniusPref used to live under "genius"; keep old readers in step.
            if (id == "genius") putBoolean("genius", value)
            apply()
        }
    }

    /** The user's saved order, normalised (dedup + every built-in present). */
    fun savedOrder(c: Context): List<String> {
        val raw = prefs(c).getString(KEY_ORDER, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return normalize(raw)
    }

    fun setOrder(c: Context, order: List<String>) =
        prefs(c).edit().putString(KEY_ORDER, normalize(order).joinToString(",")).apply()

    private fun normalize(raw: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (id in raw) if (id in ALL) seen.add(id)
        for (id in ALL) seen.add(id)
        return seen.toList()
    }

    /** Sources actually used by the resolver, in order: on when auto, else the user's order. */
    fun orderedSources(c: Context): List<String> {
        val order = if (isAuto(c)) ALL else savedOrder(c)
        val enabled = order.filter { isEnabled(c, it) }
        return enabled.ifEmpty { listOf(ALL.first()) }
    }

    /** Enabled source ids (in user order), for the settings UI. */
    fun enabledIds(c: Context): List<String> = ALL.filter { isEnabled(c, it) }

    /** Move [id] by [delta] positions (-1 up, +1 down) in the user's order. */
    fun move(c: Context, id: String, delta: Int) {
        val order = savedOrder(c).toMutableList()
        val i = order.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j].also { order[j] = id }
        setOrder(c, order)
    }
}
