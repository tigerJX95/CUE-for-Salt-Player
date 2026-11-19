package spw.cue

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PluginPermissionDeniedException
import com.xuncorp.spw.workshop.api.hook.HookRegistrar
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryProbeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun previewsManualCueWithoutRequestingLibraryAccess() {
        val file = temp.newFile("preview.cue").apply {
            writeText("FILE preview.wav WAVE\nTRACK 01 AUDIO\nTITLE First\nINDEX 01 00:00:00")
        }.toPath()
        val host = Host(null).apply { afterRead = { error("Unexpected playback access") } }
        WorkshopApi.instance = host

        val preview = LibraryProbe.inspect(file, readLibrary = false)
        assertEquals("First", preview.sheet.tracks.single().title)
        assertEquals(emptyMap<Path, MediaItem>(), preview.matches)
        assertEquals(null, preview.current)
    }

    @Test fun keepsManualPreviewWhenLibraryPermissionIsRevokedDuringLookup() {
        val file = temp.newFile("preview.cue").apply {
            writeText("FILE preview.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        }.toPath()
        val failure = PluginPermissionDeniedException("spw.cue.support", PluginPermission.LIBRARY_READ)
        WorkshopApi.instance = Host(null).apply { afterRead = { throw CompletionException(failure) } }

        val preview = LibraryProbe.inspect(file)
        assertEquals(1, preview.sheet.tracks.size)
        assertEquals(emptyMap<Path, MediaItem>(), preview.matches)
    }

    @Test fun matchesByPathAcrossPagesWithoutRequiringTheAudioFile() {
        val fixture = temp.newFile("set.cue").apply {
            writeText("""
                FILE "audio.flac" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:08:00
                FILE "disc 2/audio.flac" WAVE
                TRACK 02 AUDIO
                INDEX 01 00:00:00
            """.trimIndent())
        }
        val folder = fixture.toPath().parent
        val first = folder.resolve("audio.flac")
        val second = folder.resolve("disc 2/audio.flac")
        val media = item(first, "001")
        val fake = Host(media, (2..256).map { item(folder.resolve("unrelated/audio.flac"), "%03d".format(it)) } +
            item(second, "257") + media)
        WorkshopApi.instance = fake

        val result = LibraryProbe.inspect(fixture.toPath())
        assertEquals(listOf(null, "256"), fake.cursors)
        assertEquals(setOf(first, second), result.matches.keys)
        assertEquals("001", result.matches.getValue(first).id)
        assertEquals("257", result.matches.getValue(second).id)
    }

    @Test fun seeksToMillisecondsAndRejectsChangesOrOutOfRangeStarts() {
        val file = temp.root.toPath().resolve("audio.flac")
        val track = CueTrack(2, "Test", null, file, 601, null, null, null)
        val fake = Host(item(file, "original"))
        WorkshopApi.instance = fake

        LibraryProbe.seek(track, "original") { true }
        assertEquals(listOf(8_013L), fake.seeks)

        fake.current = item(file.resolveSibling("other.flac"), "other")
        assertThrows(IllegalStateException::class.java) { LibraryProbe.seek(track, "original") { true } }
        fake.current = item(file, "different-id")
        assertThrows(IllegalStateException::class.java) { LibraryProbe.seek(track, "original") { true } }
        fake.current = item(file, "original").copy(duration = 8_013)
        assertThrows(IllegalStateException::class.java) { LibraryProbe.seek(track, "original") { true } }
        fake.current = item(file, "original")
        assertThrows(IllegalStateException::class.java) { LibraryProbe.seek(track, "original") { false } }
        assertEquals(listOf(8_013L), fake.seeks)
    }

    @Test fun playsAnAlbumCueAgainstTheCurrentFileEvenWhenItsQueueIdDiffers() {
        val file = temp.root.toPath().resolve("醉拳 [整轨].wav")
        val track = CueTrack(2, "Test", null, file, 601, null, null, null)
        val fake = Host(item(file, "dragged-id").copy(path = file.toUri().toASCIIString()))
        WorkshopApi.instance = fake
        val requests = mutableListOf<Pair<Long, Boolean>>()
        val playAt = { start: Long, switch: Boolean -> requests.add(start to switch); Unit }

        LibraryProbe.playAlbumTrack(track, 24_000, { true }, playAt)
        fake.current = item(file.resolveSibling("other").resolve(file.fileName), "another-album")
        LibraryProbe.playAlbumTrack(track, 24_000, { true }, playAt)
        fake.current = null
        LibraryProbe.playAlbumTrack(track, 24_000, { true }, playAt)
        assertEquals(listOf(8_013L to false, 8_013L to true, 8_013L to true), requests)
        assertEquals(emptyList<Long>(), fake.seeks)
    }

    @Test fun rejectsUnsafeAlbumSeeksAndUsesPublicSeekWhenNativePlaybackIsUnavailable() {
        val file = temp.root.toPath().resolve("album.wav")
        val track = CueTrack(2, "Test", null, file, 601, null, null, null)
        val fake = Host(item(file.resolveSibling("other.wav"), "other"))
        WorkshopApi.instance = fake
        val unexpectedPlayback = { _: Long, _: Boolean -> error("Unexpected native playback") }
        assertThrows(IllegalStateException::class.java) {
            LibraryProbe.playAlbumTrack(track, 8_013, { true }, unexpectedPlayback)
        }
        assertThrows(IllegalStateException::class.java) {
            LibraryProbe.playAlbumTrack(track, 24_000, { false }, unexpectedPlayback)
        }
        assertThrows(IllegalStateException::class.java) {
            LibraryProbe.playAlbumTrack(track, 24_000, { true }, null)
        }
        fake.current = item(file, "dragged").copy(duration = 0)
        LibraryProbe.playAlbumTrack(track, 24_000, { true }, null)
        assertEquals(listOf(8_013L), fake.seeks)
    }

    @Test fun followsTheCurrentMediaAndClearsTheListWhenItHasNoCue() {
        val first = temp.newFile("album.cue").apply {
            writeText("FILE album.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        }
        val folder = first.toPath().parent
        val fake = Host(item(folder.resolve("album.flac"), "album"))
        WorkshopApi.instance = fake
        val updates = LinkedBlockingQueue<Optional<FollowedCue>>()
        val follow = CueFollow { updates.add(Optional.ofNullable(it)) }
        try {
            follow.signal()
            val album = requireNotNull(updates.poll(4, TimeUnit.SECONDS)).orElseThrow()
            assertEquals(first.toPath(), album.file)
            assertEquals("album", album.result.matches.values.single().id)
            assertEquals(emptyList<String?>(), fake.cursors)

            fake.current = item(folder.resolve("single.flac"), "single")
            follow.signal()
            assertEquals(Optional.empty<FollowedCue>(), updates.poll(4, TimeUnit.SECONDS))
        } finally {
            SwingUtilities.invokeAndWait { follow.close() }
        }
    }

    @Test fun neverPublishesAResultAfterThePlayerHasAlreadySwitchedMedia() {
        val folder = temp.root.toPath()
        for (name in listOf("first", "second")) {
            temp.newFile("$name.cue").writeText("FILE $name.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00")
        }
        val first = item(folder.resolve("first.flac"), "first")
        val second = item(folder.resolve("second.flac"), "second")
        val fake = Host(first)
        fake.afterRead = { fake.current = second }
        WorkshopApi.instance = fake
        val updates = LinkedBlockingQueue<FollowedCue>()
        val follow = CueFollow { if (it != null) updates.add(it) }
        try {
            follow.signal()
            val album = requireNotNull(updates.poll(4, TimeUnit.SECONDS))
            assertEquals("second", album.result.current?.id)
        } finally {
            SwingUtilities.invokeAndWait { follow.close() }
        }
    }

    @Test fun resolvesAnAlbumCueByIdWhileAnotherTrackIsPlaying() {
        val cue = temp.newFile("专辑.cue").apply {
            writeText("""
                FILE "专辑.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                TRACK 02 AUDIO
                INDEX 01 00:08:00
            """.trimIndent())
        }
        val folder = cue.toPath().parent
        val audio = folder.resolve("专辑.wav")
        val fake = Host(item(folder.resolve("other.wav"), "other"), listOf(item(audio, "album-id")))
        WorkshopApi.instance = fake

        val tracks = LibraryProbe.findAlbumCue("album-id").tracks
        assertEquals(listOf("album-id"), fake.queriedIds)
        assertEquals(listOf(0L, 8_000L), tracks.map { it.startMs })
        assertEquals(listOf(audio, audio), tracks.map { it.audio })
        assertEquals(emptyList<Long>(), fake.seeks)
    }

    @Test fun reportsMissingOrNonLocalAlbumItems() {
        val item = item(temp.root.toPath().resolve("irrelevant.wav"), "remote")
            .copy(path = "https://example.invalid/album.wav")
        WorkshopApi.instance = Host(null, listOf(item))
        val missing = assertThrows(IllegalStateException::class.java) { LibraryProbe.findAlbumCue("absent") }
        val remote = assertThrows(IllegalStateException::class.java) { LibraryProbe.findAlbumCue("remote") }
        assertEquals("公开曲库未找到对应整轨", missing.message)
        assertEquals("公开曲库未返回可用的本地文件路径", remote.message)
    }

    @Test fun resolvesSeparateDiscFoldersInHostOrderAndSkipsUnmatchedSources() {
        val root = temp.root.toPath()
        val disc1 = temp.newFolder("CD1").toPath()
        val disc2 = temp.newFolder("CD2").toPath()
        for (disc in listOf(disc1, disc2)) {
            disc.resolve("image.cue").toFile().writeText(
                "FILE image.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00"
            )
        }
        val first = item(disc1.resolve("image.wav"), "1").copy(duration = 31_000)
        val second = item(disc2.resolve("image.wav"), "2").copy(duration = 44_000)
        val bonus = item(root.resolve("bonus.wav"), "bonus")
        val fake = Host(first, listOf(first, second, bonus))
        WorkshopApi.instance = fake

        val cues = LibraryProbe.findAlbumCueSources(listOf("2", "absent", "1", "bonus")) { true }

        assertEquals(listOf("2", "1"), cues.map { it.trackId })
        assertEquals(listOf(disc2.resolve("image.wav"), disc1.resolve("image.wav")), cues.map { it.audio })
        assertEquals(listOf("00:44", "00:31"), cues.map { it.cue.trackDurationLabels.single() })
        assertEquals(listOf("2", "absent", "1", "bonus"), fake.queriedIds)
    }

    @Test fun currentAlbumRecoveryIncludesBothDiscsAndKeepsManualOpenLocalToItsCue() {
        fun disc(folder: String, title: String, id: String): MediaItem {
            val root = temp.newFolder(folder).toPath()
            root.resolve("image.cue").toFile().writeText("""
                FILE image.wav WAVE
                TRACK 01 AUDIO
                TITLE "$title First"
                INDEX 01 00:00:00
                TRACK 02 AUDIO
                TITLE "$title Second"
                INDEX 01 00:10:00
            """.trimIndent())
            return item(root.resolve("image.wav"), id).copy(album = "Collection", albumArtist = "The Band")
        }

        val first = disc("CD1", "CD1", "001").copy(artist = "Guest 1")
        val second = disc("CD2", "CD2", "257").copy(artist = "Guest 2")
        val unrelated = disc("Different", "Unrelated", "258").copy(albumArtist = "Another Band")
        val filler = (2..256).map { item(temp.root.toPath().resolve("other.wav"), "%03d".format(it)) }
        val fake = Host(first, filler + listOf(first, second, unrelated))
        WorkshopApi.instance = fake
        val audio = Path.of(first.path)
        val found = requireNotNull(LibraryProbe.inspectPlayingAlbum(first, audio) { true })

        assertEquals(null, found.file)
        assertEquals(listOf(null, "256"), fake.cursors)
        val (sheet, matches, current) = found.result
        assertEquals(listOf("CD1 First", "CD1 Second", "CD2 First", "CD2 Second"), sheet.tracks.map { it.title })
        assertEquals(setOf(first, second), matches.values.toSet())
        assertEquals(first, current)
        assertEquals(listOf(true, true, false, false), sheet.tracks.map { LibraryProbe.canSeek(it, current) })

        val keys = sheet.tracks.map(SongVisibility::key)
        val store = SongVisibility(temp.root.toPath().resolve("visibility.txt"))
        store.hide(setOf(keys[0], keys[2]))
        store.remove(setOf(keys[1], keys[3]))
        val hidden = filterManagedCueTracks(sheet.tracks, keys, "", CueTrackStatusFilter.Hidden, store.recovery.value)
        val removed = filterManagedCueTracks(sheet.tracks, keys, "", CueTrackStatusFilter.Removed, store.recovery.value)
        assertEquals(listOf(0, 2), hidden.map { it.index })
        assertEquals(listOf(1, 3), removed.map { it.index })
        store.restore(setOf(keys[2], keys[3]))
        assertEquals(SongVisibilityRecords(setOf(keys[0]), setOf(keys[1])), store.recovery.value)

        val manuallyOpened = LibraryProbe.inspect(audio.resolveSibling("image.cue"))
        assertEquals(listOf("CD1 First", "CD1 Second"), manuallyOpened.sheet.tracks.map { it.title })
    }

    @Test fun mapsMultiFileCueTracksToTheirOwnSourceAndPlaybackOffset() {
        val cue = temp.newFile("set.cue")
        cue.writeText("""
            FILE disc1.wav WAVE
            TRACK 01 AUDIO
            INDEX 01 00:00:00
            FILE disc2.wav WAVE
            TRACK 02 AUDIO
            INDEX 01 00:03:00
        """.trimIndent())
        val root = cue.toPath().parent
        val first = item(root.resolve("disc1.wav"), "1")
        val second = item(root.resolve("disc2.wav"), "2")
        WorkshopApi.instance = Host(first, listOf(first, second))

        val cues = LibraryProbe.findAlbumCueSources(listOf("1", "2")) { true }

        assertEquals(listOf(1, 2), cues.map { it.cue.tracks.single().number })
        assertEquals(listOf(0L, 3_000L), cues.map { it.cue.tracks.single().startMs })
        val (_, _, album) = cues.last()
        val requests = mutableListOf<Pair<Long, Boolean>>()
        LibraryProbe.playAlbumTrack(album.tracks.single(), album.durationMs, { true }) { position, switch ->
            requests.add(position to switch)
        }
        assertEquals(listOf(3_000L to true), requests)
    }

    private fun item(path: Path, id: String) =
        MediaItem("Test", "", "", "", path.toString(), id = id, duration = 24_000)

    private class Host(@Volatile var current: MediaItem?, private val songs: List<MediaItem> = emptyList()) : WorkshopApi {
        var afterRead: (() -> Unit)? = null
        val seeks = mutableListOf<Long>()
        val cursors = mutableListOf<String?>()
        val queriedIds = mutableListOf<String>()
        override val ui: WorkshopApi.Ui get() = error("Unexpected UI access")
        override val manager: WorkshopApi.Manager get() = error("Unexpected manager access")
        @OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
        override val hookRegistrar: HookRegistrar get() = error("Unexpected hook access")

        override val playback = object : WorkshopApi.Playback {
            override fun getCurrentMediaItem() = current.also { afterRead?.invoke() }
            override fun seekTo(position: Long) { seeks.add(position) }
            override fun changeExclusive(exclusive: Boolean) = error("Unexpected audio change")
            override fun pause() = error("Unexpected pause")
            override fun play() = error("Unexpected play")
            override fun previous() = error("Unexpected previous")
            override fun next() = error("Unexpected next")
        }

        override val library = object : WorkshopApi.Library {
            override fun getTracks(afterId: String?, limit: Int): CompletableFuture<List<MediaItem>> {
                cursors.add(afterId)
                return CompletableFuture.completedFuture(songs.filter { afterId == null || it.id > afterId }.sortedBy { it.id }.take(limit))
            }

            override fun getAllTracks() = error("Unexpected full library query")
            override fun getCoverById(id: String) = error("Unexpected artwork query")
            override fun getTrackById(id: String): CompletableFuture<MediaItem?> {
                queriedIds.add(id)
                return CompletableFuture.completedFuture(songs.singleOrNull { it.id == id })
            }
        }
    }
}
