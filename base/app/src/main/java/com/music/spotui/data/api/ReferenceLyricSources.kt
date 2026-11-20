package com.music.spotui.data.api

import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.LyricWord
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.random.Random

/**
 * The six online lyric platforms used by HyperLyrics-Enhanced.
 *
 * This module owns only the network clients and parsers. Caching, health, manual source choice,
 * translation display and the Google fallback stay in [LyricsApi]/[LyricsViewModel].
 */
internal object ReferenceLyricSources {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(sourceId: String, track: LyricsApi.Track): Lyrics? = withContext(Dispatchers.IO) {
        when (sourceId) {
            "amll" -> amll(track)
            "lunabeat" -> lunabeat(track)
            "netease" -> netease(track)
            "qqmusic" -> qqMusic(track)
            "kuwo" -> kuwo(track)
            "kugou" -> kugou(track)
            else -> null
        }
    }

    // ─────────────────────────── AMLL TTML DB ───────────────────────────

    private const val AMLL_PRIMARY = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/"
    private const val AMLL_MIRROR = "https://amll-ttml-db.stevexmh.net/"

    private fun amll(track: LyricsApi.Track): Lyrics? {
        val id = track.trackId.removePrefix("spotify:track:").trim()
            .takeIf { it.isNotEmpty() && it.length <= 64 && it.all(Char::isLetterOrDigit) } ?: return null
        val headers = mapOf(
            "Accept" to "application/ttml+xml, application/xml, text/xml, text/plain",
            "User-Agent" to "Orpheon/${app.myspoti.engine.BuildConfig.VERSION_NAME}",
        )
        val xml = text("$AMLL_PRIMARY spotify-lyrics/$id.ttml".replace(" ", ""), headers)
            ?: text("${AMLL_MIRROR}spotify/$id", headers)
            ?: return null
        return runCatching { LyricsApi.parseTtml(xml).takeIf { it != null && !it.isEmpty }?.copy(provider = "AMLL TTML DB") }
            .getOrNull()
    }

    // ─────────────────────────── LunaBeat TTML Hub ───────────────────────────

    private const val LUNA_BASE = "https://2755337087.github.io/ttml-hub/"
    private const val LUNA_MANIFEST = "${LUNA_BASE}api/v1/manifest.json"
    private const val LUNA_CHECK_MS = 6L * 60L * 60L * 1000L
    private var lunaIndex: JSONObject? = null
    private var lunaCheckedAt = 0L
    private val lunaTtmlCache = HashMap<String, String>()

    private fun lunabeat(track: LyricsApi.Track): Lyrics? {
        val appleId = itunesId(track) ?: return null
        val index = lunaIndex(force = false) ?: return null
        val songs = index.optJSONArray("songs") ?: return null
        var entry: JSONObject? = null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val ids = song.optJSONObject("sourceIds")?.optJSONArray("appleMusicId") ?: continue
            for (j in 0 until ids.length()) {
                if (ids.optString(j).trim() == appleId) { entry = song; break }
            }
            if (entry != null) break
        }
        val path = entry?.optString("path").orEmpty().trimStart('/')
        val expectedSha = entry?.optString("sha256").orEmpty()
        if (!path.startsWith("lyrics/") || path.contains("..") || expectedSha.length != 64) return null
        val xml = lunaTtmlCache[path] ?: runCatching {
            val bytes = bytes(LUNA_BASE + path, mapOf(
                "Accept" to "application/ttml+xml, application/xml, text/xml",
                "User-Agent" to "Orpheon/${app.myspoti.engine.BuildConfig.VERSION_NAME}",
            )) ?: return@runCatching null
            val text = String(bytes, StandardCharsets.UTF_8)
            if (!sha256Hex(bytes).equals(expectedSha, true)) return@runCatching null
            lunaTtmlCache[path] = text
            text
        }.getOrNull() ?: return null
        return runCatching { LyricsApi.parseTtml(xml).takeIf { it != null && !it.isEmpty }?.copy(provider = "LunaBeat") }
            .getOrNull()
    }

    private fun lunaIndex(force: Boolean): JSONObject? {
        val now = System.currentTimeMillis()
        val cached = lunaIndex
        if (!force && cached != null && now - lunaCheckedAt < LUNA_CHECK_MS) return cached
        val manifestText = text(LUNA_MANIFEST, mapOf(
            "Accept" to "application/json",
            "User-Agent" to "Orpheon/${app.myspoti.engine.BuildConfig.VERSION_NAME}",
        )) ?: return cached
        val manifest = runCatching { JSONObject(manifestText) }.getOrNull() ?: return cached
        lunaCheckedAt = now
        if (manifest.optInt("schemaVersion") != 2) return cached
        val filename = manifest.optString("index").trimStart('/')
        val expectedSha = manifest.optString("indexSha256")
        if (filename.isEmpty() || expectedSha.isEmpty()) return cached
        val indexBytes = bytes(LUNA_BASE + "api/v1/$filename", mapOf(
            "Accept" to "application/json",
            "User-Agent" to "Orpheon/${app.myspoti.engine.BuildConfig.VERSION_NAME}",
        )) ?: return cached
        if (!sha256Hex(indexBytes).equals(expectedSha, true)) return cached
        val index = runCatching { JSONObject(String(indexBytes, StandardCharsets.UTF_8)) }.getOrNull() ?: return cached
        if (index.optInt("schemaVersion") != 2 || index.optString("revision") != manifest.optString("revision")) return cached
        val songs = index.optJSONArray("songs") ?: return cached
        if (songs.length() < 1 || songs.length() > 100_000) return cached
        lunaIndex = index
        return index
    }

    private fun itunesId(track: LyricsApi.Track): String? {
        val term = URLEncoder.encode("${track.title} ${track.artist}", "UTF-8")
        val json = text("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=10&country=us")
            ?: return null
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        var fallback: String? = null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optLong("trackId").takeIf { it > 0 }?.toString() ?: continue
            val title = item.optString("trackName")
            val artist = item.optString("artistName")
            val duration = item.optLong("trackTimeMillis")
            if (LyricsApi.sameSong(title, artist, duration, track.title, track.artist, track.durationMs)) return id
            if (fallback == null && (title.contains(track.title, true) || track.title.contains(title, true))) fallback = id
        }
        return fallback
    }

    // ─────────────────────────── NetEase eapi ───────────────────────────

    private const val NE_BASE = "https://interface.music.163.com"
    private const val NE_KEY = "e82ckenh8dichen8"
    private const val NE_APPVER = "3.1.3.203419"
    private const val NE_OSVER = "Microsoft-Windows-10--build-19045-64bit"
    private val neDeviceId = UUID.randomUUID().toString().replace("-", "")
    private var neClientSign = neClientSign()
    private val neCookies = HashMap<String, String>()
    private var neLoggedInAt = 0L
    private val neLock = Any()

    private fun netease(track: LyricsApi.Track): Lyrics? {
        synchronized(neLock) { ensureNeLogin() }
        val keyword = "${track.title} ${track.artist}".trim()
        val search = neRequest("/eapi/search/song/list/page", JSONObject()
            .put("limit", "20")
            .put("offset", "0")
            .put("keyword", keyword)
            .put("scene", "NORMAL")
            .put("needCorrect", "true")) ?: return null
        val resources = runCatching { JSONObject(search).optJSONObject("data")?.optJSONArray("resources") }.getOrNull() ?: return null
        var songId: String? = null
        for (i in 0 until resources.length()) {
            val simple = resources.optJSONObject(i)?.optJSONObject("baseInfo")?.optJSONObject("simpleSongData") ?: continue
            val id = simple.optLong("id").takeIf { it > 0 }?.toString() ?: continue
            val title = simple.optString("name")
            val artist = simple.optJSONArray("ar")?.optJSONObject(0)?.optString("name").orEmpty()
            val duration = simple.optLong("dt")
            if (LyricsApi.sameSong(title, artist, duration, track.title, track.artist, track.durationMs)) { songId = id; break }
            if (songId == null) songId = id
        }
        val id = songId ?: return null
        val lyricJson = neRequest("/eapi/song/lyric/v1", JSONObject()
            .put("id", id.toLongOrNull() ?: 0L)
            .put("lv", "-1").put("tv", "-1").put("rv", "-1").put("yv", "-1")) ?: return null
        val root = runCatching { JSONObject(lyricJson) }.getOrNull() ?: return null
        val yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tlyric = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val romalrc = root.optJSONObject("romalrc")?.optString("lyric").orEmpty()
        val parsed = parseNetEase(yrc, lrc, tlyric, romalrc, track.durationMs) ?: return null
        return toLyrics("NetEase", parsed, "zh")
    }

    private fun ensureNeLogin() {
        if (neCookies.isNotEmpty() && System.currentTimeMillis() - neLoggedInAt < 10 * 86_400_000L) return
        val params = JSONObject().put("username", neAnonUsername()).put("e_r", true)
        val body = neBody(params, "/api/register/anonimous")
        val url = "$NE_BASE/eapi/register/anonimous"
        val request = Request.Builder().url(url)
            .header("User-Agent", neUa())
            .header("Referer", "https://music.163.com/")
            .header("Accept", "*/*")
            .header("Cookie", nePreCookies())
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return
        response.use { resp ->
            resp.headers("Set-Cookie").forEach { parseCookie(it) }
            val encrypted = resp.body?.bytes() ?: return
            val plain = neDecrypt(encrypted) ?: return
            val code = runCatching { JSONObject(plain).optString("code") }.getOrDefault("")
            if (code == "200") neLoggedInAt = System.currentTimeMillis()
        }
    }

    private fun neRequest(path: String, params: JSONObject, retry: Boolean = true): String? {
        val body = neBody(params, path.replace("/eapi/", "/api/"))
        val request = Request.Builder().url(NE_BASE + path)
            .header("User-Agent", neUa())
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neCookieHeader())
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return null
        response.use { resp ->
            val encrypted = resp.body?.bytes() ?: return null
            val plain = neDecrypt(encrypted) ?: return null
            val code = runCatching { JSONObject(plain).optString("code") }.getOrDefault("")
            if ((code == "301" || code == "401") && retry) {
                synchronized(neLock) { neCookies.clear(); ensureNeLogin() }
                return neRequest(path, params, retry = false)
            }
            return plain
        }
    }

    private fun neBody(params: JSONObject, encryptPath: String): String {
        val header = JSONObject()
            .put("clientSign", neClientSign)
            .put("osver", NE_OSVER)
            .put("deviceId", neDeviceId)
            .put("os", "pc")
            .put("appver", NE_APPVER)
            .put("requestId", System.currentTimeMillis().toString())
        params.put("header", header.toString())
        if (!params.has("e_r")) params.put("e_r", true)
        val value = params.toString()
        val message = String.format(Locale.ROOT, "nobody%suse%smd5forencrypt", encryptPath, value)
        val digest = md5Hex(message)
        val plain = "$encryptPath-36cd479b6b5-$value-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(NE_KEY.toByteArray(StandardCharsets.UTF_8), "AES"))
        return "params=" + cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)).toHex().uppercase(Locale.ROOT)
    }

    private fun neDecrypt(data: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(NE_KEY.toByteArray(StandardCharsets.UTF_8), "AES"))
        String(cipher.doFinal(data), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun neUa(): String =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/$NE_APPVER"

    private fun nePreCookies(): String = mapOf(
        "os" to "pc", "deviceId" to neDeviceId, "osver" to NE_OSVER,
        "clientSign" to neClientSign, "channel" to "netease", "mode" to "dev", "appver" to NE_APPVER,
    ).entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun neCookieHeader(): String = neCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun parseCookie(raw: String) {
        val pair = raw.substringBefore(';').split('=', limit = 2)
        if (pair.size == 2 && pair[0].isNotBlank()) neCookies[pair[0]] = pair[1]
    }

    private fun neClientSign(): String {
        val mac = (0 until 6).joinToString(":") { "%02X".format(Locale.ROOT, Random.nextInt(256)) }
        val tail = (0 until 8).joinToString("") { ('A' + Random.nextInt(26)).toString() }
        val hex = (0 until 64).joinToString("") { "%02x".format(Locale.ROOT, Random.nextInt(16)) }
        return "$mac@@@$tail@@@@@@$hex"
    }

    private fun neAnonUsername(): String {
        val key = "3go8&" + '$' + "8*3*3h0k(2)2"
        val xored = neDeviceId.mapIndexed { i, c -> (c.code xor key[i % key.length].code).toChar() }.joinToString("")
        val digest = md5Hex(xored)
        val encoded = Base64.getEncoder().encodeToString(digest.toByteArray(StandardCharsets.UTF_8))
        return "$neDeviceId $encoded"
    }

    // ─────────────────────────── QQ Music ───────────────────────────

    private const val QQ_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val QQ_3DES_KEY = "!@#)(*$%123ZXC!@!@#)(NHL"

    private fun qqMusic(track: LyricsApi.Track): Lyrics? {
        val candidates = qqSearch(track) ?: qqCatalog(track)
        val song = candidates?.firstOrNull() ?: return null
        val id = song.optLong("id").takeIf { it > 0 } ?: return null
        val title = song.optString("title").ifBlank { track.title }
        val artist = song.optString("artist").ifBlank { track.artist }
        val album = song.optString("album")
        val body = qqBody("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", JSONObject()
            .put("songID", id)
            .put("songName", b64(title)).put("albumName", b64(album)).put("singerName", b64(artist))
            .put("crypt", 1).put("qrc", 1).put("trans", 1).put("roma", 1)
            .put("cv", 2111).put("ct", 19)
            .put("lrc_t", 0).put("qrc_t", 0).put("roma_t", 0).put("trans_t", 0)
            .put("type", 0).put("interval", track.durationMs / 1000))
        val data = runCatching {
            val root = JSONObject(postJson(QQ_URL, body, qqHeaders()) ?: return null)
            val module = root.getJSONObject("req_0")
            if (module.optInt("code") != 0) return null
            module.optJSONObject("data")
        }.getOrNull() ?: return null
        val originalText = qqDecrypt(data.optString("lyric"))
        val translatedText = qqDecrypt(data.optString("trans"))
        val romaText = qqDecrypt(data.optString("roma"))
        val parsed = parseQrcSet(originalText, translatedText, romaText) ?: return null
        return toLyrics("QQ Music", parsed, "zh")
    }

    private fun qqSearch(track: LyricsApi.Track): JSONArray? {
        val keyword = "${track.title} ${track.artist}".trim()
        val body = qqBody("music.search.SearchCgiService", "DoSearchForQQMusicLite", JSONObject()
            .put("search_id", Random.nextLong(10_000_000_000_000_000L, 90_000_000_000_000_000L).toString())
            .put("remoteplace", "search.android.keyboard").put("query", keyword)
            .put("search_type", 0).put("num_per_page", 20).put("page_num", 1)
            .put("highlight", 0).put("nqc_flag", 0).put("page_id", 1).put("grp", 1))
        val root = runCatching { JSONObject(postJson(QQ_URL, body, qqHeaders()) ?: return null) }.getOrNull() ?: return null
        val module = root.optJSONObject("req_0") ?: return null
        if (module.optInt("code") != 0) return null
        return runCatching {
            val songs = module.getJSONObject("data").getJSONObject("body").getJSONArray("item_song")
            val out = JSONArray()
            for (i in 0 until songs.length()) {
                val item = songs.optJSONObject(i) ?: continue
                val artist = item.optJSONArray("singer")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(" / ")
                }.orEmpty()
                out.put(JSONObject()
                    .put("id", item.optLong("id"))
                    .put("title", item.optString("title"))
                    .put("artist", artist)
                    .put("album", item.optJSONObject("album")?.optString("name").orEmpty())
                    .put("durationMs", item.optInt("interval") * 1000L))
            }
            out
        }.getOrNull()
    }

    private fun qqCatalog(track: LyricsApi.Track): JSONArray? {
        val artists = track.artist.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val titles = listOf(track.title, cleanTitle(track.title)).distinct()
        for (title in titles) for (artist in artists.ifEmpty { listOf("") }) {
            val body = qqBody("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", JSONObject()
                .put("songID", 0L).put("type", 0)
                .put("songName", b64(title)).put("singerName", b64(artist)).put("albumName", "")
                .put("interval", track.durationMs / 1000)
                .put("crypt", 0).put("qrc", 0).put("trans", 0).put("roma", 0))
            val module = runCatching {
                JSONObject(postJson(QQ_URL, body, qqHeaders()) ?: return@runCatching null).optJSONObject("req_0")
            }.getOrNull() ?: continue
            val code = module.optInt("code")
            if (code != 0) continue
            val data = module.optJSONObject("data") ?: continue
            val ids = mutableListOf<Long>()
            data.optLong("songID").takeIf { it > 0 }?.let { ids.add(it) }
            data.optJSONArray("vecSongID")?.let { arr ->
                for (i in 0 until arr.length()) arr.optLong(i).takeIf { it > 0 }?.let { ids.add(it) }
            }
            if (ids.isEmpty()) continue
            val out = JSONArray()
            ids.distinct().forEach { out.put(JSONObject()
                .put("id", it).put("title", title).put("artist", artist)
                .put("album", "").put("durationMs", track.durationMs)) }
            return out
        }
        return null
    }

    private fun qqBody(module: String, method: String, param: JSONObject): JSONObject {
        val comm = JSONObject()
            .put("ct", "11").put("cv", "1003006").put("v", "1003006")
            .put("os_ver", "15").put("phonetype", "24122RKC7C")
            .put("tmeAppID", "qqmusiclight").put("nettype", "NETWORK_WIFI")
        return JSONObject().put("comm", comm).put("req_0", JSONObject()
            .put("module", module).put("method", method).put("param", param))
    }

    private fun qqHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "okhttp/3.14.9",
        "Referer" to "https://y.qq.com/",
    )

    private fun qqDecrypt(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val hex = raw.filter { it.isHexDigit() }
            if (hex.length % 2 != 0) return@runCatching ""
            val bytes = ByteArray(hex.length / 2) { i -> ((hex[i * 2].hex() shl 4) + hex[i * 2 + 1].hex()).toByte() }
            if (bytes.size % 8 != 0) return@runCatching ""
            val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QQ_3DES_KEY.toByteArray(StandardCharsets.UTF_8), "DESede"))
            inflate(cipher.doFinal(bytes))
        }.getOrDefault("")
    }

    // ─────────────────────────── KuWo ───────────────────────────

    private const val KUWO_SEARCH = "https://www.kuwo.cn/openapi/v1/www/search/searchMusicBykeyWord"
    private const val KUWO_LYRIC = "https://www.kuwo.cn/openapi/v1/www/lyric/getlyric"
    private const val KUWO_LRCX = "https://newlyric.kuwo.cn/newlyric.lrc"

    private fun kuwo(track: LyricsApi.Track): Lyrics? {
        val keyword = URLEncoder.encode("${track.title} ${track.artist}".trim(), "UTF-8")
        val json = text("$KUWO_SEARCH?key=$keyword&pn=1&rn=30&httpsStatus=1", kuwoHeaders()) ?: return null
        val list = runCatching {
            val root = JSONObject(json)
            if (root.optInt("code") != 200) return@runCatching null
            root.optJSONObject("data")?.optJSONArray("list")
        }.getOrNull() ?: return null
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val score = score(item.optString("name"), item.optString("artist"), item.optLong("duration") * 1000L,
                track.title, track.artist, track.durationMs)
            if (score > bestScore) { best = item; bestScore = score }
        }
        val rid = best?.optLong("rid")?.takeIf { it > 0 } ?: return null
        val lrcxText = runCatching {
            val raw = bytes("$KUWO_LRCX?${kuwoQuery(rid)}", kuwoHeaders("okhttp/3.10.0")) ?: return@runCatching null
            decodeKuwo(raw)
        }.getOrNull()
        val rawText = if (!lrcxText.isNullOrEmpty() && KUWO_TIME.containsMatchIn(lrcxText)) lrcxText
        else {
            val fallback = text("$KUWO_LYRIC?musicId=$rid&httpsStatus=1", kuwoHeaders()) ?: return null
            val lines = runCatching {
                val root = JSONObject(fallback)
                if (root.has("code") && root.optInt("code") != 200) return@runCatching null
                root.optJSONObject("data")?.optJSONArray("lrclist")
            }.getOrNull() ?: return null
            buildString {
                for (i in 0 until lines.length()) {
                    val item = lines.optJSONObject(i) ?: continue
                    val t = ((item.optString("time").toDoubleOrNull() ?: continue) * 1000L).toLong()
                    append('[').append(kuwoTime(t)).append(']').append(item.optString("lineLyric")).append('\n')
                }
            }
        }
        val parsed = parseKuwo(rawText, track.durationMs) ?: return null
        return toLyrics("KuWo", parsed, "zh")
    }

    private fun kuwoHeaders(agent: String = "Mozilla/5.0"): Map<String, String> = mapOf(
        "User-Agent" to agent,
        "Referer" to "https://www.kuwo.cn/",
        "Accept-Encoding" to "identity",
    )

    private fun kuwoQuery(rid: Long): String {
        val request = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$rid&lrcx=1"
        return Base64.getEncoder().encodeToString(xor(request.toByteArray(StandardCharsets.US_ASCII)))
    }

    private fun decodeKuwo(response: ByteArray): String? {
        val prefix = "tp=content".toByteArray(StandardCharsets.US_ASCII)
        if (response.size < prefix.size || !prefix.indices.all { response[it] == prefix[it] }) return null
        val separator = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val at = indexOf(response, separator).takeIf { it >= 0 }?.plus(separator.size) ?: return null
        val inflated = InflaterInputStream(ByteArrayInputStream(response, at, response.size - at)).use { it.readBytes() }
        val encrypted = Base64.getMimeDecoder().decode(inflated.toString(StandardCharsets.US_ASCII).trim())
        return String(xor(encrypted), Charset.forName("GB18030")).takeIf { it.isNotBlank() }
    }

    private val KUWO_XOR = "yeelion".toByteArray(StandardCharsets.US_ASCII)
    private fun xor(value: ByteArray): ByteArray = ByteArray(value.size) { (value[it].toInt() xor KUWO_XOR[it % KUWO_XOR.size].toInt()).toByte() }
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        return -1
    }

    // ─────────────────────────── KuGou v2 ───────────────────────────

    private const val KUGOU_SEARCH = "https://lyrics.kugou.com/v2/search"
    private const val KUGOU_DOWNLOAD = "https://lyrics.kugou.com/v2/download"
    private const val KUGOU_SECRET = "OIlwieks28dk2k092lksi2UIkp"
    private val KUGOU_MID = md5Hex("HyperLyrics-Enhanced-online-translation")
    private val KUGOU_KRC_KEY = byteArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206.toByte(), 210.toByte(), 110, 105)

    private fun kugou(track: LyricsApi.Track): Lyrics? {
        val keyword = "${track.title} ${track.artist}".trim().take(200)
        val params = mapOf(
            "album_audio_id" to "0", "appid" to "1005", "clientver" to "20759",
            "duration" to ((track.durationMs / 1000) * 1000).toString(), "hash" to "",
            "keyword" to keyword, "lrctxt" to "1", "man" to "yes", "query_copyright" to "1",
        )
        val root = runCatching { JSONObject(text("$KUGOU_SEARCH?${signedQuery(params)}", kugouHeaders()) ?: return null) }.getOrNull() ?: return null
        if (root.optInt("errcode", 200) != 200) return null
        val candidates = root.optJSONObject("data")?.optJSONArray("candidates") ?: return null
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val score = score(item.optString("song"), item.optString("singer"), item.optLong("duration"),
                track.title, track.artist, track.durationMs)
            if (score > bestScore) { best = item; bestScore = score }
        }
        val candidate = best ?: return null
        val id = candidate.optString("download_id").ifBlank { candidate.optString("id") }
        val ak = candidate.optString("accesskey")
        val ct = candidate.optInt("contenttype")
        if (id.isBlank() || ak.isBlank()) return null
        val downloadParams = mapOf(
            "accesskey" to ak, "appid" to "1005", "clientver" to "20759",
            "contenttype" to ct.toString(), "download_id" to id,
        )
        val downloadRoot = runCatching { JSONObject(text("$KUGOU_DOWNLOAD?${signedQuery(downloadParams)}", kugouHeaders()) ?: return null) }.getOrNull() ?: return null
        if (downloadRoot.optInt("errcode", 200) != 200) return null
        val encoded = downloadRoot.optJSONObject("data")?.optString("content").orEmpty()
        val content = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        val parsed = parseKugou(content, track.durationMs) ?: return null
        return toLyrics("Kugou", parsed, "zh")
    }

    private fun kugouHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Accept-Encoding" to "identity",
        "User-Agent" to "Android-KuGou/20759",
        "clienttime" to System.currentTimeMillis().toString(),
        "mid" to KUGOU_MID, "uuid" to KUGOU_MID, "dfid" to "-",
        "userid" to "0", "token" to "",
    )

    private fun signedQuery(params: Map<String, String>): String {
        val sorted = params.toSortedMap()
        val joined = sorted.entries.joinToString("") { "${it.key}=${it.value}" }
        val signature = md5Hex("$KUGOU_SECRET$joined$KUGOU_SECRET")
        return (sorted + ("signature" to signature)).toSortedMap().entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
    }

    // ─────────────────────────── parsers ───────────────────────────

    private data class Parsed(val original: List<LyricLine>, val translated: List<LyricLine>, val romanized: List<LyricLine>)

    private fun toLyrics(provider: String, parsed: Parsed, translationLanguage: String?): Lyrics? {
        val lines = LyricsExtras.attach(parsed.original, parsed.translated, parsed.romanized)
        if (lines.isEmpty()) return null
        return Lyrics(
            lines = lines,
            synced = lines.any { it.timeMs > 0 },
            provider = provider,
            translationLanguage = if (parsed.translated.isEmpty()) null else translationLanguage,
        )
    }

    private val LRC_TIME = Regex("""\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?]""")
    private val KUWO_TIME = Regex("""^\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?]""")

    private fun parseLrc(text: String, durationMs: Long = 0L): List<LyricLine> {
        val found = mutableListOf<Pair<Long, String>>()
        text.lineSequence().forEach { raw ->
            val matches = LRC_TIME.findAll(raw).toList()
            if (matches.isEmpty()) return@forEach
            val body = raw.substring(matches.last().range.last + 1).trim()
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                val frac = m.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').toLongOrNull() ?: 0L
                found += (min * 60_000L + sec * 1000L + frac) to body
            }
        }
        return found.sortedBy { it.first }.mapIndexed { i, (start, body) ->
            val end = found.getOrNull(i + 1)?.first?.takeIf { it > start }
                ?: if (durationMs > start) durationMs else start + 3000L
            LyricLine(start, body, listOf(LyricWord(start, end, body)))
        }
    }

    private fun align(original: List<LyricLine>, extras: List<LyricLine>, separator: String = ""): List<LyricLine> {
        if (extras.isEmpty()) return emptyList()
        val used = BooleanArray(extras.size)
        return original.map { line ->
            var best = -1
            var bestDelta = Long.MAX_VALUE
            for (i in extras.indices) {
                if (used[i]) continue
                val delta = abs(extras[i].timeMs - line.timeMs)
                if (delta <= 1000L && delta < bestDelta) { best = i; bestDelta = delta }
            }
            if (best < 0) LyricLine(line.timeMs, "")
            else {
                used[best] = true
                val value = extras[best].words.orEmpty().joinToString(separator) { it.text }.trim()
                LyricLine(line.timeMs, value, listOf(LyricWord(line.timeMs, line.endMs(), value)))
            }
        }
    }

    private fun LyricLine.endMs(): Long = words?.lastOrNull()?.endMs ?: timeMs + 3000L

    private fun parseNetEase(yrc: String, lrc: String, tlyric: String, romalrc: String, durationMs: Long): Parsed? {
        val yrcLine = Regex("""^\[(\d+),(\d+)\](.*)$""")
        val yrcWord = Regex("""\((\d+),(\d+),\d+\)([^()]*)""")
        val original = if (yrc.isNotBlank()) {
            yrc.lineSequence().mapNotNull { raw ->
                val m = yrcLine.matchEntire(raw.trim()) ?: return@mapNotNull null
                val start = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val duration = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val body = m.groupValues[3]
                val words = yrcWord.findAll(body).map { w ->
                    val off = w.groupValues[1].toLongOrNull() ?: 0L
                    val len = w.groupValues[2].toLongOrNull() ?: 0L
                    LyricWord(start + off, start + off + len, w.groupValues[3])
                }.toList().ifEmpty { listOf(LyricWord(start, start + duration, body)) }
                LyricLine(start, words.joinToString("") { it.text }, words)
            }.sortedBy { it.timeMs }.toList()
        } else parseLrc(lrc, durationMs)
        if (original.isEmpty()) return null
        val trans = if (tlyric.isBlank()) emptyList() else align(original, parseLrc(tlyric, durationMs))
        val roma = if (romalrc.isBlank()) emptyList() else align(original, parseLrc(romalrc, durationMs), " ")
        return Parsed(original, trans, roma)
    }

    private val qrcLineRegex = Regex("""^\[(\d+),(\d+)\](.*)$""")
    private val qrcWordRegex = Regex("""\((\d+),(\d+)\)([^()]*)""")
    private val qrcXmlRegex = Regex("""<Lyric_1\s+LyricType="1"\s+LyricContent="(.*?)"\s*/>""", RegexOption.DOT_MATCHES_ALL)

    private fun parseQrcSet(originalText: String, transText: String, romaText: String): Parsed? {
        val original = parseQrc(originalText)
        if (original.isEmpty()) return null
        val trans = parseQrc(transText).ifEmpty { parseLrc(transText) }
        val roma = parseQrc(romaText).ifEmpty { parseLrc(romaText) }
        return Parsed(original, align(original, trans), align(original, roma, " "))
    }

    private fun parseQrc(text: String): List<LyricLine> {
        if (text.isBlank()) return emptyList()
        val body = qrcXmlRegex.find(text)?.groupValues?.get(1)?.xmlUnescape() ?: text
        return body.lineSequence().mapNotNull { raw ->
            val m = qrcLineRegex.matchEntire(raw.trim()) ?: return@mapNotNull null
            val start = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val end = start + duration
            val content = m.groupValues[3]
            val words = mutableListOf<LyricWord>()
            var cursor = 0L
            qrcWordRegex.findAll(content).forEach { w ->
                val off = w.groupValues[1].toLongOrNull() ?: return@forEach
                val len = w.groupValues[2].toLongOrNull() ?: return@forEach
                val textPart = w.groupValues[3]
                if (off > cursor && cursor.toInt() < content.length) {
                    val from = cursor.toInt().coerceAtMost(content.length)
                    val to = off.toInt().coerceAtMost(content.length)
                    if (to > from) words += LyricWord(start + cursor, start + off, content.substring(from, to))
                }
                words += LyricWord(start + off, start + off + len, textPart)
                cursor = off + len
            }
            if (cursor < duration && cursor.toInt() < content.length) words += LyricWord(start + cursor, end, content.substring(cursor.toInt()))
            if (words.isEmpty()) words += LyricWord(start, end, content)
            LyricLine(start, words.joinToString("") { it.text }.trim(), words.sortedBy { it.startMs })
        }.sortedBy { it.timeMs }.toList()
    }

    private val KUWO_WORD = Regex("""<(-?\d+),(-?\d+)>([^<]*)""")
    private val KUWO_SCALE = Regex("""\[kuwo:([0-7]+)]""", RegexOption.IGNORE_CASE)

    private fun parseKuwo(text: String, durationMs: Long): Parsed? {
        data class Raw(val begin: Long, val words: List<LyricWord>, val plain: Boolean, val auxiliary: Boolean, val body: String)
        val raw = mutableListOf<Raw>()
        text.lineSequence().forEach { line ->
            val m = KUWO_TIME.find(line) ?: return@forEach
            val begin = kuwoMillis(m)
            val body = line.substring(m.range.last + 1)
            val scaleRaw = KUWO_SCALE.find(body)?.groupValues?.get(1)?.toIntOrNull(8)
            val beginDiv = if (scaleRaw != null && scaleRaw / 10 > 0) (scaleRaw / 10) * 2 else 2
            val durationDiv = if (scaleRaw != null && scaleRaw % 10 > 0) (scaleRaw % 10) * 2 else 2
            val words = KUWO_WORD.findAll(body).map { w ->
                val a = w.groupValues[1].toLongOrNull() ?: 0L
                val b = w.groupValues[2].toLongOrNull() ?: 0L
                val relative = Math.floorDiv(a + b, beginDiv)
                val len = Math.floorDiv(a - b, durationDiv)
                LyricWord(begin + relative, begin + relative + len, w.groupValues[3])
            }.toList()
            val plain = words.isEmpty()
            val auxiliary = !plain && words.all { it.startMs == begin && it.endMs == begin }
            raw += Raw(begin, words, plain, auxiliary, body.replace(KUWO_WORD, "").replace(KUWO_SCALE, "").trim())
        }
        val original = mutableListOf<LyricLine>()
        val translated = mutableListOf<LyricLine>()
        val romanized = mutableListOf<LyricLine>()
        for (i in raw.indices) {
            val item = raw[i]
            when {
                item.auxiliary -> {
                    val target = original.lastOrNull() ?: continue
                    val value = item.words.joinToString("") { it.text }.trim()
                    if (translated.none { it.timeMs == target.timeMs }) translated += LyricLine(target.timeMs, value, listOf(LyricWord(target.timeMs, target.endMs(), value)))
                    else romanized += LyricLine(target.timeMs, value, listOf(LyricWord(target.timeMs, target.endMs(), value)))
                }
                item.plain && i + 1 < raw.size && raw[i + 1].plain && raw[i + 1].begin == item.begin -> {
                    val next = raw[i + 1]
                    val t = LyricLine(item.begin, item.body, listOf(LyricWord(item.begin, next.begin.takeIf { it > item.begin } ?: item.begin + 3000L, item.body)))
                    original += LyricLine(next.begin, next.body, listOf(LyricWord(next.begin, next.begin + 3000L, next.body)))
                    translated += t
                }
                item.plain -> original += LyricLine(item.begin, item.body, listOf(LyricWord(item.begin, item.begin + 3000L, item.body)))
                else -> original += LyricLine(item.begin, item.words.joinToString("") { it.text }.trim(), item.words)
            }
        }
        if (original.isEmpty()) return null
        return Parsed(original.sortedBy { it.timeMs }, translated.sortedBy { it.timeMs }, romanized.sortedBy { it.timeMs })
    }

    private fun kuwoMillis(m: MatchResult): Long {
        val min = m.groupValues[1].toLongOrNull() ?: 0L
        val sec = m.groupValues[2].toLongOrNull() ?: 0L
        val frac = m.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').toLongOrNull() ?: 0L
        return min * 60_000L + sec * 1000L + frac
    }

    private fun parseKugou(content: ByteArray, durationMs: Long): Parsed? {
        val isKrc = content.size >= 4 && String(content.copyOfRange(0, 4), StandardCharsets.US_ASCII) == "krc1"
        val text = if (isKrc) decryptKugouKrc(content) else String(content, StandardCharsets.UTF_8)
        if (text.isBlank()) return null
        val original = if (isKrc) parseKrc(text) else parseLrc(text, durationMs)
        if (original.isEmpty()) return null
        val translated = if (isKrc) parseKugouTranslation(text, original) else emptyList()
        return Parsed(original, translated, emptyList())
    }

    private fun decryptKugouKrc(input: ByteArray): String = runCatching {
        val decoded = ByteArray(input.size - 4) { i -> (input[i + 4].toInt() xor KUGOU_KRC_KEY[i % KUGOU_KRC_KEY.size].toInt()).toByte() }
        InflaterInputStream(ByteArrayInputStream(decoded)).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private val krcLineRegex = Regex("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
    private val krcWordRegex = Regex("""<(\d+)\s*,\s*(\d+)\s*,\s*\d+>""")

    private fun parseKrc(text: String): List<LyricLine> = text.lineSequence().mapNotNull { raw ->
        val m = krcLineRegex.matchEntire(raw.trim()) ?: return@mapNotNull null
        val begin = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val duration = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val body = m.groupValues[3]
        val tags = krcWordRegex.findAll(body).toList()
        val words = if (tags.isEmpty()) listOf(LyricWord(begin, begin + duration, body.trim()))
        else tags.mapIndexed { i, tag ->
            val off = tag.groupValues[1].toLongOrNull() ?: 0L
            val len = tag.groupValues[2].toLongOrNull() ?: 0L
            val from = tag.range.last + 1
            val to = tags.getOrNull(i + 1)?.range?.first ?: body.length
            LyricWord(begin + off, begin + off + len, body.substring(from, to).trim())
        }
        LyricLine(begin, words.joinToString("") { it.text }.trim(), words)
    }.sortedBy { it.timeMs }.toList()

    private val kugouLanguageRegex = Regex("""^\[language:(.*)]$""", RegexOption.IGNORE_CASE)

    private fun parseKugouTranslation(text: String, original: List<LyricLine>): List<LyricLine> {
        val encoded = text.lineSequence().mapNotNull { kugouLanguageRegex.matchEntire(it.trim())?.groupValues?.get(1) }.firstOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val sections = json.optJSONArray("content") ?: return emptyList()
        var values: JSONArray? = null
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            if (section.optInt("type") != 1) continue
            values = section.optJSONArray("lyricContent")?.takeIf { it.length() == original.size }
            if (values != null) break
        }
        values ?: return emptyList()
        return original.mapIndexed { i, line ->
            val chunks = values.optJSONArray(i) ?: return@mapIndexed LyricLine(line.timeMs, "")
            val value = (0 until chunks.length()).joinToString("") { chunks.optString(it) }.trim()
            LyricLine(line.timeMs, value, listOf(LyricWord(line.timeMs, line.endMs(), value)))
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun text(url: String, headers: Map<String, String> = emptyMap()): String? =
        bytes(url, headers)?.let { String(it, StandardCharsets.UTF_8) }

    private fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.newCall(builder.build()).execute()
        response.use { if (!it.isSuccessful) return null; return it.body?.bytes() }
    }.getOrNull()

    private fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val response = client.newCall(request).execute()
        response.use { if (!it.isSuccessful) return null; return it.body?.string() }
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun b64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun cleanTitle(title: String): String = title.replace(Regex("""\s*[（(][^)）]*[)）]\s*"""), " ").trim().ifEmpty { title.trim() }
    private fun score(name: String, by: String, ms: Long, title: String, artist: String, durationMs: Long): Int {
        val n = norm(name); val t = norm(title); val a = norm(by); val ar = norm(artist)
        var s = 0
        if (n == t) s += 6 else if (n.contains(t) || t.contains(n)) s += 3
        if (a == ar || (a.isNotBlank() && ar.contains(a))) s += 4 else if (ar.isNotBlank() && a.contains(ar)) s += 2
        if (ms > 0 && durationMs > 0 && abs(ms - durationMs) < 6000) s += 2
        return s
    }
    private fun norm(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    private fun kuwoTime(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) break
            out.write(buffer, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }
    private fun String.xmlUnescape(): String = replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private fun Char.hex() = Character.digit(this, 16)
}
