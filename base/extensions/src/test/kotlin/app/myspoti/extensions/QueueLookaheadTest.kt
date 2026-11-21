package app.myspoti.extensions

import kotlin.test.Test
import kotlin.test.assertEquals

class QueueLookaheadTest {
    @Test fun queueEventSeesFiveUpcomingTracks() {
        assertEquals((1..5).toList(), QueueLookahead.next((1..12).toList()))
        assertEquals(emptyList<Int>(), QueueLookahead.next(emptyList()))
    }
}
