package com.music.spotui.ui.immersive.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.engine.R
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.preferences.LyricsSourcePref
import com.music.spotui.ui.immersive.Immersive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The three-party online-source configuration: one switch per source, drag handles for the
 * fallback order, and the "auto pick the best source" switch. The settings themselves live in
 * [LyricsSourcePref], so this page only renders and commits them.
 */
@Composable
internal fun LyricsSourcesPage(back: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var auto by remember { mutableStateOf(LyricsSourcePref.isAuto(ctx)) }
    var order by remember { mutableStateOf(LyricsSourcePref.savedOrder(ctx)) }
    var enabled by remember { mutableStateOf(LyricsSourcePref.ALL.associateWith { LyricsSourcePref.isEnabled(ctx, it) }) }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val ids = order.toMutableList()
        val old = ids.indexOf(from.key as? String)
        val new = ids.indexOf(to.key as? String)
        if (old < 0 || new < 0 || old == new) return@rememberReorderableLazyListState
        ids.add(new, ids.removeAt(old))
        LyricsSourcePref.setOrder(ctx, ids)
        order = LyricsSourcePref.savedOrder(ctx)
        LyricsApi.forgetAll()
    }

    val shown = if (auto) LyricsSourcePref.ALL else order

    ImmersivePage(
        title = stringResource(R.string.lyrics_extra_title),
        onBack = back,
        listState = listState,
    ) {
        item(key = "sources:auto") {
            ImmersiveSwitchRow(
                "自动选择最优来源",
                checked = auto,
                onChecked = {
                    auto = it
                    LyricsSourcePref.setAuto(ctx, it)
                    LyricsApi.forgetAll()
                },
                detail = if (auto) "TTML 源优先，其余来源同时检索、先到先显示，逐字歌词或更优的翻译到达后自动切换" else "关闭后按下方顺序依次尝试，可长按手柄拖动排序",
            )
        }
        item(key = "sources:header") {
            Text(
                "平台来源",
                style = Immersive.Caption,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
            )
        }
        items(shown, key = { it }) { id ->
            ReorderableItem(reorder, key = id, enabled = !auto) { dragging ->
                val on = enabled[id] == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Immersive.Gutter, vertical = 3.dp)
                        .heightIn(min = 56.dp)
                        .background(Immersive.Raised, RoundedCornerShape(14.dp))
                        .toggleable(on, role = Role.Switch) {
                            if (!it && enabled.values.count { v -> v } <= 1) return@toggleable
                            if (id == "genius")
                                com.music.spotui.data.preferences.GeniusPref.set(ctx, it)
                            else LyricsSourcePref.setEnabled(ctx, id, it)
                            enabled = enabled.toMutableMap().also { m -> m[id] = it }
                            LyricsApi.forgetAll()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "${shown.indexOf(id) + 1}",
                        color = Immersive.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            LyricsSourcePref.NAMES[id] ?: id,
                            style = Immersive.Body.copy(color = if (on) Immersive.Text else Immersive.TextTertiary),
                        )
                        if (id == "genius") Text(
                            stringResource(app.myspoti.engine.R.string.settings_genius_detail),
                            style = Immersive.Caption.copy(color = Immersive.TextTertiary),
                            maxLines = 2,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = on, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = if (auto) "自动模式下顺序由应用决定" else "拖动排序",
                        tint = if (auto) Immersive.TextTertiary else Immersive.TextSecondary,
                        modifier = Modifier
                            .width(24.dp)
                            .draggableHandle(enabled = !auto),
                    )
                }
            }
        }
    }
}
