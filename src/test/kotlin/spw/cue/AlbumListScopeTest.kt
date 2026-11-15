package spw.cue

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumListScopeTest {
    interface HostList {
        fun item(key: Any, kind: Any?, content: Any?)
        fun rows(count: Int, key: (Int) -> String, kind: Any?, select: Any?, divider: Any?, render: Any?)
    }

    @Test fun insertsEachDiscAfterItsHostRows() {
        assertEquals(
            listOf("CD1", "disc-one", "CUE:disc-one", "CD2", "disc-two", "CUE:disc-two", "footer"),
            replay(listOf("disc-one", "disc-two"))
        )
    }

    @Test fun preservesTheHostListAndUsesTheFooterIfIdsDoNotMatch() {
        assertEquals(
            listOf("CD1", "disc-one", "CD2", "disc-two", "fallback", "footer"),
            replay(listOf("disc-two", "disc-one"))
        )
    }

    @Test fun placesEachCueSectionDirectlyBelowItsDiscHeadingWhenBothOriginalsAreHidden() {
        assertEquals(
            listOf("CD1", "CUE:disc-one:hidden", "CD2", "CUE:disc-two:hidden", "footer"),
            replay(listOf("disc-one", "disc-two"), hiddenIds = setOf("disc-one", "disc-two"))
        )
    }

    @Test fun retainsAnUnmatchedDiscAndHidesOnlyTheMatchedDisc() {
        assertEquals(
            listOf("CD1", "CUE:disc-one:hidden", "CD2", "disc-two", "CUE:disc-two", "footer"),
            replay(listOf("disc-one", "disc-two"), hiddenIds = setOf("disc-one"))
        )
    }

    @Test fun retainsNativeRowsIfIdsCannotBeVerifiedEvenWhenAskedToHideThem() {
        assertEquals(
            listOf("CD1", "disc-one", "CD2", "disc-two", "fallback", "footer"),
            replay(listOf("disc-two", "disc-one"), hiddenIds = setOf("disc-one", "disc-two"))
        )
    }

    @Test fun retainsAnOrdinaryRowInTheSameGroupAndRemapsItsNativeIndex() {
        assertEquals(
            listOf("CD1", "regular", "CUE:disc-one:hidden", "CUE:regular", "CD2", "disc-two", "CUE:disc-two", "footer"),
            replay(listOf("disc-one", "regular", "disc-two"), setOf("disc-one"), mixedGroup = true)
        )
    }

    private fun replay(
        expectedIds: List<String>, hiddenIds: Set<String> = emptySet(), mixedGroup: Boolean = false
    ): List<String> {
        val events = mutableListOf<String>()
        val original = object : HostList {
            override fun item(key: Any, kind: Any?, content: Any?) {
                events.add(key.toString())
            }

            override fun rows(count: Int, key: (Int) -> String, kind: Any?, select: Any?, divider: Any?, render: Any?) {
                for (index in 0 until count) events.add(key(index))
            }
        }
        val recorder = AlbumListScope(original)
        val type = HostList::class.java
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type), recorder) as HostList
        proxy.item("CD1", null, null)
        val firstDisc = if (mixedGroup) listOf("disc-one", "regular") else listOf("disc-one")
        proxy.rows(firstDisc.size, firstDisc::get, null, null, null, null)
        proxy.item("CD2", null, null)
        proxy.rows(1, { "disc-two" }, null, null, null, null)
        proxy.item("footer", null, null)
        recorder.applyByTrack(expectedIds, hiddenIds, { id, hidden ->
            events.add(if (hidden) "CUE:$id:hidden" else "CUE:$id")
        }, { events.add("fallback") })
        return events
    }
}
