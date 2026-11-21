package com.music.spotui.ui.player

import androidx.test.platform.app.InstrumentationRegistry
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CatalogueTranslationLiveTest {
    @Test fun automaticLookupFindsCatalogueTranslation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalogueTranslation") == "true")
        val inst = InstrumentationRegistry.getInstrumentation()
        val track = LyricsApi.Track("0096wJntoqxtkmSXMn55Pr", "NEO", "じん", "NEO", 0)
        LyricsApi.forget(track)
        val vm = LyricsViewModel()
        inst.runOnMainSync { vm.load(track) }
        withTimeout(90000) { vm.state.first { it is LyricsViewModel.State.Loaded && it.lyrics.translationsFor("zh") != null } }
        inst.runOnMainSync { vm.toggleTranslation("zh") }
        val shown = withTimeout(45000) { vm.state.first { it is LyricsViewModel.State.Loaded && it.showTranslation } } as LyricsViewModel.State.Loaded
        assertTrue(shown.translationBySource)
        assertTrue(shown.translations.orEmpty().any { it.isNotBlank() })
    }
}
