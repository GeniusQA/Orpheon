package com.music.spotui.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.data.preferences.LyricsSourcePref
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText

/**
 * Lyrics sources configuration: an "auto pick the best source" switch and, once it is off,
 * a per-source on/off switch plus up/down arrows to set the order. Mirrors the three-party
 * online-source settings: auto on = built-in best order; auto off = the user's own order.
 */
@Composable
internal fun LyricsSourceConfig(ctx: android.content.Context) {
    var expanded by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(LyricsSourcePref.isAuto(ctx)) }
    var order by remember { mutableStateOf(LyricsSourcePref.savedOrder(ctx)) }
    var enabled by remember { mutableStateOf(LyricsSourcePref.ALL.associateWith { LyricsSourcePref.isEnabled(ctx, it) }) }

    fun commitOrder() { LyricsSourcePref.setOrder(ctx, order); order = LyricsSourcePref.savedOrder(ctx) }

    Column(Modifier.fillMaxWidth()) {
        // Entry row -> expands the config in place.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { expanded = !expanded }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("歌词来源设置", color = AppText, fontSize = 16.sp)
                Text(
                    if (auto) "自动选择最优来源" else "自定义来源与顺序",
                    color = AppMuted, fontSize = 12.sp,
                )
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = AppMuted, modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            // Auto pick: when on, the order below is ignored (shown greyed, arrows disabled).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(auto, role = Role.Switch) {
                        auto = it
                        LyricsSourcePref.setAuto(ctx, it)
                        com.music.spotui.data.api.LyricsApi.forgetAll()
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动选择最优来源", color = AppText, fontSize = 15.sp)
                    Text("TTML 源优先，其余来源同时检索、先到先显示，逐字歌词或更优的翻译到达后自动切换", color = AppMuted, fontSize = 12.sp)
                }
                Switch(checked = auto, onCheckedChange = null)
            }

            Text(
                if (auto) "来源（自动模式，顺序由应用决定）" else "来源（关闭后按此顺序依次尝试，可用箭头调整）",
                color = AppMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp),
            )
            order.forEachIndexed { i, id ->
                val on = enabled[id] == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .toggleable(on, role = Role.Switch) {
                            // Guard: keep at least one source enabled.
                            if (!it && enabled.values.count { v -> v } <= 1) return@toggleable
                            if (id == "genius")
                                com.music.spotui.data.preferences.GeniusPref.set(ctx, it)
                            else LyricsSourcePref.setEnabled(ctx, id, it)
                            enabled = enabled.toMutableMap().also { m -> m[id] = it }
                            com.music.spotui.data.api.LyricsApi.forgetAll()
                        }
                        .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text("${i + 1}", color = AppAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 20.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(LyricsSourcePref.NAMES[id] ?: id, color = if (on) AppText else AppMuted, fontSize = 15.sp)
                        if (id == "genius") Text(
                            androidx.compose.ui.res.stringResource(app.myspoti.engine.R.string.settings_genius_detail),
                            color = AppMuted, fontSize = 12.sp,
                        )
                    }
                    if (!auto) {
                        IconButton(onClick = { LyricsSourcePref.move(ctx, id, -1); commitOrder() },
                            enabled = i > 0, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移",
                                tint = if (i > 0) AppText else AppMuted.copy(alpha = 0.3f))
                        }
                        IconButton(onClick = { LyricsSourcePref.move(ctx, id, 1); commitOrder() },
                            enabled = i < order.size - 1, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移",
                                tint = if (i < order.size - 1) AppText else AppMuted.copy(alpha = 0.3f))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = on, onCheckedChange = null)
                }
            }
        }
    }
}
