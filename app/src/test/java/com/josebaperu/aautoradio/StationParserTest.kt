package com.josebaperu.aautoradio

import com.josebaperu.aautoradio.data.StationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StationParserTest {
    private val stations = StationRepository.parse(File("../stations.tsv").readText())

    @Test fun parsesEveryStationLine() {
        assertEquals(77, stations.size)
        assertEquals("Radio Paradise Main", stations.first().name)
        assertEquals("https://stream.radioparadise.com/mp3-192", stations.first().url)
        assertTrue(stations.first().defaultFavorite)
    }

    @Test fun idsAreUnique() {
        assertEquals(stations.size, stations.map { it.id }.toSet().size)
    }

    @Test fun allUrlsAreHttps() {
        assertTrue(stations.all { it.url.startsWith("https://") })
    }

    @Test fun skipsCommentsAndBlankLinesAndDedupesIds() {
        val parsed = StationRepository.parse("# c\n\nA\thttp://a\tx\t1\nA\thttp://b\n")
        assertEquals(listOf("a", "a-2"), parsed.map { it.id })
    }
}

class StationSortTest {
    private val list = StationRepository.parse("zeta\thttp://z\nÉcho\thttp://e\nalpha\thttp://a\n4 Ever\thttp://4\n")

    @Test fun descendingIsZToAIgnoringCaseAndAccents() {
        assertEquals(listOf("zeta", "Écho", "alpha", "4 Ever"), StationRepository.sorted(list, descending = true).map { it.name })
        assertEquals(listOf("4 Ever", "alpha", "Écho", "zeta"), StationRepository.sorted(list, descending = false).map { it.name })
    }

    @Test fun groupLettersStripAccentsAndBucketDigits() {
        assertEquals(listOf("Z", "E", "A", "#"), list.map { it.groupLetter })
    }
}
