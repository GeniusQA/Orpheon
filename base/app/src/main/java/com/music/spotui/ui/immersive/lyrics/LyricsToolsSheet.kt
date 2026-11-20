package com.music.spotui.ui.immersive.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.preferences.LyricsColorPref
import com.music.spotui.data.preferences.LyricsFontScalePref
import com.music.spotui.data.preferences.LyricsOffsetPref
import com.music.spotui.data.preferences.LyricsTitlePref
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.ImmersiveButton
import com.music.spotui.ui.immersive.components.ImmersiveCircleButton
import com.music.spotui.ui.immersive.components.ImmersiveSearchField
import com.music.spotui.ui.immersive.sheets.ImmersiveSheet
import com.music.spotui.ui.immersive.sheets.ImmersiveSheetTitle
import com.music.spotui.ui.look.ThinSlider
import com.music.spotui.ui.viewmodel.LyricsViewModel
import kotlinx.coroutines.launch
import java.util.Locale

private enum class LyricsToolsPage { Root, Search, Title, Progress, Font, Color }

@Composable
internal fun LyricsToolsSheet(vm: LyricsViewModel, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(LyricsToolsPage.Root) }
    val current = SongPlayer.currentLyricsTrack()
    ImmersiveSheet(onDismiss = onDismiss, expand = true, scrollable = false) {
        when (page) {
            LyricsToolsPage.Root -> RootTools(
                onBack = onDismiss,
                onSearch = { page = LyricsToolsPage.Search },
                onTitle = { page = LyricsToolsPage.Title },
                onProgress = { page = LyricsToolsPage.Progress },
                onFont = { page = LyricsToolsPage.Font },
                onColor = { page = LyricsToolsPage.Color },
            )
            LyricsToolsPage.Search -> LyricsSearchTools(
                initialTitle = current?.title.orEmpty(),
                initialArtist = current?.artist.orEmpty(),
                onBack = { page = LyricsToolsPage.Root },
                onUse = { hit ->
                    current?.let { vm.useLyrics(it, hit.lyrics) }
                    onDismiss()
                },
            )
            LyricsToolsPage.Title -> ToolPage(
                title = stringResource(R.string.lyrics_tools_title),
                detail = stringResource(R.string.lyrics_tools_title_hint),
                onBack = { page = LyricsToolsPage.Root },
            ) {
                val context = LocalContext.current
                SwitchRow(stringResource(R.string.lyrics_tools_title), LyricsTitlePref.shown) {
                    LyricsTitlePref.set(context, it)
                }
            }
            LyricsToolsPage.Progress -> ProgressTools(onBack = { page = LyricsToolsPage.Root })
            LyricsToolsPage.Font -> FontTools(onBack = { page = LyricsToolsPage.Root })
            LyricsToolsPage.Color -> ColorTools(onBack = { page = LyricsToolsPage.Root })
        }
    }
}

@Composable
private fun RootTools(
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onTitle: () -> Unit,
    onProgress: () -> Unit,
    onFont: () -> Unit,
    onColor: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        ToolsTitle(stringResource(R.string.lyrics_tools), onBack = onBack)
        val entries = listOf(
            Triple<ImageVector, Int, () -> Unit>(Icons.Rounded.Search, R.string.lyrics_tools_search, onSearch),
            Triple(Icons.Rounded.Title, R.string.lyrics_tools_title, onTitle),
            Triple(Icons.Rounded.Replay, R.string.lyrics_tools_progress, onProgress),
            Triple(Icons.Rounded.FormatSize, R.string.lyrics_tools_font, onFont),
            Triple(Icons.Rounded.ColorLens, R.string.lyrics_tools_color, onColor),
        )
        entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (icon, label, action) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        ImmersiveCircleButton(icon, null, action, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(label),
                            style = Immersive.Caption,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable(onClick = action),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(18.dp))
        ImmersiveButton(stringResource(R.string.lyrics_tools_close), onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ToolPage(
    title: String,
    detail: String? = null,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        ToolsTitle(title, subtitle = detail, onBack = onBack)
        content()
        Spacer(Modifier.height(18.dp))
        ImmersiveButton(stringResource(R.string.lyrics_tools_close), onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ToolsTitle(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ImmersiveCircleButton(
            Icons.Rounded.Replay,
            stringResource(R.string.lyrics_tools_close),
            onBack,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.width(12.dp))
        ImmersiveSheetTitle(title, subtitle = subtitle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Immersive.Body, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(38.dp, 22.dp).background(if (checked) Immersive.Accent else Immersive.Raised, CircleShape),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(16.dp).background(if (checked) Color.Black else Immersive.TextSecondary, CircleShape))
        }
    }
}

@Composable
private fun ProgressTools(onBack: () -> Unit) {
    val context = LocalContext.current
    val offset = LyricsOffsetPref.ms
    ToolPage(stringResource(R.string.lyrics_tools_progress), stringResource(R.string.lyrics_tools_progress_detail), onBack) {
        val seconds = offset / 1000f
        Text(
            text = "${if (seconds > 0f) "+" else ""}${String.format(Locale.ROOT, "%.1f", seconds)} s",
            style = Immersive.Headline,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ImmersiveCircleButton(Icons.Rounded.Remove, "-0.5 s", { LyricsOffsetPref.set(context, LyricsOffsetPref.ms - 500) })
            ImmersiveCircleButton(Icons.Rounded.Replay, stringResource(R.string.lyrics_tools_progress_reset), { LyricsOffsetPref.set(context, 0L) })
            ImmersiveCircleButton(Icons.Rounded.Add, "+0.5 s", { LyricsOffsetPref.set(context, LyricsOffsetPref.ms + 500) })
        }
    }
}

@Composable
private fun FontTools(onBack: () -> Unit) {
    val context = LocalContext.current
    val scale = LyricsFontScalePref.value
    ToolPage(stringResource(R.string.lyrics_tools_font), stringResource(R.string.lyrics_tools_font_detail), onBack) {
        Text(
            text = "${String.format(Locale.ROOT, "%.2f", scale)}×",
            style = Immersive.Headline,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            textAlign = TextAlign.Center,
        )
        ThinSlider(
            value = (scale - 0.7f) / 1.1f,
            onValueChange = { value -> LyricsFontScalePref.set(context, 0.7f + value * 1.1f) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Text(
            "A",
            style = Immersive.Lyric.copy(fontSize = (30f * scale).sp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColorTools(onBack: () -> Unit) {
    val context = LocalContext.current
    val selected = LyricsColorPref.value
    val colors = listOf(
        null,
        Color.White,
        Color(0xFFFF4F7B),
        Color(0xFFFFCC00),
        Color(0xFF4CD964),
        Color(0xFF5AC8FA),
        Color(0xFFAF52DE),
        Color(0xFFFF9500),
    )
    ToolPage(stringResource(R.string.lyrics_tools_color), stringResource(R.string.lyrics_tools_color_detail), onBack) {
        Text(
            "Aa",
            style = Immersive.Lyric.copy(color = selected ?: Color.White),
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            colors.forEach { color ->
                Box(
                    Modifier.size(38.dp).background(if (color == null) Immersive.Raised else color)
                        .clickable { LyricsColorPref.set(context, color?.value?.toInt()) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected == color) {
                        Box(Modifier.size(12.dp).background(if (color == null) Immersive.Accent else Color.Black, CircleShape))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        RgbSliders(selected)
    }
}

@Composable
private fun RgbSliders(selected: Color?) {
    val base = selected ?: Color.White
    ColorSlider("R", base.red) { Color(it, base.green, base.blue) }
    ColorSlider("G", base.green) { Color(base.red, it, base.blue) }
    ColorSlider("B", base.blue) { Color(base.red, base.green, it) }
}

@Composable
private fun ColorSlider(label: String, value: Float, color: (Float) -> Color) {
    val context = LocalContext.current
    Text(label, style = Immersive.Caption, modifier = Modifier.padding(top = 8.dp))
    ThinSlider(
        value = value,
        onValueChange = { value -> LyricsColorPref.set(context, color(value).value.toInt()) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LyricsSearchTools(
    initialTitle: String,
    initialArtist: String,
    onBack: () -> Unit,
    onUse: (LyricsApi.SearchHit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var results by remember { mutableStateOf<List<LyricsApi.SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val pager = rememberPagerState(pageCount = { results.size })
    fun search() {
        if (searching || title.isBlank()) return
        searching = true
        message = null
        results = emptyList()
        scope.launch {
            results = LyricsApi.searchSources(title, artist)
            searching = false
            if (results.isEmpty()) message = Graph.app.getString(R.string.lyrics_tools_no_results)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ToolsTitle(stringResource(R.string.lyrics_tools_search), onBack = onBack)
        ImmersiveSearchField(title, { title = it }, ::search, placeholder = stringResource(R.string.lyrics_tools_search_song))
        Spacer(Modifier.height(8.dp))
        ImmersiveSearchField(artist, { artist = it }, ::search, placeholder = stringResource(R.string.lyrics_tools_search_artist))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImmersiveButton(stringResource(R.string.lyrics_tools_search_action), ::search, Modifier.weight(1f), filled = true)
            if (results.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("${pager.currentPage + 1}/${results.size}", style = Immersive.Caption)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            searching -> Text(
                stringResource(R.string.lyrics_tools_searching), style = Immersive.Body,
                modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center,
            )
            results.isEmpty() -> Text(
                message.orEmpty(), style = Immersive.Body,
                modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center,
            )
            else -> {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { index ->
                    val hit = results[index]
                    Column(Modifier.fillMaxSize()) {
                        Text(
                            hit.sourceName, style = Immersive.Subhead,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = TextAlign.Center,
                        )
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            itemsIndexed(hit.lyrics.lines) { _, line ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(line.text, style = Immersive.Body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                    line.translation?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = Immersive.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                    }
                                    line.romanization?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = Immersive.Caption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                ImmersiveButton(
                    stringResource(R.string.lyrics_tools_use),
                    { if (results.isNotEmpty()) onUse(results[pager.currentPage.coerceIn(0, results.lastIndex)]) },
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    filled = true,
                )
            }
        }
    }
}
