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
internal class SongArtwork(private val sourceId: String, private val audio: Path) {
    private val lock = Mutex()
    private var loaded = false
    private var image: ImageBitmap? = null

    @Composable
    fun image(): ImageBitmap? = key(this) {
        produceState<ImageBitmap?>(null) { value = load() }.value
    }

    private suspend fun load(): ImageBitmap? = lock.withLock {
        if (!loaded) {
            image = runInterruptible(reads) {
                val embedded = readCover {
                    WorkshopApi.library.getCoverById(sourceId).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS)?.let { FolderCover.decodeEmbedded(it, imageSize) }
                        ?.toComposeImageBitmap()
                }
                embedded ?: readCover { FolderCover.load(audio, imageSize)?.toComposeImageBitmap() }
            }
            loaded = true
        }
        image
    }

    private inline fun readCover(block: () -> ImageBitmap?): ImageBitmap? = try {
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
