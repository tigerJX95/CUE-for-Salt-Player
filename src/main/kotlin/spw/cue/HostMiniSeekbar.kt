package spw.cue

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocal
import androidx.compose.ui.Modifier
import java.lang.classfile.Instruction
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.ParameterizedType

internal class HostMiniSeekbar {
    val caller = HostCatalog.owner("MiniPlayer (MiniPlayerContent.kt:")
    private val owner = HostCatalog.owner("MiniTimeSeekbar (MiniTime.kt:")
    private val candidates = owner.declaredMethods.filter {
        JvmModifier.isPublic(it.modifiers) && JvmModifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
            it.parameterTypes.toList() == listOf(
                Modifier::class.java, Composer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
    }
    val render = candidates.single { method ->
        HostCatalog.code(method).filterIsInstance<ConstantInstruction>().any {
            it.constantValue().toString().contains("MiniTimeSeekbar (MiniTime.kt:")
        }
    }

    init {
        HostCatalog.requireSingleCall(caller, render)
        val call = caller.declaredMethods.flatMap {
            HostCatalog.code(it).filterIsInstance<Instruction>().zipWithNext()
        }.single { (_, next) ->
            next is InvokeInstruction && next.owner().asInternalName() == owner.name.replace('.', '/') &&
                next.name().stringValue() == render.name && next.type().stringValue() == render.hostDescriptor()
        }
        // Workshop skips replacement at call sites that supply a nonzero default mask.
        check(((call.first as? ConstantInstruction)?.constantValue() as Any?) == 0) { "进度条调用使用默认参数" }
    }

    private val calls = HostCatalog.calls(render)
    private val geometryGetter = calls.single {
        it.type().stringValue() == "()Landroidx/compose/runtime/ProvidableCompositionLocal;" &&
            it.owner().asInternalName().startsWith("com/xuncorp/voxzen/ui/")
    }.let(HostCatalog::resolve)
    val geometry = geometryGetter.invoke(null) as CompositionLocal<*>
    private val geometryType = (geometryGetter.genericReturnType as ParameterizedType).actualTypeArguments.single() as Class<*>

    val cornerRadius = calls.zipWithNext().filter { (first, second) ->
        first.owner().asInternalName() == geometryType.name.replace('.', '/') && first.type().stringValue() == "()F" &&
            second.owner().asInternalName() == "androidx/compose/ui/unit/Density" && second.type().stringValue() == "(F)F"
    }.map { HostCatalog.resolve(it.first) }.distinct().single()

    val padding = owner.declaredMethods.single {
        JvmModifier.isPublic(it.modifiers) && JvmModifier.isStatic(it.modifiers) &&
            it.parameterCount == 0 && it.returnType == Float::class.javaPrimitiveType
    }.invoke(null) as Float

    private val palette = calls.zipWithNext().filter { (first, second) ->
        first.type().stringValue() == "(Landroidx/compose/runtime/Composer;)L${second.owner().asInternalName()};" &&
            second.type().stringValue() == "()J"
    }.map { (first, second) -> HostCatalog.resolve(first) to HostCatalog.resolve(second) }.distinct().single()

    fun color(composer: Composer): Long = palette.second.invoke(palette.first.invoke(null, composer)) as Long
}
