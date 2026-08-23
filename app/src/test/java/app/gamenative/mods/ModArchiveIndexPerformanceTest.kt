package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class ModArchiveIndexPerformanceTest {
    @Test
    fun fiftyThousandEntries_indexWithinGenerousRegressionBudget() {
        val entries = List(50_000) { index ->
            ModArchiveEntry(
                path = "Data/Textures/Set${index / 100}/texture$index.dds",
                directory = false,
                sizeBytes = 1,
            )
        }
        lateinit var archiveIndex: ModArchiveIndex

        val elapsed = measureTimeMillis { archiveIndex = ModArchiveIndex.build(entries) }

        assertEquals(50_000, archiveIndex.files.size)
        assertEquals(100, archiveIndex.filesUnder("Data/Textures/Set42").size)
        assertTrue("Indexing took ${elapsed}ms", elapsed < 15_000)
    }
}
