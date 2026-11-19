package spw.cue

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeCueShuffle(
    private val host: HostCurrentPlayback,
    private val active: () -> Boolean,
    private val removed: () -> Set<String>,
    private val report: (String) -> Unit
) : AutoCloseable {
    private val queue = HostCueQueue()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val revision = MutableStateFlow(0L)
    private var appliedRevision = 0L
    private val parsed = CueDiscovery.ParsedCueCache()
    private val boundary = CueShuffleBoundary()
    private val hooks = mutableListOf<AutoCloseable>()
    private data class Pool(
        val queue: HostCueQueue.Snapshot, val entries: List<CueShuffleEntry>, val timelines: Map<Path, AlbumCue>
    )
    @Volatile private var pool: Pool? = null
    private var pending: CuePendingTransition? = null
    private val history = ArrayDeque<CueShuffleEntry>()
    @Volatile private var closed = false
    private val bypass = ThreadLocal<Boolean>()

    init {
        try {
            val controller = HostCatalog.type("com.xuncorp.voxzen.service.PlaybackController")
            for ((name, direction) in listOf("previous" to -1, "next" to 1)) {
                val method = controller.getMethod(name)
                hooks += WorkshopApi.hookRegistrar.hookMethod(controller.name, name, emptyList(), 100) {
                    before { call ->
                        try {
                            if (bypass.get() == true || call.isOriginalSkipped || !active() || playbackMode() !in setOf("Random", "Circle", "CircleEnd")) return@before
                            if (!randomMode() && !atSourceEdge(direction)) return@before
                            if (EventQueue.isDispatchThread()) {
                                if (request(direction)) call.setResult(null)
                            } else if (pool?.timelines?.isNotEmpty() == true) {
                                call.setResult(null)
                                EventQueue.invokeLater {
                                    if (!active()) return@invokeLater
                                    try {
                                        if (!request(direction)) {
                                            bypass.set(true)
                                            try { queue.nativeStep(direction) } finally { bypass.remove() }
                                        }
                                    } catch (error: Exception) {
                                        report("随机切换失败：${error.javaClass.simpleName}")
                                    } catch (error: LinkageError) {
                                        report("随机切换签名不匹配：${error.javaClass.simpleName}")
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            report("随机请求失败：${error.javaClass.simpleName}")
                        } catch (error: LinkageError) {
                            report("随机请求签名不匹配：${error.javaClass.simpleName}")
                        }
                    }
                }
            }
            scope.launch {
                combine(queue.flow, revision) { state, rev ->
                    val snapshot = try {
                        if (state != null && playbackMode() in setOf("Random", "Circle", "CircleEnd")) queue.snapshot(state) else null
                    } catch (error: Exception) {
                        report("随机队列读取失败：${error.javaClass.simpleName}")
                        null
                    } catch (error: LinkageError) {
                        report("随机队列签名不匹配：${error.javaClass.simpleName}")
                        null
                    }
                    Triple(snapshot, rev, playbackMode())
                }.distinctUntilChanged().collectLatest { (snapshot, requestedRevision, requestedMode) ->
                    pool = null
                    pending = null
                    history.clear()
                    boundary.reset()
                    if (snapshot == null || !active()) return@collectLatest
                    try {
                        val built = runInterruptible(Dispatchers.IO) { buildPool(snapshot, requestedRevision != appliedRevision) }
                        if (active() && playbackMode() == requestedMode && queue.snapshot() == snapshot) {
                            pool = built
                            appliedRevision = requestedRevision
                            report(if (built.timelines.isEmpty()) "当前队列无 CUE，使用原生播放" else "当前队列 ${built.entries.size} 首 · ${if (requestedMode == "Random") "按分轨随机" else "按队列顺序衔接"}")
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        report("随机候选读取失败：${error.cause?.message ?: error.message}")
                    } catch (error: LinkageError) {
                        report("随机队列签名不匹配：${error.javaClass.simpleName}")
                    }
                }
            }
            scope.launch {
                combine(host.current, host.progress) { item, progress -> item to progress }.collect { (item, progress) ->
                    try {
                        observe(item, progress)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        boundary.reset()
                        report("随机边界检测失败：${error.javaClass.simpleName}")
                    } catch (error: LinkageError) {
                        boundary.reset()
                        report("随机边界签名不匹配：${error.javaClass.simpleName}")
                    }
                }
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun playbackMode(): String? = try {
        if (closed) null else queue.mode()
    } catch (error: Exception) {
        report("播放模式读取失败：${error.javaClass.simpleName}")
        null
    } catch (error: LinkageError) {
        report("播放模式签名不匹配：${error.javaClass.simpleName}")
        null
    }
    fun randomMode(): Boolean = playbackMode() == "Random"

    fun ownsNaturalEnd(): Boolean = randomMode() ||
        (playbackMode() in setOf("Circle", "CircleEnd") && pool?.let {
            it.timelines.isNotEmpty() && it.queue.items.size > 1 && queue.snapshot() == it.queue
        } == true)

    private fun atSourceEdge(direction: Int): Boolean {
        val data = pool ?: return false
        val item = host.current.value ?: return false
        val progress = host.progress.value ?: return false
        val audio = LibraryProbe.localPath(host.path.invoke(item) as? String)
        val timeline = data.timelines[audio] ?: return false
        val duration = host.duration.invoke(progress) as Long
        val track = cueTrackAt(timeline.tracks, host.position.invoke(progress) as Long, duration) ?: return false
        return cueSourceEdge(timeline.tracks, track, duration, direction)
    }

    fun refresh() { scope.launch { if (active()) revision.value += 1 } }

    private fun buildPool(snapshot: HostCueQueue.Snapshot, forceRefresh: Boolean): Pool {
        check(active())
        val library = WorkshopApi.library.getAllTracks().toCompletableFuture().get(15, TimeUnit.SECONDS)
        val byId = library.associateBy { it.id }
        val byPath = library.mapNotNull { item -> LibraryProbe.pathOf(item)?.let { it to item } }.toMap()
        val paths = snapshot.sources.mapNotNull { it.audio }.distinct()
        if (forceRefresh) parsed.invalidateFolders(paths.mapNotNullTo(mutableSetOf()) { it.parent })
        val cues = CueDiscovery.findAll(paths, parsed, active)
        val entries = mutableListOf<CueShuffleEntry>()
        val timelines = mutableMapOf<Path, AlbumCue>()
        for (source in snapshot.sources) {
            if (!active() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val item = byId[source.id]?.takeIf { LibraryProbe.pathOf(it) == source.audio } ?: byPath[source.audio]
            val duration = item?.duration ?: 0L
            val tracks = cues[source.audio]?.sheet?.tracks.orEmpty().filter { it.audio == source.audio }
            val playable = tracks.filter { CuePlaybackWindow.from(it, duration) != null }
            if (playable.isEmpty()) entries += CueShuffleEntry(source.index, source.audio, source.id, null, duration)
            else {
                timelines[checkNotNull(source.audio)] = AlbumCue(tracks, duration)
                entries += playable.map { CueShuffleEntry(source.index, source.audio, source.id, it, duration) }
            }
            check(entries.size <= 200_000) { "随机分轨候选过多" }
        }
        return Pool(snapshot, entries.distinctBy { it.key }, timelines)
    }

    private fun observe(item: Any?, progress: Any?) {
        if (!active() || item == null || progress == null) {
            boundary.reset()
            return
        }
        val data = pool ?: return
        val audio = LibraryProbe.localPath(host.path.invoke(item) as? String)
        val timeline = data.timelines[audio]
        val position = host.position.invoke(progress) as Long
        val duration = host.duration.invoke(progress) as Long
        val actualTrack = cueTrackAt(timeline?.tracks.orEmpty(), position, duration)
        if (pending?.blocks(audio, actualTrack) == true) return
        pending = null
        if (!randomMode() || timeline == null) { boundary.reset(); return }
        val ended = boundary.observe(item, timeline.tracks, position, duration,
            PlaybackProbe.lastSeek?.observedAt, PlaybackProbe.playing, speed = queue.speed())
        if (ended != null) request(1, ended)
    }

    private fun request(direction: Int, ended: CueTrack? = null): Boolean {
        val data = pool?.takeIf { it.timelines.isNotEmpty() } ?: return false
        val mode = playbackMode()
        if (!active() || mode !in setOf("Random", "Circle", "CircleEnd") || queue.snapshot() != data.queue) return false
        val item = host.current.value ?: return false
        val audio = LibraryProbe.localPath(host.path.invoke(item) as? String)
        val progress = host.progress.value ?: return false
        val actualTrack = data.timelines[audio]?.let {
            cueTrackAt(it.tracks, host.position.invoke(progress) as Long, host.duration.invoke(progress) as Long)
        }
        if (pending?.blocks(audio, actualTrack) == true) return true
        pending = null
        val current = ended ?: actualTrack
        val currentEntry = data.entries.firstOrNull {
            it.audio == audio && (it.track == null && current == null || it.track?.matchesPlaybackTrack(current) == true)
        }
        val excluded = removed()
        val sequential = mode != "Random"
        val sourceIndex = queue.currentSourceIndex(data.queue, item) ?: return false
        if (sequential && (current == null || !cueSourceEdge(data.timelines[audio]?.tracks.orEmpty(), current,
                host.duration.invoke(progress) as Long, direction))) return false
        val previous = if (!sequential && direction < 0) history.lastOrNull {
            it.key !in excluded && data.entries.any { candidate -> candidate.key == it.key } && it.key != currentEntry?.key
        } else null
        val target = if (sequential) nextCueQueueSource(data.entries, sourceIndex, direction, mode, excluded)
            else previous ?: chooseCueShuffleEntry(data.entries, audio, current, excluded)
        if (host.current.value !== item || !active() || playbackMode() != mode) return false
        if (target == null) {
            if (sequential && mode == "CircleEnd" && direction > 0) {
                boundary.reset()
                WorkshopApi.playback.pause()
                report("已到当前队列末尾，顺序播放停止")
                return true
            }
            return false
        }
        try {
            if (!sequential && previous != null) {
                while (history.isNotEmpty()) if (history.removeLast().key == previous.key) break
            } else if (!sequential && currentEntry != null) {
                if (history.lastOrNull()?.key != currentEntry.key) history.addLast(currentEntry)
                while (history.size > 128) history.removeFirst()
            }
            boundary.reset()
            pending = CuePendingTransition(target.audio, target.track, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))
            queue.play(data.queue, target.sourceIndex, target.startMs, audio != null && audio == target.audio)
            report("${if (sequential) "顺序衔接" else "随机播放"} · ${target.track?.title ?: "普通歌曲"}")
            return true
        } catch (error: Exception) {
            pending = null
            report("随机切换失败：${error.cause?.message ?: error.message}")
            return false
        }
    }

    override fun close() {
        closed = true
        scope.cancel()
        hooks.asReversed().forEach { runCatching { it.close() } }
        hooks.clear()
        pool = null
        pending = null
        boundary.reset()
        history.clear()
    }
}
