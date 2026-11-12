package spw.cue

import androidx.compose.runtime.Composer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentComposer
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.classfile.Instruction
import java.lang.classfile.Opcode
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal const val CUE_SONG_KEY_PREFIX = "spw.cue.support:songs:"

@OptIn(UnstableSpwWorkshopApi::class)
internal class NativeSongSelection private constructor(
    val groupSetter: Method,
    private val scrollState: Method,
    private val selectionMode: Method,
    private val selectedKeys: Method,
    private val setSelectedKeys: Method,
    private val popup: Method,
    private val popupOpen: Method,
    private val closePopup: Method,
    private val listOwner: Class<*>
) {
    fun state(selector: Any?): Any? = selector?.let { scrollState.invoke(it) }
    fun selecting(state: Any): Boolean = selectionMode.invoke(state) == true
    private fun keys(state: Any): Set<*> = selectedKeys.invoke(state) as Set<*>
    fun checked(state: Any, key: String): Boolean = key in keys(state)

    fun toggle(state: Any, key: String) {
        val current = keys(state)
        setSelectedKeys.invoke(state, if (key in current) current - key else current + key)
    }

    fun removeHiddenCueKeys(selector: Any, visibleCueKeys: Set<String>) {
        val state = state(selector) ?: return
        val current = keys(state)
        val hidden = current.filter { it is String && it.startsWith(CUE_SONG_KEY_PREFIX) && it !in visibleCueKeys }
        if (hidden.isNotEmpty()) setSelectedKeys.invoke(state, current - hidden.toSet())
    }

    fun guardBatchMenu(trackId: (Any) -> String): AutoCloseable =
        WorkshopApi.hookRegistrar.replaceComposable(
            popup.declaringClass.name, popup.name, listOwner.name, popup.parameterTypes.take(3).map { it.name }, 50
        ) { call ->
            val args = call.arguments
            val state = args[0]!!
            val selected = args[1] as Set<*>
            if (selected.any { it != null && trackId(it).startsWith(CUE_SONG_KEY_PREFIX) }) {
                // The host's batch menu expects persisted file IDs and file-level playback.
                if (popupOpen.invoke(state) == true) {
                    LaunchedEffect(state) {
                        closePopup.invoke(state)
                        WorkshopApi.ui.toast("含 CUE 分轨的选择暂不支持批量操作", WorkshopApi.Ui.ToastType.Warning)
                    }
                }
            } else {
                val composer = currentComposer
                try {
                    popup.invoke(null, args[0], args[1], args[2], composer, 0, 0)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            }
        }

    companion object {
        fun resolve(nativeList: Method): NativeSongSelection? = runCatching {
            val owner = nativeList.declaringClass
            val selector = nativeList.parameterTypes[2]
            val setter = selector.methods.single {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(List::class.java)
            }
            val scroll = selector.methods.single { method ->
                method.parameterCount == 0 && method.returnType.methods.any {
                    it.returnType.name == "androidx.compose.foundation.lazy.LazyListState"
                }
            }
            val state = scroll.returnType
            val mode = state.methods.single {
                it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&
                    HostCatalog.calls(it).any { call ->
                        call.owner().asInternalName() == "androidx/compose/runtime/State" &&
                            call.name().stringValue() == "getValue"
                    }
            }
            val keys = state.methods.single { it.parameterCount == 0 && it.returnType == Set::class.java }
            val write = state.methods.single {
                it.returnType == Void.TYPE && it.parameterTypes.toList() == listOf(Set::class.java)
            }
            val popup = owner.declaredMethods.flatMap(HostCatalog::calls).filter {
                ";Ljava/util/Set;" in it.type().stringValue() &&
                    it.type().stringValue().endsWith(";Landroidx/compose/runtime/Composer;II)V")
            }.map(HostCatalog::resolve).distinct().single { method ->
                Modifier.isStatic(method.modifiers) && method.parameterCount == 6 &&
                    method.parameterTypes[1] == Set::class.java &&
                    HostCatalog.code(method).filterIsInstance<LoadConstantInstruction>().any {
                        (it.constantEntry() as? StringEntry)?.stringValue()?.contains("MultipleTrackPopupMenu (") == true
                    }
            }
            HostCatalog.requireSingleCall(owner, popup)
            val popupType = popup.parameterTypes[0]
            val open = popupType.methods.single { it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType }
            val close = popupType.methods.single { method ->
                method.parameterCount == 0 && method.returnType == Void.TYPE &&
                    HostCatalog.code(method).filterIsInstance<Instruction>().any { it.opcode() == Opcode.ICONST_0 }
            }
            NativeSongSelection(setter, scroll, mode, keys, write, popup, open, close, owner)
        }.getOrNull()
    }
}
