package spw.cue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.awt.EventQueue

private val albumCueRowStyle = TextStyle(
    fontSize = 16.sp,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = "tnum"
)

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeAlbumCue(
    private val target: AlbumHookTarget,
    private val active: () -> Boolean,
    private val hideWholeTrack: () -> Boolean,
    private val lookupScope: CoroutineScope,
    private val report: (String) -> Unit
) {
    private data class MatchedAlbum(val page: Any, val trackId: String)
    private class DiscRequest(val ids: List<String>)
    private var discRequest: DiscRequest? = null
    private var discLookup: Job? = null
    private var discMatches by mutableStateOf<Pair<DiscRequest, Map<String, AlbumCue>>?>(null)
    private val multiDiscSeeking = mutableStateOf(false)
    private var matchedAlbum by mutableStateOf<Pair<MatchedAlbum, AlbumCue>?>(null)
    data class Artwork(val trackId: String, val image: ImageBitmap)
    var artwork by mutableStateOf<Artwork?>(null)
        private set

    fun beforeList(call: MethodHookParam) {
        if (!active()) return
        try {
            val args = call.args
            val tracks = target.albumTracks(args)
            if (tracks.isEmpty()) return
            val page = args.getOrNull(5) ?: return
            val scope = args.lastOrNull() ?: return
            report("已收到专辑页回调，等待插入分轨区")
            val (proxy, list) = target.recordList(scope)
            args[args.lastIndex] = proxy
            val result = try {
                // This host passes the modified arguments only through invokeOriginal.
                call.invokeOriginal()
            } finally {
                args[args.lastIndex] = scope
            }
            if (!list.hasFooter || !active()) return
            call.setResult(result)
            val track = tracks.singleOrNull()
            if (track == null) {
                val byId = tracks.associateBy { it.id }
                val ids = tracks.map { it.id }
                val request = if (hideWholeTrack()) {
                    loadDiscMatches(ids)
                } else {
                    cancelDiscLookup()
                    null
                }
                val matches = discMatches?.takeIf { it.first === request }?.second.orEmpty()
                val hiddenIds = matches.filterValues { CueRemoval.visibleAlbum(it).tracks.isNotEmpty() }.keys
                list.applyByTrack(ids, hiddenIds, append = { id, hidden ->
                    appendDisc(scope, byId.getValue(id), hidden, matches[id])
                }, appendFallback = { appendMultiDisc(scope, tracks) })
                return
            }

            cancelDiscLookup()
            val match = MatchedAlbum(page, track.id)
            val album = matchedAlbum?.takeIf { it.first == match }?.second
            list.apply(hideWholeTrack() && album != null && CueRemoval.visibleAlbum(album).tracks.isNotEmpty()) { hidden ->
                append(scope, match, track, hidden, compact = true)
            }
        } catch (error: Exception) {
            report("构建专辑分轨区失败：${error.javaClass.simpleName}")
        }
    }

    fun afterList(call: MethodHookParam) {
        if (!active() || call.isOriginalSkipped || call.throwable != null) return
        try {
            val tracks = target.albumTracks(call.args)
            if (tracks.isEmpty()) return
            val page = call.args.getOrNull(5) ?: return
            val scope = call.args.lastOrNull() ?: return
            val track = tracks.singleOrNull()
            if (track == null) {
                appendMultiDisc(scope, tracks)
            } else {
                append(scope, MatchedAlbum(page, track.id), track, hidden = false, compact = false)
            }
        } catch (error: Exception) {
            report("读取专辑页失败：${error.javaClass.simpleName}")
        }
    }

    private fun loadDiscMatches(ids: List<String>): DiscRequest {
        discRequest?.takeIf { it.ids == ids }?.let { return it }
        discLookup?.cancel()
        val request = DiscRequest(ids)
        discRequest = request
        discLookup = lookupScope.launch {
            try {
                val context = currentCoroutineContext()
                val albums = runInterruptible(Dispatchers.IO) {
                    LibraryProbe.findAlbumCueSources(request.ids) { active() && context.isActive }
                        .associate { it.trackId to it.cue }
                }
                EventQueue.invokeLater {
                    // A toggle or album change may have superseded this request while the lookup ran.
                    if (active() && discRequest === request) discMatches = request to albums
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (active()) report("查找多碟 CUE 失败：${error.javaClass.simpleName}")
            }
        }
        return request
    }

    private fun cancelDiscLookup() {
        discRequest = null
        discLookup?.cancel()
        discLookup = null
    }

    private fun appendDisc(scope: Any, track: AlbumHookTarget.Track, hidden: Boolean, resolvedCue: AlbumCue?) {
        try {
            val playAt = playbackFor(track)
            target.appendCue(scope, track.id) {
                if (active()) AlbumCueRows(track.id, hidden, playAt, active, report, multiDiscSeeking, resolvedCue)
            }
        } catch (error: Exception) {
            report("追加分轨区失败：${error.javaClass.simpleName}")
        }
    }

    private fun appendMultiDisc(scope: Any, tracks: List<AlbumHookTarget.Track>): Boolean {
        if (!active()) return false
        val ids = tracks.map { it.id }
        val byId = tracks.associateBy { it.id }
        target.appendCue(scope, ids.joinToString(prefix = "multi-disc:", separator = ",") { "${it.length}:$it" }) {
            if (active()) MultiDiscCueRows(ids, active, report) { id -> byId[id]?.let(::playbackFor) }
        }
        return true
    }

    private fun playbackFor(track: AlbumHookTarget.Track): ((Long, Boolean) -> Unit)? =
        target.playback?.let { playback ->
            { position: Long, switchSource: Boolean -> playback.playAt(track, position, switchSource) }
        }

    private fun append(
        scope: Any, match: MatchedAlbum, track: AlbumHookTarget.Track, hidden: Boolean, compact: Boolean
    ): Boolean {
        if (!active()) return false
        return try {
            val playAt = playbackFor(track)
            target.appendCue(scope, match.trackId) {
                if (active()) {
                    AlbumCueRows(match.trackId, hidden, playAt, active, report) { album, cover ->
                        if (album != null && album.tracks.isNotEmpty()) {
                            matchedAlbum = match to album
                            artwork = cover?.let { Artwork(match.trackId, it) }
                        } else if (matchedAlbum?.first == match) {
                            matchedAlbum = null
                            artwork = null
                        }
                    }
                }
            }
            report(if (compact) "已在整轨区下方插入分轨区" else "已通过后备路径追加分轨区")
            true
        } catch (error: Exception) {
            report("追加分轨区失败：${error.javaClass.simpleName}")
            false
        }
    }
}

@Composable
private fun AlbumCueRows(
    trackId: String, wholeTrackHidden: Boolean, playAt: ((Long, Boolean) -> Unit)?, active: () -> Boolean,
    report: (String) -> Unit, sharedSeeking: MutableState<Boolean>? = null,
    resolvedCue: AlbumCue? = null, onMatch: ((AlbumCue?, ImageBitmap?) -> Unit)? = null
) {
    var cue by remember(trackId, resolvedCue) { mutableStateOf(resolvedCue) }
    LaunchedEffect(trackId, resolvedCue) {
        if (resolvedCue != null) return@LaunchedEffect
        report("正在通过公开曲库查找整轨及对应的 CUE")
        try {
            val (loaded, image) = withContext(Dispatchers.IO) {
                val album = LibraryProbe.findAlbumCue(trackId)
                val image = album.tracks.firstOrNull()?.audio?.takeIf { onMatch != null }?.let { audio ->
                    runCatching { FolderCover.load(audio)?.toComposeImageBitmap() }.getOrNull()
                }
                album to image
            }
            cue = loaded
            val tracks = loaded.tracks
            onMatch?.invoke(loaded, image)
            report(if (tracks.isEmpty()) "已读取专辑整轨，未找到匹配 CUE" else "已识别 ${tracks.size} 轨，等待界面应用")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onMatch?.invoke(null, null)
            report("查找 CUE 失败：${if (error is IllegalStateException) error.message else error.javaClass.simpleName}")
        }
    }
    val album = cue?.let(CueRemoval::visibleAlbum) ?: return
    if (album.tracks.isEmpty()) return
    SideEffect { report("已提交至原生布局 · ${album.tracks.size} 轨") }
    val seeking = sharedSeeking ?: remember(trackId) { mutableStateOf(false) }
    AlbumCueSection(album, null, wholeTrackHidden, playAt, active, seeking)
}

@Composable
private fun MultiDiscCueRows(
    ids: List<String>, active: () -> Boolean, report: (String) -> Unit,
    playAt: (String) -> ((Long, Boolean) -> Unit)?
) {
    var sources by remember(ids) { mutableStateOf<List<AlbumCueSource>>(emptyList()) }
    val seeking = remember(ids) { mutableStateOf(false) }
    LaunchedEffect(ids) {
        report("正在查找专辑各碟的 CUE")
        try {
            val loaded = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                LibraryProbe.findAlbumCueSources(ids) { active() && context.isActive }
            }
            sources = loaded
            if (loaded.isEmpty()) report("未找到匹配的多碟 CUE")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (active()) report("查找多碟 CUE 失败：${error.javaClass.simpleName}")
        }
    }
    val visible = sources.map { it to CueRemoval.visibleAlbum(it.cue) }.filter { it.second.tracks.isNotEmpty() }
    if (visible.isEmpty()) return
    SideEffect { report("已提交至原生布局 · ${visible.size} 碟 · ${visible.sumOf { it.second.tracks.size }} 轨") }
    Column {
        for ((source, album) in visible) {
            key(source.trackId) {
                val filename = source.audio.fileName.toString()
                val duplicateName = sources.count { it.audio.fileName == source.audio.fileName } > 1
                val label = if (duplicateName) "${source.audio.parent.fileName}/$filename" else filename
                AlbumCueSection(album, label, false, playAt(source.trackId), active, seeking)
            }
        }
    }
}

@Composable
private fun AlbumCueSection(
    album: AlbumCue, label: String?, wholeTrackHidden: Boolean, playAt: ((Long, Boolean) -> Unit)?,
    active: () -> Boolean, seekingState: MutableState<Boolean>
) {
    var seeking by seekingState
    val scope = rememberCoroutineScope()
    val tracks = album.tracks
    if (tracks.isEmpty()) return
    val theme = nativeSongRowTheme()
    val gap = theme.gap.dp
    val durationWidth = with(LocalDensity.current) { (48.dp.roundToPx() + 2 * gap.roundToPx()).toDp() }
    val durationStyle = theme.detailStyle.copy(fontFeatureSettings = "tnum")
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        val heading = if (label == null) "分轨 ${tracks.size}" else "$label · 分轨 ${tracks.size}"
        NativeAlbumText(heading, Modifier.padding(horizontal = 4.dp, vertical = 12.dp), singleLine = true)
        if (wholeTrackHidden && playAt == null) {
            NativeAlbumText("使用专辑播放按钮开始播放后，点选分轨即可跳转。", Modifier.padding(horizontal = 12.dp))
        }
        for ((index, track) in tracks.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !seeking) {
                    if (active() && !seeking) {
                        seeking = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val context = currentCoroutineContext()
                                    LibraryProbe.playAlbumTrack(track, album.durationMs, { active() && context.isActive }, playAt)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                if (active()) WorkshopApi.ui.toast(
                                    error.message ?: "分轨定位失败", WorkshopApi.Ui.ToastType.Warning
                                )
                            } finally {
                                seeking = false
                            }
                        }
                    }
                }.heightIn(min = 40.dp).padding(start = 24.dp, end = gap, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NativeAlbumText(
                    track.number.toString().padStart(2, '0'),
                    Modifier.width(32.dp),
                    singleLine = true,
                    style = albumCueRowStyle
                )
                NativeAlbumText(
                    track.title,
                    Modifier.weight(1f).padding(end = 12.dp),
                    singleLine = true,
                    style = albumCueRowStyle
                )
                NativeAlbumText(
                    album.trackDurationLabels[index],
                    Modifier.width(durationWidth),
                    singleLine = true,
                    style = durationStyle,
                    color = theme.detailColor
                )
            }
        }
    }
}
