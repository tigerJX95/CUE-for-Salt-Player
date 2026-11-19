package spw.cue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProbeWindowContent(
    playingTrack: CueTrack?,
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
    managingAlbum: Boolean,
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
    visibility: SongVisibilityRecords,
    onRestoreHiddenSongs: (Set<String>?) -> Unit,
    onSeek: () -> Unit
) {
    val colors = probeColors(isSystemInDarkTheme(), probeHostTheme())
    val tracks = result?.sheet?.tracks.orEmpty()
    var query by remember(result) { mutableStateOf("") }
    var filter by remember(result) { mutableStateOf(CueTrackStatusFilter.All) }
    val trackKeys = remember(tracks) { tracks.map(SongVisibility::key) }
    val filteredTracks = remember(tracks, trackKeys, query, filter, visibility) {
        filterManagedCueTracks(tracks, trackKeys, query, filter, visibility)
    }
    var checkedKeys by remember(result, query, filter) { mutableStateOf(emptySet<String>()) }
    val rows = remember(tracks, trackKeys, filteredTracks) { cueWindowRows(tracks, trackKeys, filteredTracks) }
    val playingIndex = cueWindowPlayingIndex(tracks, playingTrack)
    var locateRevision by remember(result) { mutableStateOf(0L) }
    var locateTarget by remember(result) { mutableStateOf(-1) }
    var handledLocate by remember(result) { mutableStateOf(0L) }
    val listState = key(result) { rememberLazyListState() }
    LaunchedEffect(result, query, filter, locateRevision) {
        val target = if (handledLocate != locateRevision) {
            handledLocate = locateRevision
            rows.indexOfFirst { it is CueWindowRow.Track && it.index == locateTarget }.coerceAtLeast(0)
        } else 0
        listState.scrollToItem(target)
    }
    val selectedVisible = filteredTracks.any { it.index == selectedRow }
    Column(
        Modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp, vertical = 18.dp),
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
                Box(Modifier.weight(1f).fillMaxWidth().clip(panelShape).background(colors.panel).border(1.dp, colors.border, panelShape)) {
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
                ProbeAction(if (managingAlbum) "重新识别 CUE" else "查找当前 CUE",
                    colors, enabled = !busy, onClick = onDiscover)
                ProbeAction("打开 CUE 文件…", colors, enabled = !busy, onClick = onOpen)
                ProbeText(
                    if (result?.sheet?.title != null) source.orEmpty() else "",
                    colors.secondary, 12, Modifier.weight(1f).padding(start = 4.dp),
                    lines = 1, alignment = TextAlign.End
                )
            }

            if (tracks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = query, onValueChange = { query = it }, enabled = !busy, singleLine = true,
                        textStyle = TextStyle(color = colors.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).clip(panelShape).background(colors.panel)
                            .border(1.dp, colors.border, panelShape).padding(horizontal = 12.dp, vertical = 9.dp)
                            .semantics { contentDescription = "搜索曲名或艺术家" },
                        decorationBox = { field ->
                            Box {
                                if (query.isEmpty()) ProbeText("搜索曲名或艺术家", colors.secondary, 14)
                                field()
                            }
                        }
                    )
                    if (query.isNotEmpty()) ProbeAction("清除", colors, enabled = !busy) { query = "" }
                    ProbeText("${filteredTracks.size} / ${tracks.size} 轨", colors.secondary, 12)
                }
            }
            if (tracks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CueTrackStatusFilter.entries.forEach { option ->
                        ProbeAction(option.label, colors, enabled = !busy, primary = filter == option) { filter = option }
                    }
                    Box(Modifier.weight(1f))
                    ProbeAction("定位播放", colors, enabled = !busy && playingIndex >= 0) {
                        query = ""
                        filter = CueTrackStatusFilter.All
                        locateTarget = playingIndex
                        locateRevision += 1
                        onSelect(playingIndex)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ProbeAction("全选结果", colors, enabled = !busy && filteredTracks.isNotEmpty()) {
                        checkedKeys = filteredTracks.mapTo(linkedSetOf()) { trackKeys[it.index] }
                    }
                    ProbeAction("清除勾选", colors, enabled = !busy && checkedKeys.isNotEmpty()) { checkedKeys = emptySet() }
                    ProbeText("已勾选 ${checkedKeys.size} 轨 · 点击左侧方框可多选", colors.secondary, 13)
                }
            }
            if (visibility.hiddenInSongs.isNotEmpty() || visibility.removedFromLibrary.isNotEmpty()) {
                val albumKeys = trackKeys.filterTo(mutableSetOf()) { visibility.label(it) != null }
                val recovery = cueRecoveryPlan(checkedKeys, filteredTracks, trackKeys, visibility)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProbeAction("恢复勾选分轨（${recovery.checked.size}）", colors, enabled = !busy && recovery.checked.isNotEmpty()) {
                        onRestoreHiddenSongs(recovery.checked)
                        checkedKeys = emptySet()
                    }
                    ProbeAction("恢复当前结果（${recovery.visible.size}）", colors, enabled = !busy && recovery.visible.isNotEmpty()) {
                        onRestoreHiddenSongs(recovery.visible)
                        checkedKeys = emptySet()
                    }
                    if (managingAlbum) {
                        ProbeAction("恢复本专辑", colors, enabled = !busy && albumKeys.isNotEmpty()) {
                            onRestoreHiddenSongs(albumKeys)
                        }
                    }
                    ProbeAction("全部恢复", colors, enabled = !busy) { onRestoreHiddenSongs(null) }
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().clip(panelShape)
                    .background(colors.panel).border(1.dp, colors.border, panelShape)
            ) {
                if (filteredTracks.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ProbeText(
                            if (busy) "正在读取 CUE…" else if (tracks.isEmpty()) "暂无分轨" else "没有匹配的分轨",
                            colors.secondary, 15, alignment = TextAlign.Center
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().background(colors.header)
                            .padding(start = 16.dp, end = 24.dp).height(38.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProbeText("选择", colors.secondary, 12, Modifier.width(34.dp))
                        ProbeText("轨号", colors.secondary, 12, Modifier.width(46.dp))
                        ProbeText("曲名 / 艺术家", colors.secondary, 12, Modifier.weight(1f))
                        TrackDurationCell("时长", colors.secondary)
                    }
                    key(result) {
                        Box(Modifier.weight(1f)) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = 14.dp),
                                state = listState
                            ) {
                                itemsIndexed(rows, key = { _, row ->
                                    when (row) {
                                        is CueWindowRow.Disc -> "disc:${row.audio}"
                                        is CueWindowRow.Track -> row.key
                                    }
                                }) { _, row ->
                                    when (row) {
                                        is CueWindowRow.Disc -> Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            ProbeText("光盘 ${row.number} · ${row.audio.fileName}", colors.secondary, 13,
                                                Modifier.weight(1f).padding(end = 8.dp), lines = 1)
                                            ProbeText("${row.matchedCount} / ${row.totalCount} 轨", colors.secondary, 12)
                                        }
                                        is CueWindowRow.Track -> {
                                            val sourceDuration = result?.matches?.get(row.track.audio)?.duration
                                                ?.takeIf { it > 0 } ?: row.track.endMs ?: 0L
                                            ProbeTrackRow(
                                                row.track, sourceDuration, row.index == selectedRow, !busy, colors,
                                                visibilityLabel = visibility.label(row.key),
                                                playing = row.track.matchesPlaybackTrack(playingTrack),
                                                checked = row.key in checkedKeys,
                                                onToggle = {
                                                    checkedKeys = if (row.key in checkedKeys) checkedKeys - row.key else checkedKeys + row.key
                                                },
                                                onSelect = { onSelect(row.index) }, onPlay = { onPlay(row.index) }
                                            )
                                        }
                                    }
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
                ProbeAction("跳到分轨起点", colors, enabled = canSeek && selectedVisible, primary = true, onClick = onSeek)
            }
        }
    }
}

@Composable
private fun ProbeTrackRow(
    track: CueTrack,
    sourceDuration: Long,
    selected: Boolean,
    enabled: Boolean,
    colors: ProbeColors,
    visibilityLabel: String?,
    playing: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onPlay: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> colors.selected
        hovered && enabled -> colors.hover
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(shape)
            .background(background)
            .hoverable(interaction, enabled = enabled)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onSelect, onDoubleClick = onPlay)
            .heightIn(min = 50.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
            Canvas(Modifier.size(28.dp).semantics { contentDescription = "勾选 ${track.title}" }
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() }) {
                val inset = 3.dp.toPx()
                val width = 1.5.dp.toPx()
                val color = if (checked) colors.accent else colors.secondary
                drawRoundRect(color, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(width))
                if (checked) {
                    drawLine(color, Offset(size.width * .28f, size.height * .51f), Offset(size.width * .44f, size.height * .67f), width, StrokeCap.Round)
                    drawLine(color, Offset(size.width * .44f, size.height * .67f), Offset(size.width * .74f, size.height * .34f), width, StrokeCap.Round)
                }
            }
        }
        ProbeText(
            track.number.toString().padStart(2, '0'),
            if (selected || playing) colors.accent else colors.secondary, 14, Modifier.width(46.dp),
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ProbeText(track.title, if (playing) colors.accent else colors.text, 14, lines = 1, weight = FontWeight.Medium)
            val details = listOfNotNull(track.performer, visibilityLabel, "播放中".takeIf { playing }).joinToString(" · ")
            if (details.isNotEmpty()) ProbeText(details, colors.secondary, 12, lines = 1)
        }
        val duration = CuePlaybackWindow.from(track, sourceDuration)?.durationMs
        TrackDurationCell(duration?.let(::formatCueTime) ?: "—", if (playing) colors.accent else colors.secondary)
    }
}

@Composable
private fun TrackDurationCell(text: String, color: Color) {
    ProbeText(text, color, 13, Modifier.width(88.dp), lines = 1, alignment = TextAlign.Center)
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        primary && enabled -> colors.accent
        hovered && enabled -> colors.hover
        else -> colors.header
    }
    Box(
        Modifier.clip(shape)
            .background(background)
            .border(1.dp, if (primary && enabled) Color.Transparent else colors.border, shape)
            .hoverable(interaction, enabled = enabled)
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
        style = TextStyle(
            color = color, fontSize = size.sp, lineHeight = (size + 5).sp, fontWeight = weight,
            fontFamily = probeHostTheme()?.detailStyle?.fontFamily, textAlign = alignment
        ),
        maxLines = lines, overflow = TextOverflow.Ellipsis
    )
}

private val panelShape = RoundedCornerShape(12.dp)

@Immutable
internal data class ProbeColors(
    val background: Color,
    val panel: Color,
    val header: Color,
    val border: Color,
    val hover: Color,
    val selected: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val secondary: Color,
    val disabled: Color
)

internal fun probeColors(systemDark: Boolean, host: SongRowTheme?): ProbeColors {
    val hostText = displayColor(host?.titleColor)
    val dark = hostText?.let { it.luminance() > .5f } ?: systemDark
    val background = if (dark) Color(0xFF191A1C) else Color(0xFFF6F7F9)
    val panel = if (dark) Color(0xFF222326) else Color.White
    val text = hostText ?: if (dark) Color(0xFFF0F2F4) else Color(0xFF20252B)
    val accent = displayColor(host?.accentColor) ?: if (dark) Color(0xFF42BFF5) else Color(0xFF006EA6)
    return ProbeColors(
        background = background,
        panel = panel,
        header = text.copy(alpha = if (dark) .047f else .043f).compositeOver(panel),
        border = text.copy(alpha = if (dark) .15f else .12f).compositeOver(panel),
        hover = text.copy(alpha = .085f).compositeOver(panel),
        selected = accent.copy(alpha = if (dark) .21f else .14f).compositeOver(panel),
        accent = accent,
        onAccent = if (accent.luminance() > .20f) Color(0xFF101316) else Color.White,
        text = text,
        secondary = displayColor(host?.detailColor) ?: if (dark) Color(0xFFBAC1C9) else Color(0xFF52606C),
        disabled = text.copy(alpha = .48f).compositeOver(panel)
    )
}

private fun displayColor(value: Long?): Color? = value?.let { Color(it.toULong()) }?.takeIf {
    it != Color.Unspecified && it.alpha >= .65f
}
