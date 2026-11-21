package app.myspoti.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueLookaheadTest {
    @Test fun queueEventSeesFiveUpcomingTracks() {
        assertEquals((1..5).toList(), QueueLookahead.next<Int>((1..12).toList()))
        assertEquals(emptyList<Int>(), QueueLookahead.next<Int>(emptyList()))
    }
}
