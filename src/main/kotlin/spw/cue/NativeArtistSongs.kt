package spw.cue

import androidx.compose.runtime.snapshotFlow
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType

internal data class ArtistSongProjection(val groups: List<*>, val count: Int)

internal class ArtistSongTarget(
    val stateGetter: Method,
    private val success: Constructor<*>,
    private val accessors: List<Method>
) {
    fun groups(state: Any?): List<*>? =
        if (success.declaringClass.isInstance(state)) accessors[1].invoke(state) as List<*> else null

    fun project(state: Any, songs: ArtistSongProjection): Any {
        val args = accessors.map { it.invoke(state) }.toTypedArray()
        args[1] = songs.groups
        args[3] = songs.count
        return success.newInstance(*args)
    }

    companion object {
        fun resolve(render: Method): ArtistSongTarget {
            val screen = HostCatalog.tracedMethods("ArtistContentScreen (ArtistContentScreen.kt:").single()
            val owners = screen.parameterTypes.map { it.name.replace('.', '/') }.toSet()
            val getter = HostCatalog.calls(screen).filter {
                it.owner().asInternalName() in owners &&
                    it.type().stringValue() == "()Lkotlinx/coroutines/flow/StateFlow;"
            }.map(HostCatalog::resolve).distinct().single()

            val page = HostCatalog.tracedMethods("TrackPage (ArtistContentScreen.kt:").single {
                HostCatalog.calls(it).any { call ->
                    call.owner().asInternalName() == render.declaringClass.name.replace('.', '/') &&
                        call.name().stringValue() == render.name && call.type().stringValue() == render.hostDescriptor()
                }
            }
            val state = page.parameterTypes.first()
            val flowType = getter.genericReturnType as ParameterizedType
            val baseState = flowType.actualTypeArguments.single() as Class<*>
            check(flowType.rawType == StateFlow::class.java && baseState.isAssignableFrom(state)) {
                "艺术家歌曲状态类型不匹配"
            }

            val success = state.declaredConstructors.single {
                val types = it.parameterTypes
                types.size == 6 && types[1] == List::class.java &&
                    types[2] == Boolean::class.javaPrimitiveType && types[3] == Int::class.javaPrimitiveType &&
                    types[4] == List::class.java && types[5] == Boolean::class.javaPrimitiveType
            }
            check(success.trySetAccessible()) { "无法构建艺术家歌曲状态" }
            val accessors = HostCatalog.accessors(success)
            val groups = HostCatalog.calls(page).filter {
                it.owner().asInternalName() == state.name.replace('.', '/') &&
                    it.type().stringValue() == "()Ljava/util/List;"
            }.map(HostCatalog::resolve).distinct().single()
            check(accessors[1] == groups) { "艺术家歌曲分组字段不匹配" }
            return ArtistSongTarget(getter, success, accessors)
        }
    }
}

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeArtistSongs(
    private val binding: ArtistSongTarget,
    private val active: () -> Boolean,
    enabled: Flow<Boolean>,
    hideWhole: () -> Boolean,
    excluded: Flow<Set<String>>,
    revision: Flow<Long>,
    private val project: (List<*>, Boolean, Set<String>) -> ArtistSongProjection?,
    private val report: (String) -> Unit
) : AutoCloseable {
    private data class Settings(val enabled: Boolean, val hideWhole: Boolean, val excluded: Set<String>, val revision: Long)
    private data class Published(val original: Any, val projected: Any, val settings: Settings)

    private val settings = combine(enabled, snapshotFlow(hideWhole), excluded, revision, ::Settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    private var current: MutableStateFlow<Any>? = null
    private var job: Job? = null
    private var published: Published? = null

    fun install(): AutoCloseable {
        val getter = binding.stateGetter
        val hook = WorkshopApi.hookRegistrar.hookMethod(getter.declaringClass.name, getter.name, emptyList(), 50) {
            after { afterRead(it) }
        }
        return AutoCloseable {
            try { close() } finally { hook.close() }
        }
    }

    fun afterRead(call: MethodHookParam) {
        if (!active() || call.throwable != null || call.isOriginalSkipped) return
        val source = call.result as? StateFlow<*> ?: return
        try {
            val flow = source.mutableHostState()
            if (flow == null) {
                report("艺术家状态流无法更新")
                return
            }
            synchronized(lock) {
                if (closed || !active() || current === flow) return
                job?.cancel()
                restore()
                current = flow
                job = scope.launch { observeWhileVisible(flow) }
            }
        } catch (error: Exception) {
            report("艺术家状态流接入失败：${error.javaClass.simpleName}")
        }
    }

    private suspend fun observeWhileVisible(flow: MutableStateFlow<Any>) {
        // Avoid keeping the host's WhileSubscribed artist query alive after its UI leaves.
        while (currentCoroutineContext().isActive) {
            flow.subscriptionCount.first { it > 0 }
            coroutineScope {
                val subscribed = CompletableDeferred<Unit>()
                val observer = launch {
                    combine(flow.onSubscription { subscribed.complete(Unit) }, settings, ::Pair)
                        .collectLatest { (state, options) -> publish(flow, state, options) }
                }
                try {
                    subscribed.await()
                    flow.subscriptionCount.first { it <= 1 }
                } finally {
                    observer.cancelAndJoin()
                }
            }
            synchronized(lock) { if (current === flow) restore() }
        }
    }

    private suspend fun publish(flow: MutableStateFlow<Any>, state: Any, options: Settings) {
        val previous = synchronized(lock) {
            if (closed || !active() || current !== flow || flow.value !== state) return
            published?.takeIf { it.projected === state }
        }
        if (previous?.settings == options) return
        val original = previous?.original ?: state
        try {
            val groups = if (options.enabled) binding.groups(original)?.takeIf { it.isNotEmpty() } else null
            val songs = groups?.let {
                runInterruptible(reads) { project(it, options.hideWhole, options.excluded) }
            }
            val projected = songs?.let { binding.project(original, it) } ?: original
            synchronized(lock) {
                if (closed || !active() || current !== flow || flow.value !== state) return
                if (!flow.compareAndSet(state, projected)) return
                val actual = flow.value
                published = if (songs != null && (actual === projected || actual === previous?.projected)) {
                    report("已展开当前艺术家的 CUE 分轨（${songs.count} 首）")
                    Published(original, actual, options)
                } else null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            report("艺术家展开失败：${error.cause?.message ?: error.message ?: error.javaClass.simpleName}")
        } catch (error: LinkageError) {
            report("艺术家状态不兼容：${error.javaClass.simpleName}")
        }
    }

    // Call with lock held. Restore only the last state we own, without overwriting a host refresh.
    private fun restore() {
        val flow = current
        val previous = published
        if (flow != null && previous != null && flow.value === previous.projected) {
            flow.compareAndSet(previous.projected, previous.original)
        }
        published = null
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            scope.cancel()
            restore()
            current = null
            job = null
        }
    }

    private companion object {
        val reads = Dispatchers.IO.limitedParallelism(2)
    }
}
