package spw.cue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.internal.ComposableLambda
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.WeakHashMap

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeSongCue(
    private val target: SongHookTarget,
    private val hideWholeTrack: () -> Boolean,
    private val active: () -> Boolean,
    private val diagnostics: SongPageDiagnostics,
    private val visibility: SongVisibility?
) {
    private data class Song(
        val key: String,
        val visibilityKey: String,
        val source: AlbumHookTarget.Track,
        val track: CueTrack,
        val artist: String,
        val album: String,
        val artwork: SongArtwork,
        val fileDuration: Long,
        val trackDuration: Long
    )

    private data class FilterInput(
        val entities: List<*>,
        val original: Any,
        val sort: Any,
        val grouped: Boolean,
        val query: String
    )

    private data class Indexed(
        val sources: Map<Any, List<Any>>,
        val songs: Map<String, Song>,
        val pathCount: Int,
        val matchedSources: Int,
        val matchedTracks: Int
    )
    private sealed interface RowPlan {
        data class Original(val index: Int) : RowPlan
        data class Cue(val song: Song) : RowPlan
    }
    private data class GroupPlan(val nativeCount: Int, val rows: List<RowPlan>)
    private class SongGroups(
        private val groups: List<Any>,
        val renderingGroups: List<Any>,
        val selectableGroups: List<Any>,
        val cueKeys: Set<String>,
        val entries: List<GroupPlan>
    ) : List<Any> by groups {
        // Success uses structural equality. Searches with the same source file can show different rows.
        override fun equals(other: Any?): Boolean =
            other is SongGroups && groups == other.groups && entries == other.entries

        override fun hashCode(): Int = 31 * groups.hashCode() + entries.hashCode()
    }
    private data class ScopeCall(val method: Method, val args: Array<Any?>)

    private var indexed: WeakReference<List<*>> = WeakReference(null)
    private var indexedCues: Indexed? = null
    // Preserve rapid off/on requests even when a StateFlow collector skips the intermediate value.
    private data class Activation(val enabled: Boolean, val revision: Long = 0)
    private val mount = MutableStateFlow(Activation(false))
    private val automaticEnabled = MutableStateFlow(Activation(false))
    @Volatile private var diagnosing = false
    @Volatile private var callbacksVerified = false
    private class CallbackCheck {
        var filter = false
        var list = false
    }
    private val callbackCheck = ThreadLocal<CallbackCheck>()
    private val latestInput = MutableStateFlow<FilterInput?>(null)
    @Volatile private var observedSource = WeakReference<StateFlow<*>>(null)
    private val pageState = MutableStateFlow<MutableStateFlow<Any>?>(null)
    private data class Publication(
        val flow: MutableStateFlow<Any>, val input: FilterInput, val hideWhole: Boolean,
        val excluded: Set<String>, val value: Any
    )
    private val publishLock = Any()
    private var publication: Publication? = null
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var observedGroups = WeakReference<List<*>>(null)
    private val seekLock = Mutex()
    private val visibilityWrites = Dispatchers.IO.limitedParallelism(1)

    fun restoreHiddenSongs() {
        val store = visibility ?: return
        changeVisibility(store::restoreAll)
    }

    private fun changeVisibility(change: () -> Unit) {
        previewScope.launch(visibilityWrites) {
            if (!active()) return@launch
            try {
                change()
            } catch (error: Exception) {
                if (active()) WorkshopApi.ui.toast(error.message ?: "无法保存歌曲页隐藏记录", WorkshopApi.Ui.ToastType.Warning)
            }
        }
    }
    @Volatile private var selectionReady = false

    fun startDiagnostics(onUpdated: () -> Unit) {
        stopMount()
        diagnosing = false
        verifyCallbacks()
        diagnostics.registration = if (callbacksVerified) {
            "只读诊断已开启"
        } else {
            "只读诊断已开启 · 自检未通过 · 试挂载关闭"
        }

        latestInput.value?.let { diagnostics.pendingCachedInput(it.entities.size) }
        diagnosing = true
        synchronized(publishLock) {
            if (automaticEnabled.value.enabled && callbacksVerified) {
                diagnostics.registration = "歌曲页分轨自动显示已启用"
                mount.value = Activation(true, mount.value.revision + 1)
                onUpdated()
                return
            }
        }
        val input = latestInput.value
        if (input != null) {
            previewScope.launch {
                inspect(input, hideWholeTrack(), apply = false, cached = true)
                onUpdated()
            }
        }
    }

    private fun verifyCallbacks() {
        val sample = CallbackCheck()
        callbackCheck.set(sample)
        try {
            val filter = checkCallback("筛选") { target.probeFilter(); sample.filter }
            val list = checkCallback("列表") { target.probeList(); sample.list }
            callbacksVerified = filter.first && list.first
            diagnostics.selfCheck = "${filter.second} · ${list.second}"
        } finally {
            callbackCheck.remove()
        }
    }

    fun setAutomatic(enabled: Boolean) {
        synchronized(publishLock) {
            if (automaticEnabled.value.enabled == enabled) return
            automaticEnabled.value = Activation(enabled, automaticEnabled.value.revision + 1)
            if (enabled) {
                diagnostics.registration = "已启用歌曲页自动显示 · 等待歌曲页数据"
            } else {
                stopMount()
                diagnosing = false
                diagnostics.automaticStopped()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchAutomatic() {
        automaticEnabled.flatMapLatest { request ->
            if (!request.enabled) {
                emptyFlow()
            } else {
                combine(pageState.filterNotNull(), latestInput.filterNotNull()) { _, _ -> request }.take(1)
            }
        }.collect { request ->
            if (!active() || automaticEnabled.value !== request) return@collect
            verifyCallbacks()
            if (!callbacksVerified) {
                diagnostics.registration = "歌曲页自动显示自检未通过"
                return@collect
            }
            synchronized(publishLock) {
                if (!active() || automaticEnabled.value !== request) return@collect
                diagnosing = true
                diagnostics.registration = "歌曲页分轨自动显示已启用"
                diagnostics.awaitingMount()
                mount.value = Activation(true, mount.value.revision + 1)
            }
        }
    }

    private fun checkCallback(name: String, check: () -> Boolean): Pair<Boolean, String> = try {
        if (check()) true to "$name 已收到回调" else false to "$name 未收到回调"
    } catch (error: Exception) {
        false to "$name 自检失败（${error.cause?.message ?: error.message ?: error.javaClass.simpleName}）"
    } catch (error: LinkageError) {
        false to "$name 自检不兼容（${error.javaClass.simpleName}）"
    }

    fun enableMount() {
        check(callbacksVerified) { "筛选和列表的空输入自检都通过后才能试挂载" }
        check(diagnostics.canMount) { "请先等诊断记录到可用分轨" }
        check(observedSource.get() == null || pageState.value != null) { "宿主歌曲页状态流不兼容" }
        diagnostics.awaitingMount()
        mount.value = Activation(true, mount.value.revision + 1)
    }

    fun install(): AutoCloseable {
        val registrar = WorkshopApi.hookRegistrar
        val builder = target.listBuilder
        val list = registrar.hookMethod(builder.declaringClass.name, builder.name, builder.parameterTypes.map { it.name }, 50) {
            before { beforeList(it) }
        }
        val filter = try {
            val method = target.filter
            registrar.hookMethod(method.declaringClass.name, method.name, method.parameterTypes.map { it.name }, 50) {
                before {
                    if (active() && callbackCheck.get() == null) diagnostics.filterEntered()
                }
                after { afterFilter(it) }
            }
        } catch (error: Throwable) {
            list.close()
            throw error
        }
        val state = try {
            val method = target.stateFlow
            registrar.hookMethod(method.declaringClass.name, method.name, emptyList(), 50) {
                after { call ->
                    if (active() && !call.isOriginalSkipped && call.throwable == null) {
                        val source = call.result as? StateFlow<*>
                        if (source != null) observePageState(source)
                    }
                }
            }
        } catch (error: Throwable) {
            try { filter.close() } finally { list.close() }
            throw error
        }
        val selection = target.selection?.let { binding ->
            try {
                installSelection(binding)
            } catch (error: Exception) {
                diagnostics.failed("接入歌曲页选择", error)
                null
            } catch (error: LinkageError) {
                diagnostics.failed("接入歌曲页选择", error)
                null
            }
        }
        previewScope.launch { watchMount() }
        previewScope.launch { watchAutomatic() }
        return AutoCloseable {
            stopMount()
            previewScope.cancel()
            latestInput.value = null
            pageState.value = null
            try {
                selection?.close()
            } finally {
                try { state.close() } finally {
                    try { filter.close() } finally { list.close() }
                }
            }
        }
    }

    private fun installSelection(binding: NativeSongSelection): AutoCloseable {
        val menu = binding.guardBatchMenu(target::id)
        val nativeGroups = WeakHashMap<Any, List<Any>>()
        val setter = binding.groupSetter
        val groups = try {
            WorkshopApi.hookRegistrar.hookMethod(setter.declaringClass.name, setter.name, listOf(List::class.java.name), 50) {
                before { call ->
                    val selector = call.thisObject ?: return@before
                    val argument = call.args[0]
                    val projected = argument as? SongGroups
                    synchronized(nativeGroups) {
                        if (call.isOriginalSkipped || !selectionReady) return@synchronized
                        if (projected == null && selector !in nativeGroups) return@synchronized
                        binding.removeHiddenCueKeys(selector, projected?.cueKeys.orEmpty())
                        if (projected == null) {
                            nativeGroups.remove(selector)
                        } else {
                            try {
                                call.args[0] = projected.selectableGroups
                                call.setResult(call.invokeOriginal())
                                nativeGroups[selector] = projected.renderingGroups
                            } finally {
                                call.args[0] = argument
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            menu.close()
            throw error
        }
        selectionReady = true
        return AutoCloseable {
            synchronized(nativeGroups) {
                selectionReady = false
                try {
                    for ((selector, original) in nativeGroups) {
                        binding.removeHiddenCueKeys(selector, emptySet())
                        setter.invoke(selector, original)
                    }
                } finally {
                    try { groups.close() } finally { menu.close() }
                }
            }
        }
    }

    private fun observePageState(source: StateFlow<*>) {
        if (observedSource.get() === source) return
        try {
            val flow = target.mutablePageState(source)
            synchronized(publishLock) {
                if (!active()) return
                observedSource = WeakReference(source)
                if (pageState.value !== flow) {
                    restorePublication()
                    pageState.value = flow
                }
            }
            if (flow == null) diagnostics.stateUnavailable()
        } catch (error: Exception) {
            diagnostics.failed("连接歌曲页状态", error)
        } catch (error: LinkageError) {
            diagnostics.failed("连接歌曲页状态", error)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchMount() {
        mount.flatMapLatest { request ->
            if (!request.enabled) emptyFlow() else pageState.filterNotNull().flatMapLatest { flow ->
                combine(flow, latestInput, snapshotFlow(hideWholeTrack), visibility?.hidden ?: flowOf(emptySet())) {
                        state, input, hideWhole, excluded ->
                    if (input != null) mountCurrent(flow, state, input, hideWhole, excluded)
                }
            }
        }.collect {}
    }

    private fun mountCurrent(
        flow: MutableStateFlow<Any>, state: Any, input: FilterInput, hideWhole: Boolean, excluded: Set<String>
    ) {
        val current = synchronized(publishLock) { publication }
        val ownsState = current?.flow === flow && current.value === state
        if (ownsState && current.input === input && current.hideWhole == hideWhole && current.excluded == excluded) return
        if (state != input.original && (!ownsState || current.input.original != input.original)) return

        val projected = inspect(input, hideWhole, apply = true, cached = false, excluded) ?: return
        synchronized(publishLock) {
            if (!active() || !mount.value.enabled || pageState.value !== flow || latestInput.value !== input ||
                hideWholeTrack() != hideWhole || visibility?.hidden?.value.orEmpty() != excluded || flow.value !== state
            ) return
            if (!flow.compareAndSet(state, projected)) return
            val actual = flow.value
            if (actual === projected || (ownsState && actual === state && state == projected)) {
                publication = Publication(flow, input, hideWhole, excluded, actual)
                diagnostics.published(target.count(projected))
            }
        }
    }

    private fun stopMount() {
        synchronized(publishLock) {
            mount.value = Activation(false, mount.value.revision + 1)
            restorePublication()
        }
    }

    private fun restorePublication() {
        publication?.let { previous ->
            if (previous.flow.value === previous.value) {
                previous.flow.compareAndSet(previous.value, previous.input.original)
            }
        }
        publication = null
    }

    fun afterFilter(call: MethodHookParam) {
        if (!active()) return
        callbackCheck.get()?.let {
            it.filter = !call.isOriginalSkipped && call.throwable == null && call.result != null
            return
        }
        diagnostics.filterReturned()
        if (call.isOriginalSkipped || call.throwable != null) {
            diagnostics.filterIgnored(if (call.isOriginalSkipped) "宿主调用被提前结束" else "宿主筛选抛出异常")
            return
        }
        try {
            val entities = call.args.firstOrNull() as? List<*>
            val sort = call.args.getOrNull(1)
            val grouped = call.args.getOrNull(2) as? Boolean
            val query = call.args.getOrNull(3) as? String
            val state = call.result
            if (entities == null || sort == null || grouped == null || query == null || state == null) {
                diagnostics.filterIgnored("回调没有提供完整的曲目列表或筛选状态")
                return
            }
            val input = FilterInput(entities, state, sort, grouped, query)
            observedGroups = WeakReference(target.groupEntities(state))
            latestInput.value = input
            if (diagnosing && !mount.value.enabled) latestInput.value?.let { current ->
                previewScope.launch { inspect(current, hideWholeTrack(), apply = false, cached = false) }
            }
        } catch (error: Exception) {
            diagnostics.failed("读取宿主筛选状态", error)
        } catch (error: LinkageError) {
            diagnostics.failed("读取宿主筛选状态", error)
        }
    }

    @Synchronized
    private fun inspect(
        input: FilterInput, hide: Boolean, apply: Boolean, cached: Boolean,
        excluded: Set<String> = visibility?.hidden?.value.orEmpty()
    ): Any? {
        if (!active() || !diagnosing || latestInput.value !== input || mount.value.enabled != apply) return null
        return try {
            val entities = input.entities
            diagnostics.begin(entities.size, target.count(input.original), hide, input.query.isNotBlank(), cached, apply)
            val library = index(entities)
            diagnostics.scanned(library.pathCount, library.matchedSources, library.matchedTracks, library.songs.size)
            if (library.songs.isEmpty() || !active()) return null
            val expanded = buildList {
                for (entity in entities.filterNotNull()) {
                    val split = library.sources[entity]
                    if (!hide || split == null) add(entity)
                    split?.filterTo(this) { library.songs.getValue(target.id(it)).visibilityKey !in excluded }
                }
            }
            callbackCheck.set(CallbackCheck())
            val preview = try {
                target.filterTracks(expanded, input.sort, input.grouped, input.query)
            } finally {
                callbackCheck.remove()
            }
            val groups = remap(preview, library.songs)
            val count = target.count(preview)
            val rows = groups.entries.sumOf { it.rows.size }
            check(count == rows) { "歌曲页原生计数与列表计划不一致（$count / $rows）" }
            val cueCount = groups.entries.sumOf { group -> group.rows.count { it is RowPlan.Cue } }
            diagnostics.preview(count, cueCount)
            if (apply && mount.value.enabled && callbacksVerified && active() && latestInput.value === input) {
                target.projection(preview, groups, count)
            } else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            diagnostics.failed("筛选／挂载", error)
            null
        } catch (error: LinkageError) {
            diagnostics.failed("筛选／挂载", error)
            null
        }
    }

    private fun index(entities: List<*>): Indexed {
        if (indexed.get() === entities) indexedCues?.let { return it }
        val tracks = entities.filterNotNull()
        val paths = tracks.mapNotNull { entity -> target.audioPath(entity)?.let { entity to it } }.toMap()
        val cues = CueDiscovery.findAll(paths.values, active)
        val originalIds = tracks.mapTo(HashSet(), target::id)
        val songs = mutableMapOf<String, Song>()
        val sources = mutableMapOf<Any, List<Any>>()
        var matchedTracks = 0
        var matchedSources = 0
        for ((entity, audio) in paths) {
            if (!active() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val sheet = cues[audio]?.sheet ?: continue
            val segments = sheet.tracks.filter { it.audio == audio }
            if (segments.isEmpty()) continue
            matchedSources++
            matchedTracks += segments.size
            val id = target.id(entity)
            if (id.isBlank() || !Files.isRegularFile(audio) || !Files.isReadable(audio)) continue
            val fileDuration = target.duration(entity)
            val source = AlbumHookTarget.Track(id, entity)
            val sourceArtist = target.artist(entity).orEmpty()
            val album = target.album(entity) ?: sheet.title.orEmpty()
            val artwork = SongArtwork(id, audio)
            val split = buildList {
                for (segment in segments) {
                    val window = CuePlaybackWindow.from(segment, fileDuration) ?: continue
                    val key = "$CUE_SONG_KEY_PREFIX${id.length}:$id:${segment.number}"
                    if (key in originalIds || key in songs) continue
                    val artist = segment.performer ?: sourceArtist
                    add(target.trackForFilter(entity, key, segment, artist, window.durationMs))
                    songs[key] = Song(key, SongVisibility.key(segment), source, segment, artist, album, artwork, fileDuration, window.durationMs)
                }
            }
            if (split.isNotEmpty()) sources[entity] = split
        }
        return Indexed(sources, songs, paths.size, matchedSources, matchedTracks).also {
            indexed = WeakReference(entities)
            indexedCues = it
        }
    }

    private fun remap(state: Any, songs: Map<String, Song>): SongGroups {
        val groups = target.groupEntities(state).filterNotNull()
        val rowPlans = mutableListOf<GroupPlan>()
        val cueKeys = mutableSetOf<String>()
        val cueSources = songs.values.mapTo(HashSet()) { it.source.id }
        val nativeGroups = mutableListOf<Any>()
        val renderingGroups = groups.map { group ->
            val originals = mutableListOf<Any>()
            val rows = target.tracksIn(group).filterNotNull().map { entity ->
                val song = songs[target.id(entity)]
                if (song == null) {
                    RowPlan.Original(originals.size).also { originals.add(entity) }
                } else {
                    cueKeys.add(song.key)
                    RowPlan.Cue(song)
                }
            }
            rowPlans.add(GroupPlan(originals.size, rows))
            // The backing list must change even when the source row is kept. The host can otherwise
            // compare it equal to its previous native list and skip the CUE content callbacks.
            nativeGroups.add(target.replaceTracks(group, originals.filter { target.id(it) !in cueSources }))
            target.replaceTracks(group, originals)
        }
        return SongGroups(nativeGroups, renderingGroups, groups, cueKeys, rowPlans)
    }

    fun beforeList(call: MethodHookParam) {
        if (!active()) return
        callbackCheck.get()?.let {
            it.list = !call.isOriginalSkipped
            return
        }
        diagnostics.listEntered()
        if (!diagnosing || call.isOriginalSkipped) return
        val candidate = call.args.firstOrNull()
        val groups = candidate as? SongGroups
        if (groups == null) {
            if (candidate != null && candidate === observedGroups.get()) {
                diagnostics.observedNativeList()
            }
            return
        }
        val scope = call.args.lastOrNull() ?: return
        try {
            val entries = mutableListOf<ScopeCall>()
            val scopeType = target.listBuilder.parameterTypes.last()
            val selection = target.selection.takeIf { selectionReady }
            val selector = selection?.let { binding ->
                call.args.firstOrNull { binding.groupSetter.declaringClass.isInstance(it) }
            }
            val selectionState = selection?.state(selector)
            var capturing = true
            val proxy = Proxy.newProxyInstance(scopeType.classLoader, arrayOf(scopeType)) { _, method, args ->
                val values = args ?: emptyArray<Any?>()
                if (capturing && method.returnType == Void.TYPE) {
                    entries.add(ScopeCall(method, values))
                    null
                } else {
                    invoke(method, scope, values)
                }
            }
            val result = try {
                call.args[0] = groups.renderingGroups
                call.args[call.args.lastIndex] = proxy
                call.invokeOriginal()
            } finally {
                capturing = false
                call.args[0] = groups
                call.args[call.args.lastIndex] = scope
            }
            if (!active()) return
            var group = 0
            val render = entries.map { entry ->
                if (entry.method == target.itemBatch) {
                    val items = groups.entries.getOrNull(group++) ?: error("歌曲列表批次与分组不匹配")
                    check(entry.args.first() == items.nativeCount) { "歌曲列表曲目数量不匹配" }
                    ScopeCall(entry.method, withCueRows(entry.args, items.rows, selection, selectionState))
                } else entry
            }
            check(group == groups.entries.size) { "歌曲列表批次缺失" }
            // The host ignores the Unit returned by this list-content lambda.
            call.setResult(result)
            for (entry in render) invoke(entry.method, scope, entry.args)
            diagnostics.submitted(groups.entries.sumOf { it.rows.size })
        } catch (error: Exception) {
            diagnostics.failed("列表提交", error)
        } catch (error: LinkageError) {
            diagnostics.failed("列表提交", error)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun withCueRows(
        original: Array<Any?>, rows: List<RowPlan>, selection: NativeSongSelection?, selectionState: Any?
    ): Array<Any?> {
        if (rows.none { it is RowPlan.Cue }) return original
        val key = original[1] as (Int) -> Any?
        val type = original[2] as (Int) -> Any?
        val flag = original[3] as (Int) -> Boolean
        // Host Compose callbacks can implement several arities on the same object.
        val overlay = original[4] as ComposableLambda
        val content = original[5] as ComposableLambda
        val rowKey: (Int) -> Any? = { i ->
            when (val row = rows[i]) {
                is RowPlan.Original -> key(row.index)
                is RowPlan.Cue -> row.song.key
            }
        }
        val rowType: (Int) -> Any? = { i -> (rows[i] as? RowPlan.Original)?.let { type(it.index) } ?: "spw.cue.song" }
        val rowFlag: (Int) -> Boolean = { i -> (rows[i] as? RowPlan.Original)?.let { flag(it.index) } ?: false }
        // SelectableLazyColumn places the first callback in a matchParentSize overlay.
        val rowOverlay: @Composable (Any, Int) -> Unit = { scope, i ->
            (rows[i] as? RowPlan.Original)?.let { overlay(scope, it.index, currentComposer, 0) }
        }
        val rowContent: @Composable (Any, Int) -> Unit = { scope, i ->
            when (val row = rows[i]) {
                is RowPlan.Original -> content(scope, row.index, currentComposer, 0)
                is RowPlan.Cue -> SongRowMenu(row.song, selection, selectionState)
            }
        }
        return arrayOf(rows.size, rowKey, rowType, rowFlag, rowOverlay, rowContent)
    }

    @Composable
    private fun SongRowMenu(song: Song, selection: NativeSongSelection?, selectionState: Any?) {
        val store = visibility
        if (store == null) {
            SongRow(song, selection, selectionState)
        } else {
            SongContextMenu(onHide = { changeVisibility { store.hide(song.visibilityKey) } }) {
                SongRow(song, selection, selectionState)
            }
        }
    }

    private fun invoke(method: Method, scope: Any, args: Array<Any?>): Any? = try {
        method.invoke(scope, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    @Composable
    private fun SongRow(song: Song, selection: NativeSongSelection?, selectionState: Any?) {
        var seeking by remember(song.key) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val theme = nativeSongRowTheme()
        val gap = theme.gap.dp
        val canSelect = selection != null && selectionState != null
        val selecting = canSelect && selection.selecting(selectionState)
        val checked = canSelect && selection.checked(selectionState, song.key)
        val showSelection = selecting || checked
        val hoverSource = remember(song.key) { MutableInteractionSource() }
        val hovered by hoverSource.collectIsHoveredAsState()
        val selectionColor = theme.accentColor?.let { Color(it.toULong()) } ?: Color(0xFF42BFF5)
        val selectionStyle = if (showSelection) {
            Modifier.semantics { selected = checked }
                .background(if (checked) selectionColor.copy(alpha = 0.25f) else Color.Transparent)
        } else Modifier
        Row(
            Modifier.fillMaxWidth().onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    diagnostics.laidOutCue(song.track.number, song.track.title, size.height)
                }
            }.hoverable(hoverSource, enabled = canSelect)
                .then(selectionStyle).clickable(enabled = showSelection || !seeking) {
                if (canSelect && showSelection) {
                    selection.toggle(selectionState, song.key)
                    return@clickable
                }
                if (active() && !seeking) {
                    seeking = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val context = currentCoroutineContext()
                                seekLock.withLock {
                                    LibraryProbe.playAlbumTrack(song.track, song.fileDuration, { active() && context.isActive }) { at, change ->
                                        target.playback.playAt(song.source, at, change)
                                    }
                                }
                            }
                            diagnostics.playback = "已请求第 ${song.track.number} 轨 · ${formatCueTime(song.track.startMs)}"
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            diagnostics.failed("分轨点播", error)
                            if (active()) WorkshopApi.ui.toast(error.cause?.message ?: error.message ?: "分轨定位失败",
                                WorkshopApi.Ui.ToastType.Warning)
                        } finally {
                            seeking = false
                        }
                    }
                }
            }.padding(horizontal = gap, vertical = gap * 0.5f + 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SongCover(song.artwork)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                NativeAlbumText(song.track.title, singleLine = true, color = theme.titleColor)
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
                        NativeSongQualityBadge(song.source.entity)
                        if (song.artist.isNotBlank()) {
                            SongDetail(song.artist, theme, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.width(gap))
                    SongDetail(song.album, theme, Modifier.weight(3f))
                    Spacer(Modifier.width(gap))
                    SongDetail(formatCueTime(song.trackDuration), theme, Modifier.width(48.dp))
                }
            }
            Box(Modifier.width(gap * 2), contentAlignment = Alignment.Center) {
                if (canSelect) {
                    // The native 20 dp button is inset 22 dp from the end of the whole row.
                    val offset = gap * 2 - 32.dp
                    SelectionIndicator(
                        checked, showSelection || hovered, selectionColor, theme.detailColor,
                        Modifier.offset(x = offset)
                    ) {
                        if (active()) selection.toggle(selectionState, song.key)
                    }
                }
            }
        }
    }

    @Composable
    private fun SelectionIndicator(
        checked: Boolean, visible: Boolean, accent: Color, detailColor: Long?, modifier: Modifier, onToggle: () -> Unit
    ) {
        var focused by remember { mutableStateOf(false) }
        Canvas(
            modifier.size(16.dp).onFocusChanged { focused = it.isFocused }
                .toggleable(value = checked, role = Role.Checkbox) { onToggle() }
        ) {
            if (!visible && !focused) return@Canvas
            val stroke = 1.5.dp.toPx()
            if (checked) {
                drawCircle(accent)
                val checkColor = if (accent.red + accent.green + accent.blue > 1.5f) Color.Black else Color.White
                val bend = Offset(size.width * 0.43f, size.height * 0.64f)
                drawLine(checkColor, Offset(size.width * 0.28f, size.height * 0.50f), bend, stroke, StrokeCap.Round)
                drawLine(checkColor, bend, Offset(size.width * 0.73f, size.height * 0.35f), stroke, StrokeCap.Round)
            } else {
                val outline = detailColor?.let { Color(it.toULong()) } ?: Color.Gray
                drawCircle(outline, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            }
        }
    }

    @Composable
    private fun SongDetail(label: String, theme: SongRowTheme, modifier: Modifier = Modifier) {
        NativeAlbumText(label, modifier, singleLine = true, style = theme.detailStyle, color = theme.detailColor)
    }

    @Composable
    private fun SongCover(artwork: SongArtwork) {
        val cover by key(artwork) { produceState<ImageBitmap?>(null) { value = artwork.load() } }
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.Gray.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            val image = cover
            if (image != null) {
                Image(image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                NativeAlbumText("♪", singleLine = true)
            }
        }
    }
}

