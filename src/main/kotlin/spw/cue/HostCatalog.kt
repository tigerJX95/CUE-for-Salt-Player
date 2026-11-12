package spw.cue

import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.CodeElement
import java.lang.classfile.Instruction
import java.lang.classfile.Opcode
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LoadInstruction
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.JarURLConnection
import java.nio.file.Path
import java.util.zip.ZipFile

// Inspect only the running host. The catalog holds names, not copies of host classes.
internal object HostCatalog {
    val loader: ClassLoader get() = WorkshopApi::class.java.classLoader
    val track by lazy { type("com.xuncorp.voxzen.data.entity.Track") }
    val media by lazy { type("com.xuncorp.pisces.PiscesMediaItem") }

    private val markers = listOf(
        "AlbumContentScreen.kt:", "TrackLazyColumn (TrackLazyColumn.kt:", "TrackScreen (TrackScreen.kt:",
        "toPiscesMediaItem", "AlbumCover (AlbumCover.kt:", "TrackItemContent (TrackItem.kt:",
        "MiniPlayer (MiniPlayerContent.kt:", "MiniPlayerCoverItem (MiniPlayerCoverItem.kt:",
        "MiniPlayerControl (MiniPlayerControl.kt:", "MiniTimeSeekbar (MiniTime.kt:", "MiniTimeText (MiniTime.kt:",
        "MainWindow (MainWindow.kt:", "updateMainWindowVisible"
    )
    private val index by lazy { scan() }

    private val models = object : ClassValue<ClassModel>() {
        override fun computeValue(type: Class<*>): ClassModel {
            val internal = type.name.replace('.', '/')
            val bytes = type.getResourceAsStream("/$internal.class")?.use { it.readNBytes(262_145) }
            check(bytes != null && bytes.size <= 262_144) { "无法读取宿主类 ${type.simpleName}" }
            return ClassFile.of().parse(bytes).also { check(it.thisClass().asInternalName() == internal) }
        }
    }

    fun type(name: String): Class<*> = Class.forName(name.replace('/', '.'), false, loader)

    fun candidates(marker: String): List<String> = index.getValue(marker)

    fun owner(marker: String): Class<*> =
        type(checkNotNull(candidates(marker).singleOrNull()) { "无法唯一定位宿主入口：$marker" })

    val mediaConverter: Method by lazy {
        candidates("toPiscesMediaItem").flatMap { type(it).declaredMethods.asList() }.single {
            Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) &&
                it.returnType == media && it.parameterTypes.contentEquals(arrayOf(track))
        }
    }

    val mediaLocation: Method by lazy {
        accessors(media.getConstructor(*Array(6) { String::class.java })).last()
    }

    fun stickyHeader(): Method {
        val owner = owner("TrackLazyColumn (TrackLazyColumn.kt:")
        return owner.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                code(method).filterIsInstance<LoadConstantInstruction>().any {
                    (it.constantEntry() as? StringEntry)?.stringValue()?.contains("TrackStickyHeader (TrackLazyColumn.kt:") == true
                }
        }
    }

    fun code(method: Method): List<CodeElement> = code(method.declaringClass, method.name, method.hostDescriptor())
    fun calls(method: Method): List<InvokeInstruction> = code(method).filterIsInstance<InvokeInstruction>()

    fun constructorCalls(constructor: Constructor<*>): List<InvokeInstruction> {
        val signature = MethodType.methodType(Void.TYPE, constructor.parameterTypes.toList()).descriptorString()
        return code(constructor.declaringClass, "<init>", signature).filterIsInstance<InvokeInstruction>()
    }

    fun tracedMethods(marker: String): List<Method> = candidates(marker).flatMap { name ->
        type(name).declaredMethods.filter { method ->
            code(method).filterIsInstance<LoadConstantInstruction>().any {
                (it.constantEntry() as? StringEntry)?.stringValue()?.contains(marker) == true
            }
        }
    }

    fun callOffsets(method: Method): List<Pair<Int, InvokeInstruction>> = buildList {
        var offset = 0
        for (element in code(method).filterIsInstance<Instruction>()) {
            if (element is InvokeInstruction) add(offset to element)
            offset += element.sizeInBytes()
        }
    }

    fun resolve(call: InvokeInstruction): Method {
        val owner = type(call.owner().asInternalName())
        return owner.declaredMethods.single {
            it.name == call.name().stringValue() && it.hostDescriptor() == call.type().stringValue()
        }
    }

    fun requireSingleCall(caller: Class<*>, method: Method) {
        val signature = method.hostDescriptor()
        val owner = method.declaringClass.name.replace('.', '/')
        val count = models.get(caller).methods().sumOf { candidate ->
            candidate.code().orElse(null)?.elementList()?.filterIsInstance<InvokeInstruction>()?.count {
                it.owner().asInternalName() == owner && it.name().stringValue() == method.name &&
                    it.type().stringValue() == signature
            } ?: 0
        }
        check(count == 1) { "宿主调用点缺失或不唯一（$count）" }
    }

    // Bind data-class accessors by the constructor's argument slots and the fields they write.
    fun accessors(constructor: Constructor<*>): List<Method> {
        val owner = constructor.declaringClass
        val internal = owner.name.replace('.', '/')
        val parameters = constructor.parameterTypes
        val signature = MethodType.methodType(Void.TYPE, parameters.toList()).descriptorString()
        val body = code(owner, "<init>", signature).filterIsInstance<Instruction>()
        var slot = 1
        return parameters.map { parameter ->
            val field = body.windowed(3).mapNotNull { (receiver, value, write) ->
                if (receiver is LoadInstruction && receiver.slot() == 0 && value is LoadInstruction &&
                    value.slot() == slot && write is FieldInstruction && write.opcode() == Opcode.PUTFIELD &&
                    write.owner().asInternalName() == internal && write.type().stringValue() == parameter.descriptorString()
                ) write.name().stringValue() else null
            }.single()
            slot += if (parameter == Long::class.javaPrimitiveType || parameter == Double::class.javaPrimitiveType) 2 else 1
            owner.declaredMethods.single { candidate ->
                if (!Modifier.isPublic(candidate.modifiers) || Modifier.isStatic(candidate.modifiers) ||
                    candidate.parameterCount != 0 || candidate.returnType != parameter
                ) return@single false
                val instructions = code(candidate).filterIsInstance<Instruction>()
                val read = instructions.getOrNull(1) as? FieldInstruction
                instructions.size == 3 && read?.opcode() == Opcode.GETFIELD &&
                    read.owner().asInternalName() == internal && read.name().stringValue() == field
            }
        }
    }

    private fun code(owner: Class<*>, name: String, descriptor: String): List<CodeElement> =
        models.get(owner).methods().single {
            it.methodName().stringValue() == name && it.methodType().stringValue() == descriptor
        }.code().orElse(null)?.elementList().orEmpty()

    private fun scan(): Map<String, List<String>> {
        val anchor = track
        val resource = anchor.getResource(anchor.simpleName + ".class")
        val jar = resource?.openConnection() as? JarURLConnection
        val location = jar?.jarFileURL ?: checkNotNull(anchor.protectionDomain.codeSource?.location)
        check(location.protocol == "file") { "宿主归档不是本地文件" }
        val found = markers.associateWith { mutableListOf<String>() }
        var scannedBytes = 0L
        ZipFile(Path.of(location.toURI()).toFile()).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                if (!name.endsWith(".class") || entry.size !in 1..262_144 ||
                    !(name.startsWith("androidx/compose/ui/") && name.count { it == '/' } == 3 ||
                        name.startsWith("com/xuncorp/voxzen/ui/"))
                ) continue
                scannedBytes += entry.size
                check(scannedBytes <= 128L * 1024 * 1024) { "宿主定位超过读取上限" }
                val bytes = zip.getInputStream(entry).use { it.readNBytes(entry.size.toInt() + 1) }
                check(bytes.size.toLong() == entry.size) { "宿主类大小不匹配" }
                val symbols = bytes.toString(Charsets.ISO_8859_1)
                for (marker in markers) {
                    if (marker !in symbols) continue
                    if (marker == "AlbumContentScreen.kt:" && ".Decoration (" !in symbols) continue
                    if (marker == "TrackLazyColumn (TrackLazyColumn.kt:" && "TrackLazyColumnHeaderDecorator" !in symbols) continue
                    if (marker == "toPiscesMediaItem" && "com/xuncorp/pisces/PiscesMediaItem" !in symbols) continue
                    if (marker.startsWith("AlbumCover ") && "AudioCoverImage" !in symbols) continue
                    if (marker == "updateMainWindowVisible" && "Landroidx/compose/ui/awt/ComposeWindow;" !in symbols) continue
                    found.getValue(marker) += name.removeSuffix(".class").replace('/', '.')
                }
            }
        }
        return found.mapValues { it.value.toList() }
    }
}

internal fun Method.hostDescriptor(): String = MethodType.methodType(returnType, parameterTypes.toList()).descriptorString()
