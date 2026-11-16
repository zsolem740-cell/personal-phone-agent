package hu.personal.phoneagent

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarisTextTest {
    private val json = """{"updated":"2026-11-15T20:00:00Z","place":"Körmend","current":{"kp":1},
        "csi":{"t":"2026-11-15T20:00:00Z","csi":0.177},"rules":{"active":[]}}"""

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun csiHasCorrectNameAndScale() {
        val text = SolarisFeed.parse(json)!!.text
        assertTrue(text.contains("- Összevont stresszindex (CSI; nyitott skála, nem százalék; 2,0 felett kiemelt): 0,177"))
        assertFalse(text.contains("0–1"))
        assertFalse(text.contains("kockázati index"))
    }

    @Test
    fun storedTextHasNoAgeLine() {
        assertFalse(SolarisFeed.parse(json)!!.text.contains("Figyelem: az adat"))
    }

    @Test
    fun freshDataHasNoWarning() {
        val s = SolarisFeed.parse(json)!!
        assertEquals(s.text, SolarisFeed.textForAi(s, at("2026-11-15T22:59:00Z")))
    }

    @Test
    fun staleDataWarnsWithAgeAtUseTime() {
        val s = SolarisFeed.parse(json)!!
        val text = SolarisFeed.textForAi(s, at("2026-11-16T01:00:00Z"))
        assertTrue(text.endsWith("Figyelem: az adat 5 órája frissült utoljára."))
    }

    @Test
    fun dayOldDataIsNotCurrent() {
        val s = SolarisFeed.parse(json)!!
        val text = SolarisFeed.textForAi(s, at("2026-11-16T21:00:00Z"))
        assertTrue(text.contains("25 órával ezelőtt frissült, nem tekinthető aktuálisnak"))
    }

    @Test
    fun oldStoredAgeLineIsReplaced() {
        val s = SolarisFeed.parse(json)!!
        val legacy = s.copy(text = s.text + "\n\nFigyelem: az adat 0 órája frissült utoljára.")
        val text = SolarisFeed.textForAi(legacy, at("2026-11-15T20:30:00Z"))
        assertFalse(text.contains("Figyelem"))
        assertEquals(s.text, text)
    }

    @Test
    fun notifyRule() {
        assertFalse(SolarisFeed.shouldNotify(23, alertsChanged = true, notifiedToday = false))
        assertFalse(SolarisFeed.shouldNotify(6, alertsChanged = true, notifiedToday = false))
        assertTrue(SolarisFeed.shouldNotify(7, alertsChanged = false, notifiedToday = false))
        assertTrue(SolarisFeed.shouldNotify(12, alertsChanged = true, notifiedToday = true))
        assertFalse(SolarisFeed.shouldNotify(21, alertsChanged = false, notifiedToday = true))
    }
}
