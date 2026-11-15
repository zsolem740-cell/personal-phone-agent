package hu.personal.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarisFeedTest {
    private fun sample(): String =
        javaClass.classLoader!!.getResource("solaris_status_sample.json")!!.readText()

    @Test
    fun parsesRealStatusFile() {
        val snapshot = SolarisFeed.parse(sample())
        assertNotNull(snapshot)
        snapshot!!
        assertEquals("2026-11-15T20:22:19.325Z", snapshot.updated)
        assertEquals("roadIce", snapshot.activeKeys)
        assertTrue(snapshot.text.contains("Készült: 2026-11-15T20:22:19.325Z"))
        assertTrue(snapshot.text.contains("Hely: Körmend"))
        assertTrue(snapshot.text.contains("Aktív figyelmeztetések: Fekete jég veszély"))
        assertTrue(snapshot.text.contains("- Légnyomás: 1017,5 hPa"))
        assertTrue(snapshot.text.contains("Mérőállomás: Vasvár"))
        assertTrue(snapshot.text.contains("Elérhető adatforrások: 15 / 15"))
    }

    @Test
    fun calmStateHasNoActiveAlerts() {
        val json = """{"updated":"2026-11-15T20:00:00Z","place":"Körmend","current":{"kp":1},"rules":{"active":[]}}"""
        val snapshot = SolarisFeed.parse(json)!!
        assertEquals("", snapshot.activeKeys)
        assertTrue(snapshot.text.contains("Aktív figyelmeztetések: nincs"))
        assertTrue(snapshot.text.contains("- nem volt jelzés"))
    }

    @Test
    fun rejectsBrokenInput() {
        assertNull(SolarisFeed.parse("nem json"))
        assertNull(SolarisFeed.parse("""{"place":"Körmend"}"""))
        assertNull(SolarisFeed.parse("""{"updated":"tegnap"}"""))
    }
}
