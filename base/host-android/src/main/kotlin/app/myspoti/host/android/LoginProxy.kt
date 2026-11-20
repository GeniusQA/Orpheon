package app.myspoti.host.android

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Fetches Spotify's sign-in pages and requests outside the WebView.
 *
 * Some WebViews cannot suppress `X-Requested-With: <package>` (Android WebView 157 here). That
 * header is one thing no browser sends, and Spotify's `/login/code/send` answers it with 400 and a
 * captcha: the same account signs in from a real browser while the app is stuck. The WebView's
 * music requests already go through [MediaProxy] for the same reason; the account host needs the
 * same hop, with its cookies carried in both directions.
 */
internal class LoginProxy(
    private val client: OkHttpClient,
    private val identity: () -> Pair<String, String>?,
    private val cookies: () -> CookieManager,
) {
    fun handles(url: String): Boolean = url.startsWith(HOST)

    fun fetch(request: WebResourceRequest): WebResourceResponse? = runCatching {
        val url = request.url.toString()
        val method = (request.method ?: "GET").uppercase()
        val builder = Request.Builder().url(url)

        val contentType = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", true) }?.value
        val bodyBytes = postData(request)
        val body = if (bodyBytes != null && method !in SIMPLE_METHODS) {
            RequestBody.create(contentType?.let { MediaType.parse(it) }, bodyBytes)
        } else null
        builder.method(method, body)

        request.requestHeaders.forEach { (key, value) ->
            if (key.lowercase() !in SKIP) builder.header(key, value)
        }
        identity()?.let { (userAgent, language) ->
            builder.header("User-Agent", userAgent)
            if (language.isNotEmpty()) builder.header("Accept-Language", language)
        }
        cookies().getCookie(url)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }

        val response = client.newCall(builder.build()).execute()
        try {
            response.headers("Set-Cookie").forEach { cookie ->
                runCatching { cookies().setCookie(url, cookie) }
            }
            val headers = response.headers.toMultimap()
                .filterKeys { !it.equals("Set-Cookie", true) }
                .mapValues { (_, values) -> values.joinToString(",") }
            val mediaType = response.body?.contentType()
            val bytes = response.body?.bytes() ?: ByteArray(0)
            WebResourceResponse(
                mediaType?.toString()?.substringBefore(';') ?: "text/html",
                mediaType?.charset()?.name(),
                response.code,
                response.message.ifBlank { "OK" },
                headers,
                ByteArrayInputStream(bytes),
            )
        } finally {
            response.close()
        }
    }.getOrNull()

    /** WebResourceRequest's POST stream is not exposed to this module's Kotlin stub; read it reflectively. */
    private fun postData(request: WebResourceRequest): ByteArray? = runCatching {
        (request.javaClass.getMethod("getPostData").invoke(request) as? InputStream)?.use { it.readBytes() }
    }.getOrNull()

    companion object {
        private const val HOST = "https://accounts.spotify.com/"
        private val SIMPLE_METHODS = setOf("GET", "HEAD")
        private val SKIP = setOf(
            "host", "connection", "accept-encoding", "content-length", "x-requested-with",
            "user-agent", "accept-language", "cookie",
        )

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
