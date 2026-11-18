package spw.cue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.classfile.Opcode
import java.lang.classfile.constantpool.StringEntry
import java.lang.classfile.instruction.ConstantInstruction.LoadConstantInstruction
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

private val suppressAlbumRowAccent = ThreadLocal<Boolean>()

@OptIn(UnstableSpwWorkshopApi::class)
internal fun installAlbumRowAccentOverride(): AutoCloseable {
    val binding = checkNotNull(songThemeBinding) { "未找到原生曲目行强调色" }
    val accent = binding.accentColor
    return WorkshopApi.hookRegistrar.hookMethod(accent.declaringClass.name, accent.name, emptyList(), 50) {
        before { call ->
            if (suppressAlbumRowAccent.get() != true || call.isOriginalSkipped) return@before
            val colors = call.thisObject ?: return@before
            val neutral = runCatching { binding.titleColor.invoke(colors) as Long }.getOrNull()
            if (neutral != null) call.setResult(neutral)
        }
    }
}

internal fun withoutAlbumRowAccent(render: () -> Unit) {
    val previous = suppressAlbumRowAccent.get()
    suppressAlbumRowAccent.set(true)
    try {
        render()
    } finally {
        if (previous == null) suppressAlbumRowAccent.remove() else suppressAlbumRowAccent.set(previous)
    }
}
