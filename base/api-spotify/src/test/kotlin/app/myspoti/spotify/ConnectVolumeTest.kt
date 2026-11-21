package app.myspoti.spotify

import app.myspoti.spotify.bridge.AdSchedule
import app.myspoti.spotify.bridge.Bridge
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #159: a speaker at 0 must not be raised to full volume by repairs made for this page's player.
 * The page's volume is Connect's volume only while this page is the active device.
 */
class ConnectVolumeTest {
    private class FakeHost : BridgeHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
        @Volatile var active = false
        @Volatile var playerVolume = 0.0
        val sets = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var time = 10.0

        override suspend fun evaluate(script: String) {
            val m = Regex("""Myspoti\.call\((\d+), "([^"]+)", (.*)\)\.catch""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return
            val value = when (m.groupValues[2]) {
                "media.state" -> {
                    time += 2.0
                    """{"paused":false,"muted":false,"volume":1,"level":1,"own":1,"time":$time,"readyState":4,"ended":false,"playerVolume":$playerVolume,"active":$active}"""
                }
                "volume.get" -> "$playerVolume"
                "volume.set" -> { sets += m.groupValues[3]; "null" }
                else -> "null"
            }
            messages.emit("""{"type":"result","id":${m.groupValues[1]},"ok":true,"value":$value}""")
        }

        suspend fun post(json: String) = messages.emit(json)
    }

    private val time = VirtualTime()
    private lateinit var scope: CoroutineScope
    private lateinit var host: FakeHost
    private lateinit var bridge: Bridge
    private lateinit var player: SpotifyPlayer

    @BeforeEach fun up() {
        AdSchedule.clear()
        scope = time.scope
        host = FakeHost()
        bridge = Bridge(host, scope, time::now)
        player = SpotifyPlayer(bridge, scope)
    }

    @AfterEach fun down() {
        AdSchedule.clear()
        scope.cancel()
    }

    private suspend fun waitFor(what: String, ms: Long = 4_000, cond: () -> Boolean) = time.waitFor(what, ms, cond)

    private suspend fun playing() {
        host.post("""{"type":"ready"}""")
        waitFor("page ready") { bridge.ready.value }
        val item = """{"kind":"track","uri":"spotify:track:A","uid":"uA","name":"A","durationMs":200000}"""
        host.post("""{"type":"state","state":{"track":$item,"contextUri":"spotify:playlist:C","next":null,"isPaused":false,"isBuffering":false,
            |"positionMs":10000,"timestamp":${time.now()},"restrictions":{}}}""".trimMargin())
        waitFor("state") { player.state.value?.item?.name == "A" }
    }

    @Test fun `a speaker at zero is never set to full volume by the page`() = time.test {
        playing()
        delay(14_000)
        assertEquals(emptyList<String>(), host.sets.toList())
    }

    @Test fun `a stale active flag with no known volume cannot raise a muted speaker`() = time.test {
        host.active = true
        playing()
        delay(14_000)
        assertEquals(emptyList<String>(), host.sets.toList())
    }

    @Test fun `this page silent while active gets its volume back`() = time.test {
        player.setVolume(0.25)
        host.sets.clear()
        host.active = true
        playing()
        waitFor("volume set", ms = 14_000) { host.sets.isNotEmpty() }
    }
}
