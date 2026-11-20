package com.music.spotui.ui.components

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.music.spotui.di.Graph

/**
 * Short user-facing notices for extension and download results.
 *
 * Extension hooks are called very often (audio warm-up, lyrics, status probes), so identical
 * messages inside [DEDUPE_MS] are collapsed. Internals still go to the extension log and Logbook.
 */
object UserNotices {
    private const val DEDUPE_MS = 2_500L
    private val main = Handler(Looper.getMainLooper())
    private var lastText = ""
    private var lastAt = 0L

    fun post(text: String, long: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < DEDUPE_MS) return
        lastText = text
        lastAt = now
        main.post {
            val app = runCatching { Graph.app }.getOrNull() ?: return@post
            Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}
