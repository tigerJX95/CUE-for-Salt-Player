package spw.cue

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.awt.Frame
import java.lang.classfile.Opcode
import java.lang.reflect.Modifier

@OptIn(UnstableSpwWorkshopApi::class)
internal object HostTaskbarProgress {
    fun resolve() = HostCatalog.tracedMethods("Failed to update taskbar progress").single { method ->
        !Modifier.isStatic(method.modifiers) && method.hostDescriptor() == "(ZJJ)V" &&
            HostCatalog.calls(method).any { it.owner().asInternalName().startsWith("com/xuncorp/spc/windowstaskbar/") }
    }

    fun update(call: MethodHookParam, tracks: List<CueTrack>): Boolean {
        if (call.isOriginalSkipped) return false
        val position = call.args[1] as? Long ?: return false
        val duration = call.args[2] as? Long ?: return false
        val (elapsed, length) = taskbarCueTimes(tracks, position, duration) ?: return false
        if (elapsed == position && length == duration) return false
        call.args[1] = elapsed
        call.args[2] = length
        // Explicitly invoke the host; updating the hook frame alone leaves its original arguments unchanged.
        call.setResult(call.invokeOriginal())
        return true
    }
}

internal class HostTaskbarTitle {
    private val mainWindow = HostCatalog.owner("MainWindow (MainWindow.kt:")
    private val media = HostCatalog.media
    private val config = "com/xuncorp/voxzen/util/AppConfig"
    private val entry = mainWindow.declaredMethods.single { method ->
        HostCatalog.calls(method).any {
            it.owner().asInternalName() == config && it.name().stringValue() == "getTaskBarTitleShowListeningTo" &&
                it.type().stringValue() == "()Z"
        }
    }
    private val formatter = HostCatalog.calls(entry).single {
        it.opcode() == Opcode.INVOKESTATIC &&
            it.type().stringValue() == "(${media.descriptorString()})Ljava/lang/String;"
    }.let(HostCatalog::resolve)
    private val constructor = media.getConstructor(*Array(6) { String::class.java })
    private val fields = HostCatalog.accessors(constructor)
    private val composeWindow = HostCatalog.type("androidx.compose.ui.awt.ComposeWindow")
    private val frameField = HostCatalog.candidates("updateMainWindowVisible")
        .flatMap { HostCatalog.type(it).declaredFields.asList() }
        .single { it.type == composeWindow && Modifier.isStatic(it.modifiers) && Modifier.isVolatile(it.modifiers) }
        .also { check(Frame::class.java.isAssignableFrom(it.type) && it.trySetAccessible()) }
    private val configType = HostCatalog.type(config)
    private val setting = configType.getMethod("getTaskBarTitleShowListeningTo")
    private val configInstance = configType.getField("INSTANCE")

    init {
        HostCatalog.requireSingleCall(mainWindow, formatter)
        check(Modifier.isPublic(formatter.modifiers))
        val metadataReads = HostCatalog.calls(formatter).filter {
            it.owner().asInternalName() == media.name.replace('.', '/')
        }.map(HostCatalog::resolve).toSet()
        check(metadataReads == setOf(fields[1], fields[2])) { "任务栏格式化方法的歌曲字段不匹配" }
    }

    fun window(): Frame? = (frameField.get(null) as? Frame)?.takeIf { it.isDisplayable }

    fun isComposeWindow(frame: Any): Boolean = composeWindow.isInstance(frame)

    fun showsTrack(): Boolean = setting.invoke(configInstance.get(null)) as Boolean

    fun nativeTitle(item: Any): String = formatter.invoke(null, item) as String

    fun format(item: Any, track: CueTrack): String {
        val args = fields.map { it.invoke(item) }.toTypedArray()
        args[1] = track.title
        args[2] = track.performer.orEmpty()
        return nativeTitle(constructor.newInstance(*args))
    }
}
