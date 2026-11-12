package spw.cue

import androidx.compose.runtime.Composer
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import java.lang.classfile.Instruction
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// The mini player shares its Text component with the rest of the app. Resolve
// only the call sites for its metadata and the expanded elapsed/duration row.
internal class HostMiniPlayerText {
    enum class Role { TITLE, ARTIST, ELAPSED, DURATION }

    private data class Site(val owner: Class<*>, val method: String, val descriptor: String, val offset: Int)

    private val mini = HostCatalog.owner("MiniPlayerCoverItem (MiniPlayerCoverItem.kt:")
    private val stack = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    private val sources = mapOf("getUiTitle" to Role.TITLE, "getUiArtist" to Role.ARTIST)
    private val labelCalls = mini.declaredMethods
        .filter { Modifier.isStatic(it.modifiers) && it.parameterTypes.firstOrNull() == HostCatalog.media }
        .flatMap { method ->
            var role: Role? = null
            HostCatalog.callOffsets(method).mapNotNull { (offset, call) ->
                if (call.owner().asInternalName() == "com/xuncorp/voxzen/util/PiscesUtilKt") {
                    role = sources[call.name().stringValue()]
                }
                val label = role
                if (label != null && call.isPlainText()) {
                    role = null
                    Triple(site(method, offset), label, HostCatalog.resolve(call))
                } else null
            }
        }
    private val nativeText = labelCalls.map { it.third }.distinct().single()
    val caller: Class<*> = nativeText.declaringClass
    private val callerDescriptor = nativeText.hostDescriptor()
    private val timeOwner = runCatching { HostCatalog.owner("MiniTimeText (MiniTime.kt:") }.getOrNull()
    private val timeRoles = runCatching { resolveTimeRoles() }.getOrDefault(emptyMap())
    val hasTimeLabels get() = timeRoles.isNotEmpty()
    private val roles = labelCalls.associate { it.first to it.second } + timeRoles
    private val visibleLabels = mutableMapOf<Role, MutableList<MiniPlayerLabelAnchor>>()
    private val titleGroups by lazy {
        labelCalls.filter { it.second == Role.TITLE }.mapNotNull { (site) ->
            restartGroupKey(site.resolveMethod())
        }.toSet()
    }
    private val artistGroups by lazy {
        labelCalls.filter { it.second == Role.ARTIST }.mapNotNull { (site) ->
            val instructions = HostCatalog.code(site.resolveMethod()).filterIsInstance<Instruction>()
            val artistRead = instructions.indexOfFirst {
                it is InvokeInstruction && it.owner().asInternalName() == "com/xuncorp/voxzen/util/PiscesUtilKt" &&
                    it.name().stringValue() == "getUiArtist"
            }
            if (artistRead < 0) return@mapNotNull null
            val start = instructions.take(artistRead).indexOfLast { it.isComposerGroup("startReplaceGroup") }
            groupKey(instructions, start)
        }.toSet()
    }

    val render = HostCatalog.calls(nativeText).filter {
        it.owner().asInternalName() == "androidx/compose/foundation/text/BasicTextKt" && it.isPlainText()
    }.map(HostCatalog::resolve).distinct().single().also { method ->
        val types = method.parameterTypes
        check(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers))
        check(types.takeLast(3) == listOf(Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        HostCatalog.requireSingleCall(caller, method)
    }

    init {
        check(Role.TITLE in roles.values) { "未找到播控条曲名调用" }
    }

    fun origin(): Role? = stack.walk { frames ->
        val iterator = frames.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.declaringClass !== caller || frame.methodName != nativeText.name ||
                frame.descriptor != callerDescriptor) continue
            val from = if (iterator.hasNext()) iterator.next() else null
            if (from == null || (from.declaringClass !== mini && from.declaringClass !== timeOwner)) return@walk null
            return@walk roles[Site(from.declaringClass, from.methodName, from.descriptor, from.byteCodeIndex)]
        }
        null
    }

    // The window can already have rendered these labels when the Workshop hook is enabled.
    @OptIn(ExperimentalComposeRuntimeApi::class)
    fun updateVisibleLabel(item: Any, role: Role, label: String): Boolean {
        val anchors = visibleLabels.getOrPut(role) { mutableListOf() }
        anchors.removeAll { !it.updateBound(label) }
        if (anchors.isNotEmpty()) return true
        val wrapper = restartGroupKey(nativeText) ?: return false
        val compositions = mutableListOf<ObservableComposition>()
        val observer = object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                compositions += composition
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) = Unit
        }
        for (info in Recomposer.runningRecomposers.value) info.observe(observer)?.dispose()
        for (composition in compositions) {
            val getter = runCatching { composition.javaClass.getMethod("getComposer\$runtime") }.getOrNull()
                ?: continue
            val composer = getter.invoke(composition) as? Composer ?: continue
            for (group in groups(composer.compositionData)) {
                if (group.key !in titleGroups || group.data.none { it === item }) continue
                val text = if (role == Role.TITLE) {
                    groups(group).firstOrNull { it.key == wrapper }
                } else {
                    groups(group).filter { it.key in artistGroups }
                        .flatMap(::groups).filter { it.key == wrapper }.singleOrNull()
                } ?: continue
                val anchor = MiniPlayerLabelAnchor()
                if (anchor.bindExisting(text) && anchor.updateBound(label)) anchors += anchor
            }
        }
        return anchors.isNotEmpty()
    }

    fun restoreVisibleLabel(role: Role) {
        visibleLabels.remove(role)?.forEach { it.restore() }
    }

    fun restoreVisibleLabels() {
        visibleLabels.values.forEach { anchors -> anchors.forEach { it.restore() } }
        visibleLabels.clear()
    }

    private fun groups(data: CompositionData): Sequence<CompositionGroup> = sequence {
        for (group in data.compositionGroups) {
            yield(group)
            yieldAll(groups(group))
        }
    }

    private fun restartGroupKey(method: Method): Int? {
        val instructions = HostCatalog.code(method).filterIsInstance<Instruction>()
        val index = instructions.indexOfFirst { it.isComposerGroup("startRestartGroup") }
        return groupKey(instructions, index)
    }

    private fun groupKey(instructions: List<Instruction>, index: Int): Int? {
        val constant: Any? = (instructions.getOrNull(index - 1) as? ConstantInstruction)?.constantValue()
        return constant as? Int
    }

    private fun Instruction.isComposerGroup(name: String): Boolean =
        this is InvokeInstruction && owner().asInternalName() == "androidx/compose/runtime/Composer" &&
            this.name().stringValue() == name

    private fun Site.resolveMethod(): Method =
        owner.declaredMethods.single { it.name == method && it.hostDescriptor() == descriptor }

    private fun resolveTimeRoles(): Map<Site, Role> {
        val method = timeOwner?.declaredMethods?.singleOrNull { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                HostCatalog.code(method).filterIsInstance<LoadConstantInstruction>().any {
                    (it.constantEntry() as? StringEntry)?.stringValue()?.contains("MiniTimeText (MiniTime.kt:") == true
                }
        } ?: return emptyMap()
        val calls = HostCatalog.callOffsets(method).filter { (_, call) ->
            call.owner().asInternalName() == caller.name.replace('.', '/') &&
                call.name().stringValue() == nativeText.name && call.type().stringValue() == callerDescriptor
        }
        val hasSeparator = HostCatalog.code(method).filterIsInstance<LoadConstantInstruction>().any {
            (it.constantEntry() as? StringEntry)?.stringValue() == "/"
        }
        // The native row renders elapsed, separator, then duration.
        if (calls.size != 3 || !hasSeparator) return emptyMap()
        return mapOf(site(method, calls.first().first) to Role.ELAPSED, site(method, calls.last().first) to Role.DURATION)
    }

    private fun site(method: Method, offset: Int) =
        Site(method.declaringClass, method.name, method.hostDescriptor(), offset)

    private fun InvokeInstruction.isPlainText(): Boolean = type().stringValue().let {
        it.startsWith("(Ljava/lang/String;Landroidx/compose/ui/Modifier;") &&
            it.contains("Landroidx/compose/runtime/Composer;") && it.endsWith(")V")
    }
}
