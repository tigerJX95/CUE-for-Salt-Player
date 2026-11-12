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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val report: (String) -> Unit
) {
    private data class MatchedAlbum(val page: Any, val trackId: String)
    private var matchedAlbum by mutableStateOf<Pair<MatchedAlbum, AlbumCue>?>(null)
    data class Artwork(val trackId: String, val image: ImageBitmap)
    var artwork by mutableStateOf<Artwork?>(null)
        private set

    fun beforeList(call: MethodHookParam) {
        if (!active()) return
        try {
            val args = call.args
            val track = target.singleTrack(args) ?: return
            val page = args.getOrNull(5) ?: return
            val match = MatchedAlbum(page, track.id)
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
            val album = matchedAlbum?.takeIf { it.first == match }?.second
            list.apply(hideWholeTrack() && album != null) { hidden ->
                append(scope, match, track, hidden, compact = true)
            }
        } catch (error: Exception) {
            report("构建专辑分轨区失败：${error.javaClass.simpleName}")
        }
    }

    fun afterList(call: MethodHookParam) {
        if (!active() || call.isOriginalSkipped || call.throwable != null) return
        try {
            val track = target.singleTrack(call.args) ?: return
            val page = call.args.getOrNull(5) ?: return
            val scope = call.args.lastOrNull() ?: return
            append(scope, MatchedAlbum(page, track.id), track, hidden = false, compact = false)
        } catch (error: Exception) {
            report("读取专辑页失败：${error.javaClass.simpleName}")
        }
    }

    private fun append(
        scope: Any, match: MatchedAlbum, track: AlbumHookTarget.Track, hidden: Boolean, compact: Boolean
    ): Boolean {
        if (!active()) return false
        return try {
            val playAt = target.playback?.let { playback ->
                { position: Long, switchSource: Boolean -> playback.playAt(track, position, switchSource) }
            }
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
    report: (String) -> Unit, onMatch: (AlbumCue?, ImageBitmap?) -> Unit
) {
    var cue by remember(trackId) { mutableStateOf<AlbumCue?>(null) }
    var seeking by remember(trackId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(trackId) {
        report("正在通过公开曲库查找整轨和同目录 CUE")
        try {
            val (loaded, image) = withContext(Dispatchers.IO) {
                val album = LibraryProbe.findAlbumCue(trackId)
                val image = album.tracks.firstOrNull()?.audio?.let { audio ->
                    runCatching { FolderCover.load(audio)?.toComposeImageBitmap() }.getOrNull()
                }
                album to image
            }
            cue = loaded
            val tracks = loaded.tracks
            onMatch(loaded, image)
            report(if (tracks.isEmpty()) "已读取专辑整轨，未找到匹配 CUE" else "已识别 ${tracks.size} 轨，等待界面应用")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onMatch(null, null)
            report("查找 CUE 失败：${if (error is IllegalStateException) error.message else error.javaClass.simpleName}")
        }
    }
    val album = cue ?: return
    val tracks = album.tracks
    if (tracks.isEmpty()) return
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val durationWidth = (album.timeColumnChars * 8 + 2).dp * fontScale
    SideEffect { report("已提交至原生布局 · ${tracks.size} 轨") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        NativeAlbumText("分轨 ${tracks.size}", Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
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
                }.heightIn(min = 40.dp).padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                    style = albumCueRowStyle
                )
            }
        }
    }
}
