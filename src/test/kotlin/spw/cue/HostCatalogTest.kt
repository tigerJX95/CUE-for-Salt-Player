package spw.cue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostCatalogTest {
    class Sample(val destination: Long, val file: String, val length: Long)

    class Ambiguous(val start: Long) {
        fun duplicate(): Long = start
    }

    @Test fun bindsAccessorsByConstructorSlotsIncludingWideValues() {
        val constructor = Sample::class.java.constructors.single()
        val item = constructor.newInstance(177L, "市集.wav", 3600L)

        assertEquals(listOf(177L, "市集.wav", 3600L), HostCatalog.accessors(constructor).map { it.invoke(item) })
    }

    @Test fun rejectsTwoAccessorsForTheSameConstructorField() {
        val constructor = Ambiguous::class.java.constructors.single()

        assertThrows(IllegalArgumentException::class.java) { HostCatalog.accessors(constructor) }
    }
}
