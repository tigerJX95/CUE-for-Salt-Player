package spw.cue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import java.lang.classfile.Instruction
import java.lang.classfile.Opcode
import java.lang.classfile.TypeKind
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.LoadInstruction
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

internal data class SongRowTheme(
    val gap: Float = 16f,
    val accentColor: Long? = null,
    val titleColor: Long? = null,
    val detailColor: Long? = null,
    val detailStyle: TextStyle = TextStyle(fontSize = 13.sp)
)

private class ThemeValue(val provider: Method, val getter: Method) {
    fun read(composer: Composer): Any = getter.invoke(provider.invoke(null, composer))
}

private class SongThemeBinding(
    val gap: ThemeValue, val detailStyle: ThemeValue,
    val colors: Method, val accentColor: Method, val titleColor: Method, val detailColor: Method
)

private val songThemeBinding by lazy {
    runCatching {
        val row = HostCatalog.tracedMethods("TrackItemContent (TrackItem.kt:").single()
        val calls = HostCatalog.calls(row)
        val themeCalls = calls.zipWithNext().mapNotNull { (providerCall, getterCall) ->
            if (providerCall.opcode() != Opcode.INVOKESTATIC || getterCall.opcode() != Opcode.INVOKEVIRTUAL ||
                providerCall.owner().asInternalName() == getterCall.owner().asInternalName() ||
                providerCall.type().stringValue() !=
                    "(Landroidx/compose/runtime/Composer;)L${getterCall.owner().asInternalName()};" ||
                !getterCall.type().stringValue().startsWith("()")
            ) return@mapNotNull null
            ThemeValue(HostCatalog.resolve(providerCall), HostCatalog.resolve(getterCall))
        }
        fun values(name: String) = themeCalls.filter { value ->
            HostCatalog.code(value.provider).filterIsInstance<LoadConstantInstruction>().any {
                (it.constantEntry() as? StringEntry)?.stringValue()?.contains("SaltTheme.<get-$name>") == true
            }
        }
        val gap = values("dimens").filter { it.getter.returnType == Float::class.javaPrimitiveType }
            .distinctBy { it.getter }.single()
        val style = values("textStyles").filter { it.getter.returnType == TextStyle::class.java }
            .distinctBy { it.getter }.single()
        val colors = values("colors").filter { it.getter.returnType == Long::class.javaPrimitiveType }
        // TrackItemContent reads accent, secondary and primary text colors for the title,
        // then accent and secondary text colors for the metadata row.
        check(colors.size == 5 && colors[0].getter == colors[3].getter &&
            colors[1].getter == colors[4].getter && colors.map { it.getter }.distinct().size == 3)
        SongThemeBinding(gap, style, colors.map { it.provider }.distinct().single(),
            colors[0].getter, colors[2].getter, colors[4].getter)
    }.getOrNull()
}

private val lastHostSongRowTheme = mutableStateOf<SongRowTheme?>(null)

@Composable
internal fun probeHostTheme(): SongRowTheme? = lastHostSongRowTheme.value

@Composable
internal fun nativeSongRowTheme(): SongRowTheme {
    val binding = songThemeBinding ?: return SongRowTheme()
    val composer = currentComposer
    val colors = binding.colors.invoke(null, composer)
    val theme = SongRowTheme(
        gap = binding.gap.read(composer) as Float,
        accentColor = binding.accentColor.invoke(colors) as Long,
        titleColor = binding.titleColor.invoke(colors) as Long,
        detailColor = binding.detailColor.invoke(colors) as Long,
        detailStyle = binding.detailStyle.read(composer) as TextStyle
    )
    SideEffect { lastHostSongRowTheme.value = theme }
    return theme
}

private class RowPaletteBinding(
    val local: ProvidableCompositionLocal<Any>,
    private val pair: Constructor<*>,
    private val palettes: List<Method>,
    private val palette: Constructor<*>,
    private val shades: List<Field>,
    private val accentIndex: Int,
    private val textIndex: Int
) {
    fun shadesOf(source: Any): List<List<Long>> = palettes.map { getPalette ->
        val colors = getPalette.invoke(source)
        shades.map { field -> ((field.get(colors) as State<*>).value as Color).value.toLong() }
    }

    fun withNeutralAccent(colors: List<List<Long>>): Any {
        val replacements = colors.map { shades ->
            val args = shades.toTypedArray()
            args[accentIndex] = shades[textIndex]
            palette.newInstance(*args)
        }
        return pair.newInstance(*replacements.toTypedArray())
    }
}

private val rowPaletteBinding by lazy {
    runCatching {
        val theme = checkNotNull(songThemeBinding)
        val calls = HostCatalog.calls(theme.colors)
        val colorType = theme.colors.returnType
        val getLocal = calls.filter {
            it.opcode() == Opcode.INVOKESTATIC &&
                it.type().stringValue() == "()Landroidx/compose/runtime/ProvidableCompositionLocal;"
        }.map(HostCatalog::resolve).distinct().single()
        val pairType = calls.filter {
            it.opcode() == Opcode.INVOKEVIRTUAL && it.type().stringValue() == "()${colorType.descriptorString()}"
        }.map { HostCatalog.type(it.owner().asInternalName()) }.distinct().single()
        val pair = pairType.getConstructor(colorType, colorType)
        val palettes = HostCatalog.accessors(pair)
        val palette = colorType.declaredConstructors.single {
            it.parameterCount > 0 && it.parameterTypes.all { type -> type == Long::class.javaPrimitiveType }
        }
        check(palette.trySetAccessible())
        val owner = colorType.name.replace('.', '/')
        // The palette stores one snapshot state per packed color constructor argument.
        val names = HostCatalog.code(palette).filterIsInstance<Instruction>().windowed(6).mapNotNull { window ->
            val receiver = window[0] as? LoadInstruction ?: return@mapNotNull null
            val value = window[1] as? LoadInstruction ?: return@mapNotNull null
            val field = window.last() as? FieldInstruction ?: return@mapNotNull null
            if (receiver.slot() != 0 || value.typeKind() != TypeKind.LONG || field.opcode() != Opcode.PUTFIELD ||
                field.owner().asInternalName() != owner ||
                field.type().stringValue() != "Landroidx/compose/runtime/MutableState;") return@mapNotNull null
            value.slot() to field.name().stringValue()
        }.sortedBy { it.first }.also { fields ->
            check(fields.map { it.first } == (1 until 2 * palette.parameterCount step 2).toList())
        }.map { it.second }
        val shades = names.map { name ->
            colorType.getDeclaredField(name).also { check(it.trySetAccessible()) }
        }
        fun index(getter: Method): Int {
            val name = HostCatalog.code(getter).filterIsInstance<FieldInstruction>().single {
                it.opcode() == Opcode.GETFIELD && it.owner().asInternalName() == owner
            }.name().stringValue()
            return names.indexOf(name).also { check(it >= 0) }
        }
        @Suppress("UNCHECKED_CAST")
        val local = getLocal.invoke(null) as ProvidableCompositionLocal<Any>
        RowPaletteBinding(local, pair, palettes, palette, shades, index(theme.accentColor), index(theme.titleColor))
    }.getOrNull()
}

internal fun nativeRowPaletteAvailable(): Boolean = rowPaletteBinding != null

@Composable
internal fun NeutralNativeRowAccent(suppress: Boolean, render: @Composable () -> Unit) {
    val binding = rowPaletteBinding
    if (suppress && binding != null) {
        // A skipped primitive-return hook can leave transparent zero in the host.
        val colors = binding.shadesOf(binding.local.current)
        val palette = remember(binding, colors) { binding.withNeutralAccent(colors) }
        CompositionLocalProvider(binding.local provides palette, content = render)
    } else {
        render()
    }
}
