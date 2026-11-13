package spw.cue

import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

internal object EmbeddedFlacCue {
    private const val MAX_SAMPLES = 0xF_FFFF_FFFFL
    private val cueTag = "CUESHEET=".toByteArray(Charsets.US_ASCII)

    fun find(audio: Path): LocatedCue? {
        if (!audio.fileName.toString().endsWith(".flac", ignoreCase = true)) return null
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        return try {
            if (!Files.isRegularFile(audio)) return null
            FileChannel.open(audio, READ).use { file -> read(file, audio) }?.let { LocatedCue(audio, it) }
        } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: BufferUnderflowException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun read(file: FileChannel, audio: Path): CueSheet? {
        if (file.block(4).int != 0x664C6143) return null
        val streamInfoHeader = file.block(4).int
        if ((streamInfoHeader ushr 24 and 0x7F) != 0 || (streamInfoHeader and 0xFFFFFF) != 34) return null
        val streamInfo = file.block(34).getLong(10)
        val sampleRate = streamInfo ushr 44
        val totalSamples = streamInfo and MAX_SAMPLES
        if (sampleRate == 0L || streamInfoHeader < 0) return null

        var binary: CueSheet? = null
        val fileSize = file.size()
        do {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val header = file.block(4).int
            val size = header and 0xFFFFFF
            val end = file.position() + size
            if (end > fileSize) return null
            try {
                when (header ushr 24 and 0x7F) {
                    4 -> readComment(file.block(size), audio)?.let { return it }
                    5 -> if (binary == null) {
                        binary = readCuesheet(file.block(size), audio, sampleRate, totalSamples)
                    }
                }
            } catch (_: IllegalArgumentException) {
                // A malformed metadata block does not prevent using the other CUE representation.
            } catch (_: BufferUnderflowException) {
            }
            file.position(end)
        } while (header >= 0)
        return binary
    }

    private fun readComment(block: ByteBuffer, audio: Path): CueSheet? {
        block.order(ByteOrder.LITTLE_ENDIAN)
        val vendorSize = block.int
        require(vendorSize in 0..block.remaining())
        block.position(block.position() + vendorSize)
        val comments = block.int
        require(comments in 0..(block.remaining() / Int.SIZE_BYTES))
        repeat(comments) {
            val size = block.int
            require(size in 0..block.remaining())
            val start = block.position()
            val end = start + size
            val valueSize = size - cueTag.size
            if (valueSize in 1..(1024 * 1024) && cueTag.indices.all { index ->
                    block.get(start + index).toInt().toChar().uppercaseChar() == cueTag[index].toInt().toChar()
                }) {
                block.position(start + cueTag.size)
                val value = ByteArray(valueSize).also { block.get(it) }
                try {
                    return CueParser.readEmbedded(audio, value)
                } catch (_: IllegalArgumentException) {
                    // An invalid text tag can still have a usable binary CUESHEET block.
                } catch (_: CharacterCodingException) {
                }
            }
            block.position(end)
        }
        return null
    }

    private data class Index(val number: Int, val sample: Long)
    private data class Track(val number: Int, val start: Long, val pregap: Long?, val audio: Boolean)

    private fun readCuesheet(block: ByteBuffer, audio: Path, rate: Long, totalSamples: Long): CueSheet? {
        // Catalog number (128 bytes), lead-in (8 bytes), flags, and reserved bytes.
        if (block.remaining() < 396) return null
        val cd = block.get(136).toInt() and 0x80 != 0
        block.position(395)
        val count = block.u8()
        require(count >= 2)
        val tracks = ArrayList<Track>(count - 1)
        repeat(count) { index ->
            require(block.remaining() >= 36)
            val offset = block.long
            val number = block.u8()
            val isAudio = block.get(block.position() + 12).toInt() and 0x80 == 0
            block.position(block.position() + 26)
            val indexCount = block.u8()
            require(indexCount <= block.remaining() / 12)
            require(offset in 0..(totalSamples.takeIf { it > 0 } ?: MAX_SAMPLES))
            if (index == count - 1) {
                require(number == (if (cd) 170 else 255) && indexCount == 0)
                require(offset > tracks.last().start)
                tracks.add(Track(number, offset, null, false))
            } else {
                require(number in 1 until (if (cd) 100 else 255) && number > (tracks.lastOrNull()?.number ?: 0))
                val indexes = List(indexCount) {
                    val relative = block.long
                    require(relative in 0..(MAX_SAMPLES - offset))
                    val point = Index(block.u8(), offset + relative)
                    block.position(block.position() + 3)
                    point
                }
                val start = requireNotNull(indexes.firstOrNull { it.number == 1 }?.sample)
                val pregap = indexes.firstOrNull { it.number == 0 }?.sample
                require(start > (tracks.lastOrNull()?.start ?: -1L) && (pregap == null || pregap <= start))
                require(totalSamples == 0L || start < totalSamples)
                tracks.add(Track(number, start, pregap, isAudio))
            }
        }
        require(!block.hasRemaining())

        fun units(samples: Long, unitsPerSecond: Long) =
            samples / rate * unitsPerSecond + samples % rate * unitsPerSecond / rate

        val segments = tracks.zipWithNext().mapNotNull { (track, next) ->
            if (!track.audio) return@mapNotNull null
            CueTrack(
                track.number, "TRACK %02d".format(track.number), null, audio,
                units(track.start, 75), units(next.start, 75), track.pregap?.let { units(it, 75) }, null,
                startMs = units(track.start, 1000), endMs = units(next.start, 1000)
            )
        }
        return segments.takeIf { it.isNotEmpty() }?.let { CueSheet("FLAC 内嵌 CUESHEET 块", null, it) }
    }

    private fun FileChannel.block(size: Int): ByteBuffer = ByteBuffer.allocate(size).also { buffer ->
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) throw IOException("FLAC 元数据不完整")
        }
        buffer.flip()
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
}
