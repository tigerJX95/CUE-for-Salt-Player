package spw.cue

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.FieldInstruction
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

@OptIn(UnstableSpwWorkshopApi::class)
internal object HostPlaybackEnd {
    data class Binding(
        val finish: Method, val peek: Method,
        val engine: Field, val switching: Method, val peekLabel: Field
    )

    fun resolve(): Binding {
        val player = HostCatalog.type("com.xuncorp.pisces.PiscesPlayer")
        val callback = HostCatalog.owner("mixStreamCallback.onEnded")
        val finish = callback.declaredMethods.single { method ->
            method.parameterCount == 0 && method.returnType == Void.TYPE &&
                HostCatalog.calls(method).any { it.name().stringValue().startsWith("runBlocking") }
        }
        val engine = callback.declaredFields.single { !Modifier.isStatic(it.modifiers) && player.isAssignableFrom(it.type) }
            .apply { check(trySetAccessible()) }
        val switching = HostCatalog.calls(finish).filter {
            it.opcode() == Opcode.INVOKESTATIC && it.owner().asInternalName() == engine.type.name.replace('.', '/') &&
                it.type().stringValue() == "(${engine.type.descriptorString()})Ljava/lang/Object;"
        }.map(HostCatalog::resolve).single().apply { check(trySetAccessible()) }

        val peekContract = player.declaredMethods.map { it.returnType }.distinct().single { type ->
            type.isInterface && type.declaredMethods.any {
                it.name == "onPeekNext" && it.parameterTypes.contentEquals(arrayOf(Continuation::class.java))
            }
        }
        val nativePeek = peekContract.getDeclaredMethod("onPeekNext", Continuation::class.java)
        val peek = HostCatalog.callersOf(nativePeek).single { it.name.startsWith("${engine.type.name}\$") }
            .getDeclaredMethod("invokeSuspend", Any::class.java)
        val label = HostCatalog.code(peek).filterIsInstance<FieldInstruction>().first {
            it.opcode() == Opcode.GETFIELD && it.owner().asInternalName() == peek.declaringClass.name.replace('.', '/') &&
                it.type().stringValue() == "I"
        }
        val peekLabel = peek.declaringClass.getDeclaredField(label.name().stringValue()).apply { check(trySetAccessible()) }
        return Binding(finish, peek, engine, switching, peekLabel)
    }

    fun install(
        active: () -> Boolean, mode: () -> String?, advance: (String?) -> Boolean,
        controlsNext: (String?) -> Boolean, report: (String) -> Unit
    ): AutoCloseable {
        val binding = resolve()
        fun register(method: Method, intercept: (MethodHookParam, String?) -> Unit) =
            WorkshopApi.hookRegistrar.hookMethod(
                method.declaringClass.name, method.name, method.parameterTypes.map { it.name }, 100
            ) {
                before { call ->
                    if (active() && !call.isOriginalSkipped) {
                        try {
                            intercept(call, mode())
                        } catch (error: Exception) {
                            report("专辑跨碟失败：${error.cause?.message ?: error.message}")
                        } catch (error: LinkageError) {
                            report("专辑跨碟签名不匹配：${error.javaClass.simpleName}")
                        }
                    }
                }
            }
        // The player's synchronous end callback runs before its coroutine looks up the next item.
        // Respect the host's guard for end notifications caused by a manual source switch.
        val finish = register(binding.finish) { call, mode ->
            val receiver = call.thisObject ?: return@register
            if (binding.switching.invoke(null, binding.engine.get(receiver)) == null) {
                interceptNaturalEnd(call) { advance(mode) }
            }
        }
        val peek = try {
            register(binding.peek) { call, mode ->
                val receiver = call.thisObject ?: return@register
                // Automix must leave managed transitions to the playback order.
                if (binding.peekLabel.getInt(receiver) == 0 && controlsNext(mode)) call.setResult(Unit)
            }
        } catch (failure: Throwable) {
            finish.close()
            throw failure
        }
        return AutoCloseable { try { peek.close() } finally { finish.close() } }
    }

    fun interceptNaturalEnd(call: MethodHookParam, advance: () -> Boolean) {
        if (!call.isOriginalSkipped && advance()) call.setResult(null)
    }
}
