package spw.cue

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProbeWindowContent(
    version: String,
    source: String?,
    result: ProbeResult?,
    selectedRow: Int,
    status: String,
    busy: Boolean,
    canSeek: Boolean,
    diagnostics: Boolean,
    report: String,
    showingApi: Boolean,
    onDiscover: () -> Unit,
    onOpen: () -> Unit,
    onSelect: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    onToggleDiagnostics: () -> Unit,
    onShowApi: () -> Unit,
    onRetry: () -> Unit,
    onRefreshReport: () -> Unit,
    onDiagnoseSongs: () -> Unit,
    onMountSongs: () -> Unit,
    hiddenSongCount: Int,
    onRestoreHiddenSongs: () -> Unit,
    onSeek: () -> Unit
) {
    val colors = if (isSystemInDarkTheme()) darkColors else lightColors
    Column(
        Modifier.fillMaxSize().background(colors.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProbeText(if (diagnostics) "诊断" else "CUE 分轨", colors.text, 24, weight = FontWeight.SemiBold)
                ProbeText(
                    if (diagnostics) "CUE for Salt Player · v$version"
                    else result?.sheet?.title ?: source ?: "CUE for Salt Player · v$version",
                    colors.secondary, 13, lines = 1
                )
            }
            ProbeAction(if (diagnostics) "返回分轨" else "诊断", colors, onClick = onToggleDiagnostics)
        }

        if (diagnostics) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProbeAction(
                    if (showingApi) "CUE 诊断" else "原生接口报告",
                    colors, onClick = onShowApi
                )
                if (showingApi) ProbeAction("重新注册专辑页", colors, onClick = onRetry)
            }
            if (showingApi) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProbeAction("开始歌曲页诊断", colors, onClick = onDiagnoseSongs)
                    ProbeAction("本次试挂载分轨", colors, onClick = onMountSongs)
                    ProbeAction("刷新报告", colors, onClick = onRefreshReport)
                }
            }
            key(showingApi) {
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth().clip(panelShape).background(colors.panel)) {
                    SelectionContainer(
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 16.dp, top = 12.dp, end = 20.dp, bottom = 12.dp)
                    ) {
                        ProbeText(report.ifBlank { status }, colors.text, 13, lines = Int.MAX_VALUE)
                    }
                    VerticalScrollbar(
                        rememberScrollbarAdapter(scroll),
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp)
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProbeAction("查找当前 CUE", colors, enabled = !busy, onClick = onDiscover)
                ProbeAction("手动打开…", colors, enabled = !busy, onClick = onOpen)
                if (hiddenSongCount > 0) {
                    ProbeAction("恢复隐藏和移除的分轨", colors, onClick = onRestoreHiddenSongs)
                }
                ProbeText(
                    if (result?.sheet?.title != null) source.orEmpty() else "",
                    colors.secondary, 12, Modifier.weight(1f).padding(start = 4.dp),
                    lines = 1, alignment = TextAlign.End
                )
            }

            Column(Modifier.weight(1f).fillMaxWidth().clip(panelShape).background(colors.panel)) {
                val tracks = result?.sheet?.tracks.orEmpty()
                if (tracks.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ProbeText(
                            if (busy) "正在读取 CUE…" else "暂无分轨",
                            colors.secondary, 15, alignment = TextAlign.Center
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp).height(38.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProbeText("轨号", colors.secondary, 12, Modifier.width(46.dp))
                        ProbeText("曲名 / 艺术家", colors.secondary, 12, Modifier.weight(1f))
                        TrackTimes("起点", "终点", colors.secondary)
                    }
                    key(result) {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedRow.coerceAtLeast(0))
                        Box(Modifier.weight(1f)) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = 14.dp),
                                state = listState
                            ) {
                                itemsIndexed(tracks, key = { _, track -> track.number }) { index, track ->
                                    val end = track.endMs ?: result?.matches?.get(track.audio)?.duration?.takeIf { it > 0 }
                                    ProbeTrackRow(
                                        track, end, index == selectedRow, !busy, colors,
                                        onSelect = { onSelect(index) }, onPlay = { onPlay(index) }
                                    )
                                }
                            }
                            VerticalScrollbar(
                                rememberScrollbarAdapter(listState),
                                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProbeText(status, colors.secondary, 13, Modifier.weight(1f).padding(end = 12.dp), lines = 2)
            if (!diagnostics) {
                ProbeAction("跳到分轨起点", colors, enabled = canSeek, primary = true, onClick = onSeek)
            }
        }
    }
}

@Composable
private fun ProbeTrackRow(
    track: CueTrack,
    end: Long?,
    selected: Boolean,
    enabled: Boolean,
    colors: ProbeColors,
    onSelect: () -> Unit,
    onPlay: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(shape)
            .background(if (selected) colors.selected else Color.Transparent)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onSelect, onDoubleClick = onPlay)
            .heightIn(min = 50.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProbeText(
            track.number.toString().padStart(2, '0'),
            if (selected) colors.accent else colors.secondary, 14, Modifier.width(46.dp),
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProbeText(track.title, colors.text, 14, lines = 1, weight = FontWeight.Medium)
            track.performer?.let { ProbeText(it, colors.secondary, 12, lines = 1) }
        }
        TrackTimes(formatCueTime(track.startMs), end?.let(::formatCueTime) ?: "文件结束", colors.secondary)
    }
}

@Composable
private fun TrackTimes(start: String, end: String, color: Color) {
    ProbeText(start, color, 13, Modifier.width(88.dp), lines = 1, alignment = TextAlign.End)
    ProbeText(end, color, 13, Modifier.width(88.dp), lines = 1, alignment = TextAlign.End)
}

@Composable
private fun ProbeAction(
    title: String,
    colors: ProbeColors,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.clip(shape)
            .background(if (primary && enabled) colors.accent else colors.panel)
            .border(1.dp, if (primary && enabled) Color.Transparent else colors.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        val foreground = when {
            !enabled -> colors.disabled
            primary -> colors.onAccent
            else -> colors.text
        }
        ProbeText(title, foreground, 13, lines = 1, weight = FontWeight.Medium)
    }
}

@Composable
private fun ProbeText(
    text: String,
    color: Color,
    size: Int,
    modifier: Modifier = Modifier,
    lines: Int = 2,
    weight: FontWeight = FontWeight.Normal,
    alignment: TextAlign = TextAlign.Start
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size.sp, lineHeight = (size + 5).sp, fontWeight = weight, textAlign = alignment),
        maxLines = lines, overflow = TextOverflow.Ellipsis
    )
}

private val panelShape = RoundedCornerShape(12.dp)

@Immutable
private data class ProbeColors(
    val background: Color,
    val panel: Color,
    val border: Color,
    val selected: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val secondary: Color,
    val disabled: Color
)

private val lightColors = ProbeColors(
    background = Color(0xFFF4F5F7), panel = Color.White, border = Color(0xFFDCE0E5),
    selected = Color(0xFFE1F1FD), accent = Color(0xFF006DA3), onAccent = Color.White,
    text = Color(0xFF20262C), secondary = Color(0xFF52606C), disabled = Color(0xFF858E98)
)

private val darkColors = ProbeColors(
    background = Color(0xFF202020), panel = Color(0xFF292929), border = Color(0xFF444444),
    selected = Color(0xFF293B47), accent = Color(0xFF4CC2FF), onAccent = Color(0xFF102731),
    text = Color(0xFFF0F2F4), secondary = Color(0xFFB7BDC4), disabled = Color(0xFF828A93)
)
