package spw.cue

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.hook.MethodHookParam
import java.lang.reflect.Method
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(UnstableSpwWorkshopApi::class)
class NativeArtistSongsTest {
    @Test fun originalArtistStateFlowPublishesTracksAndRestoresTheLatestHostState() = runBlocking {
        val type = ArtistState::class.java
        val accessors = listOf("getArtist", "getGroups", "getLoading", "getCount", "getAlbums", "getFavorite")
            .map { type.getMethod(it) }
        val getter = ArtistView::class.java.getMethod("getState")
        val binding = ArtistSongTarget(getter, type.declaredConstructors.single(), accessors)
        val first = ArtistState("Artist 1", listOf("Whole"), false, 1, listOf("Album"), true)
        val filtered = first.copy(albums = listOf("Updated album"))
        val loading = Any()
        val view = ArtistView(MutableStateFlow(loading))
        val enabled = MutableStateFlow(true)
        val excluded = MutableStateFlow(emptySet<String>())
        val artist = NativeArtistSongs(binding, { true }, enabled, { true }, excluded, MutableStateFlow(0L),
            { rows, hide, hidden, _ ->
                if ("Whole" !in rows) null
                else {
                    val expanded = (if (hide) emptyList() else listOf("Whole")) +
                        listOf("Track 1", "Track 2").filterNot { it in hidden }
                    ArtistSongProjection(expanded, expanded.size)
                }
            }, {})

        val call = GetterCall(getter, view)
        artist.afterRead(call)
        assertSame(view.state, call.result)
        assertSame(loading, view.state.value)

        suspend fun expect(check: (Any) -> Boolean) = withTimeout(5_000) {
            while (!check(view.state.value)) delay(10)
        }
        val collector = launch { view.state.collect {} }
        try {
            view.native.value = first
            val expanded = first.copy(groups = listOf("Track 1", "Track 2"), count = 2)
            expect { it == expanded }

            excluded.value = setOf("Track 1")
            expect { it == first.copy(groups = listOf("Track 2")) }

            view.native.value = filtered
            expect { it == filtered.copy(groups = listOf("Track 2")) }
            enabled.value = false
            expect { it === filtered }

            enabled.value = true
            expect { it == filtered.copy(groups = listOf("Track 2")) }
            collector.cancelAndJoin()
            expect { it === filtered }

            val returning = launch { view.state.collect {} }
            try {
                expect { it == filtered.copy(groups = listOf("Track 2")) }
                artist.close()
                assertSame(filtered, view.state.value)
            } finally {
                returning.cancelAndJoin()
            }
        } finally {
            collector.cancelAndJoin()
            artist.close()
        }
        Unit
    }

    data class ArtistState(
        val artist: String, val groups: List<String>, val loading: Boolean,
        val count: Int, val albums: List<String>, val favorite: Boolean
    )

    class ArtistView(val native: MutableStateFlow<Any>) {
        val state = native.asStateFlow()
    }

    private class GetterCall(override val method: Method, private val view: ArtistView) : MethodHookParam() {
        override val thisObject = view
        override val args = emptyArray<Any?>()
        override val result get() = view.state
        override val throwable: Throwable? = null
        override val isOriginalSkipped = false
        override fun setResult(result: Any?) = error("The host does not return the replacement value")
        override fun setThrowable(throwable: Throwable) = throw throwable
        override fun invokeOriginal(): Any = error("The original getter should only run once")
    }
}
