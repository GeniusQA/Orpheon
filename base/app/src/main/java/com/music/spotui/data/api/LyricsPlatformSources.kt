package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * QQ Music and KuWo lyric sources, ported from HyperLyrics-Enhanced's platform clients.
 * Only the network/parse layer is used: the app's own Track, cache, health and translation pipeline
 * stay in [LyricsApi]. Both sources keep their own translation/romanization when they provide one;
 * [LyricsViewModel] falls back to Google Translate only when they do not.
 */
internal object LyricsPlatformSources {
    private const val QQ_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val KUWO_SEARCH = "https://www.kuwo.cn/openapi/v1/www/search/searchMusicBykeyWord"
    private const val KUWO_LYRIC = "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric"
    private const val KUWO_LRCX = "https://newlyric.kuwo.cn/newlyric.lrc"
    private const val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL"

    suspend fun fromQQMusic(title: String, artist: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        runCatching {
            val keyword = if (artist.isBlank()) title else "$title $artist"
            val searchParam = JSONObject()
                .put("search_id", Random.nextLong(10_000_000_000_000_000L, 90_000_000_000_000_000L).toString())
                .put("remoteplace", "search.android.keyboard")
                .put("query", keyword)
                .put("search_type", 0)
                .put("num_per_page", 20)
                .put("page_num", 1)
                .put("highlight", 0)
                .put("nqc_flag", 0)
                .put("page_id", 1)
                .put("grp", 1)
            val searchBody = qqBody("DoSearchForQQMusicLite", "music.search.SearchCgiService", searchParam)
            val search = JSONObject(post(QQ_URL, searchBody.toString(), qqHeaders()))
            val module = search.optJSONObject("req_0") ?: return@runCatching null
            if (module.optInt("code") != 0) return@runCatching null
            val songs = module.optJSONObject("data")?.optJSONObject("body")?.optJSONArray("item_song") ?: return@runCatching null
            val best = pick(songs, title, artist, durationMs) ?: return@runCatching null
            val id = best.optLong("id")
            if (id <= 0L) return@runCatching null

            val name = best.optString("title")
            val singers = best.optJSONArray("singer")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
            }.orEmpty()
            val singer = singers.joinToString(" / ")
            val album = best.optJSONObject("album")?.optString("name").orEmpty()
            val lyricParam = JSONObject()
                .put("songID", id)
                .put("songName", b64(name))
                .put("albumName", b64(album))
                .put("singerName", b64(singer))
                .put("crypt", 1).put("qrc", 1).put("trans", 1).put("roma", 1)
                .put("cv", 2111).put("ct", 19)
                .put("lrc_t", 0).put("qrc_t", 0).put("roma_t", 0).put("trans_t", 0)
                .put("type", 0).put("interval", durationMs / 1000)
            val lyricBody = qqBody("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", lyricParam)
            val lyricResp = JSONObject(post(QQ_URL, lyricBody.toString(), qqHeaders()))
            val lyricModule = lyricResp.optJSONObject("req_0") ?: return@runCatching null
            if (lyricModule.optInt("code") != 0) return@runCatching null
            val data = lyricModule.optJSONObject("data") ?: return@runCatching null
            val original = parseQrc(decryptQrc(data.optString("lyric")))
            if (original.isEmpty()) return@runCatching null
            val translated = parseQrc(decryptQrc(data.optString("trans")))
            val romanized = parseQrc(decryptQrc(data.optString("roma")))
            val lines = LyricsExtras.attach(original, translated, romanized)
            Lyrics(
                lines = lines,
                synced = true,
                language = null,
                provider = "QQ Music",
                translationLanguage = if (translated.isEmpty()) null else "zh",
            )
        }.getOrNull()
    }

    suspend fun fromKuWo(title: String, artist: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        runCatching {
            val keyword = if (artist.isBlank()) title else "$title $artist"
            val url = "$KUWO_SEARCH?key=${URLEncoder.encode(keyword, "UTF-8")}&pn=1&rn=30&httpsStatus=1"
            val json = JSONObject(get(url, kuwoHeaders()))
            if (json.optInt("code") != 200) return@runCatching null
            val list = json.optJSONObject("data")?.optJSONArray("list") ?: return@runCatching null
            var best: JSONObject? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val score = score(item.optString("name"), item.optString("artist"), item.optLong("duration") * 1000,
                    title, artist, durationMs)
                if (score > bestScore) { best = item; bestScore = score }
            }
            val rid = best?.optLong("rid") ?: return@runCatching null
            if (rid <= 0L) return@runCatching null

            val lrcx = runCatching { decodeLrcx(get("$KUWO_LRCX?${buildKuwoQuery(rid)}", kuwoHeaders("okhttp/3.10.0"))) }.getOrNull()
            val text = if (!lrcx.isNullOrBlank() && parseKuwo(lrcx).first.isNotEmpty()) lrcx
            else {
                val fallback = JSONObject(get("$KUWO_LYRIC?musicId=$rid&httpsStatus=1", kuwoHeaders()))
                val lines = fallback.optJSONObject("data")?.optJSONArray("lrclist") ?: return@runCatching null
                buildString {
                    for (i in 0 until lines.length()) {
                        val item = lines.optJSONObject(i) ?: continue
                        val t = (item.optString("time").toDoubleOrNull() ?: continue) * 1000L
                        append('[').append(time(t)).append(']').append(item.optString("lineLyric")).append('\n')
                    }
                }
            }
            val parsed = parseKuwo(text)
            val lines = LyricsExtras.attach(parsed.first, parsed.second, parsed.third)
            Lyrics(
                lines = lines,
                synced = lines.any { it.timeMs > 0 },
                language = null,
                provider = "KuWo",
                translationLanguage = if (parsed.second.isEmpty()) null else "zh",
            )
        }.getOrNull()
    }

    private fun qqBody(method: String, module: String, param: JSONObject): JSONObject {
        val comm = JSONObject()
            .put("ct", "11").put("cv", "1003006").put("v", "1003006").put("os_ver", "15")
            .put("phonetype", "24122RKC7C").put("tmeAppID", "qqmusiclight").put("nettype", "NETWORK_WIFI")
        return JSONObject().put("comm", comm).put(
            "req_0", JSONObject().put("method", method).put("module", module).put("param", param)
        )
    }

    private fun qqHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "okhttp/3.14.9",
        "Referer" to "https://y.qq.com/",
        "Content-Type" to "application/json; charset=utf-8",
    )

    private fun kuwoHeaders(agent: String = "Mozilla/5.0"): Map<String, String> = mapOf(
        "User-Agent" to agent,
        "Referer" to "https://www.kuwo.cn/",
        "Accept-Encoding" to "identity",
    )

    private fun post(url: String, body: String, headers: Map<String, String>): String = request(url, "POST", headers, body.toByteArray())
    private fun get(url: String, headers: Map<String, String>): String = request(url, "GET", headers, null)

    private fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray?): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }
        return try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun b64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun pick(songs: org.json.JSONArray, title: String, artist: String, durationMs: Long): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val singers = item.optJSONArray("singer")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
            }.orEmpty().joinToString(" ")
            val score = score(item.optString("title"), singers, item.optInt("interval") * 1000L, title, artist, durationMs)
            if (score > bestScore) { best = item; bestScore = score }
        }
        return best
    }

    private fun score(name: String, by: String, ms: Long, title: String, artist: String, durationMs: Long): Int {
        val n = norm(name)
        val t = norm(title)
        val a = norm(by)
        val ar = norm(artist)
        var s = 0
        if (n == t) s += 6 else if (n.contains(t) || t.contains(n)) s += 3
        if (a == ar || (a.isNotBlank() && ar.contains(a))) s += 4 else if (ar.isNotBlank() && a.contains(ar)) s += 2
        if (durationMs > 0 && ms > 0 && kotlin.math.abs(ms - durationMs) < 6_000) s += 2
        return s
    }

    private fun norm(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun decryptQrc(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val hex = raw.filter { it.isHexDigit() }
            val bytes = ByteArray(hex.length / 2) { i -> ((hex[i * 2].hex() shl 4) + hex[i * 2 + 1].hex()).toByte() }
            val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY.toByteArray(StandardCharsets.UTF_8), "DESede"))
            inflate(cipher.doFinal(bytes))
        }.getOrDefault("")
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private fun Char.hex() = Character.digit(this, 16)

    private fun inflate(data: ByteArray): String = runCatching {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        out.toString("UTF-8")
    }.getOrDefault("")

    private val qrcLine = Regex("""^\[(\d+),(\d+)\](.*)$""")
    private val qrcWord = Regex("""\((\d+),(\d+)\)([^()]+)""")

    private fun parseQrc(text: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            val m = qrcLine.matchEntire(raw.trim()) ?: return@forEach
            val start = m.groupValues[1].toLongOrNull() ?: return@forEach
            val body = m.groupValues[3]
            val words = qrcWord.findAll(body).map { w ->
                val off = w.groupValues[1].toLongOrNull() ?: 0L
                val dur = w.groupValues[2].toLongOrNull() ?: 0L
                LyricWord(start + off, start + off + dur, w.groupValues[3])
            }.toList()
            val plain = if (words.isEmpty()) body else words.joinToString("") { it.text }
            if (plain.isBlank()) return@forEach
            lines += LyricLine(start, plain.trim(), words.takeIf { it.isNotEmpty() })
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun buildKuwoQuery(rid: Long): String {
        val request = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$rid&lrcx=1"
        return Base64.getEncoder().encodeToString(xor(request.toByteArray(StandardCharsets.US_ASCII)))
    }

    private fun decodeLrcx(response: String): String? {
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        val prefix = "tp=content".toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size < prefix.size || !prefix.indices.all { bytes[it] == prefix[it] }) return null
        val sep = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val at = indexOf(bytes, sep).takeIf { it >= 0 }?.plus(sep.size) ?: return null
        val inflated = InflaterInputStream(ByteArrayInputStream(bytes, at, bytes.size - at)).use { it.readBytes() }
        val encrypted = Base64.getMimeDecoder().decode(inflated.toString(StandardCharsets.US_ASCII).trim())
        return String(xor(encrypted), Charset.forName("GB18030")).takeIf { it.isNotBlank() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun xor(value: ByteArray): ByteArray {
        val key = "yeelion".toByteArray(StandardCharsets.US_ASCII)
        return ByteArray(value.size) { (value[it].toInt() xor key[it % key.size].toInt()).toByte() }
    }

    private fun time(ms: Long): String = String.format(
        java.util.Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1_000 % 60, ms % 1_000
    )

    private val lrcLine = Regex("""^\[(\d{1,2}):(\d{2}(?:\.\d+)?)\](.*)$""")

    /** Original / translated / romanized KuWo lines; duplicate timestamps are translation tracks. */
    private fun parseKuwo(text: String): Triple<List<LyricLine>, List<LyricLine>, List<LyricLine>> {
        val groups = linkedMapOf<Long, MutableList<String>>()
        text.lineSequence().forEach { raw ->
            val m = lrcLine.matchEntire(raw.trim()) ?: return@forEach
            val min = m.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = m.groupValues[2].toDoubleOrNull() ?: return@forEach
            val body = m.groupValues[3]
                .replace(Regex("""\[kuwo:[^\]]*\]"""), "")
                .replace(Regex("""<[^>]*>"""), "")
                .trim()
            if (body.isEmpty() || body == "//") return@forEach
            val t = min * 60_000L + (sec * 1000L).toLong()
            groups.getOrPut(t) { mutableListOf() }.add(body)
        }
        val original = mutableListOf<LyricLine>()
        val translated = mutableListOf<LyricLine>()
        val romanized = mutableListOf<LyricLine>()
        groups.forEach { (t, values) ->
            original += LyricLine(t, values[0])
            if (values.size > 1) translated += LyricLine(t, values[1])
            if (values.size > 2) romanized += LyricLine(t, values[2])
        }
        return Triple(original.sortedBy { it.timeMs }, translated.sortedBy { it.timeMs }, romanized.sortedBy { it.timeMs })
    }
}
