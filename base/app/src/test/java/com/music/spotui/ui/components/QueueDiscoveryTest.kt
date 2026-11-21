package com.music.spotui.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueDiscoveryTest {
    @Test fun selectFreshUrisKeepsSpotifyTracksOnly() {
        assertEquals(
            listOf("spotify:track:2", "spotify:track:4"),
            QueueDiscovery.selectFreshUris(
                listOf("spotify:track:1", "spotify:track:2", "ytmusic:track:x", "spotify:track:4"),
                setOf("spotify:track:1"),
                limit = 5,
            ),
        )
    }

    @Test fun dominantArtistsAreRankedAcrossTheQueue() {
        assertEquals(
            listOf("Artist B", "Artist A"),
            QueueDiscovery.dominantArtists(
                listOf("Artist A, Artist B", "Artist C", "Artist B", "Artist B, Artist D", "Artist A"),
                limit = 2,
            ),
        )
    }

    @Test fun buildQueryUsesWholeQueueSeedArtists() {
        assertEquals("Daft Punk Justice mix", QueueDiscovery.buildQuery(QueueMood.DISCOVER, listOf("Daft Punk", "Justice")))
        assertEquals("Daft Punk essentials", QueueDiscovery.buildQuery(QueueMood.FAMILIAR, listOf("Daft Punk")))
        assertEquals(QueueMood.CHILL.query, QueueDiscovery.buildQuery(QueueMood.CHILL, listOf("Daft Punk")))
    }

    @Test fun saveTrackIdsRespectCurrentAndContextFlags() {
        assertEquals(
            listOf("q1", "q2"),
            QueueDiscovery.buildSaveTrackIds("c1", listOf("q1", "q2", "q1"), listOf("n1"), false, false),
        )
        assertEquals(
            listOf("c1", "q1", "n1"),
            QueueDiscovery.buildSaveTrackIds("c1", listOf("q1"), listOf("n1"), true, true),
        )
    }
}
