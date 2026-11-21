package spw.cue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal val LocalCueSongArtwork = staticCompositionLocalOf<SongArtwork?> { null }

@OptIn(UnstableSpwWorkshopApi::class)
internal class SongArtwork private constructor(
    private val sourceId: String,
    val isWave: Boolean,
    private val resolveAudio: () -> Path?,
    private val verifyCue: Boolean
) {
    constructor(sourceId: String, audio: Path) :
        this(sourceId, audio.fileName.toString().isWaveAudio(), { audio }, false)

    constructor(sourceId: String, resolveWave: () -> Path?) :
        this(sourceId, true, resolveWave, true)

    data class Cover(val image: ImageBitmap?, val status: String)

    private val lock = Mutex()
    @Volatile private var loaded: Cover? = null

    @Composable
    fun image(): ImageBitmap? = cover()?.image

    @Composable
    fun cover(): Cover? = key(this) {
        produceState(loaded) { value = load() }.value
    }

    private suspend fun load(): Cover = lock.withLock {
        loaded ?: runInterruptible(reads, ::read).also { loaded = it }
    }

    private fun read(): Cover {
        val audio = readCover(resolveAudio) ?: return Cover(null, "无法解析本地音频路径")
        if (verifyCue && readCover { CueDiscovery.find(audio) } == null) return Cover(null, "没有匹配的外置 CUE")
        val embedded = readCover {
            WorkshopApi.library.getCoverById(sourceId).toCompletableFuture()
                .get(5, TimeUnit.SECONDS)?.let { FolderCover.decodeEmbedded(it, imageSize) }
                ?.toComposeImageBitmap()
        }
        if (embedded != null) return Cover(embedded, "已读取内嵌封面")
        val external = readCover { FolderCover.loadSong(audio, imageSize)?.toComposeImageBitmap() }
        return Cover(external, if (external == null) "已匹配 CUE，未找到可读取的目录封面" else "已读取目录封面")
    }

    private inline fun <T> readCover(block: () -> T?): T? = try {
        block()
    } catch (error: InterruptedException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private companion object {
        const val imageSize = 192
        val reads = Dispatchers.IO.limitedParallelism(2)
    }
}

internal fun String.isWaveAudio(): Boolean = endsWith(".wav", ignoreCase = true)
