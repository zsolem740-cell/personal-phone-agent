package hu.personal.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolarisAiCacheTest {
    private val k = SolarisAiCache.key("gemini", "gemini-3.5-flash-lite", "adat")

    @Test
    fun sameDataSameModelIsHit() {
        val e = SolarisAiCache.Entry(k, 1L, "értelmezés")
        assertEquals(e, SolarisAiCache.hit(e, SolarisAiCache.key("gemini", "gemini-3.5-flash-lite", "adat")))
    }

    @Test
    fun changedDataOrModelIsMiss() {
        val e = SolarisAiCache.Entry(k, 1L, "értelmezés")
        assertNull(SolarisAiCache.hit(e, SolarisAiCache.key("gemini", "gemini-3.5-flash-lite", "új adat")))
        assertNull(SolarisAiCache.hit(e, SolarisAiCache.key("gemini", "gemini-3.8-flash", "adat")))
        assertNull(SolarisAiCache.hit(e, SolarisAiCache.key("openai", "gemini-3.5-flash-lite", "adat")))
        assertNull(SolarisAiCache.hit(null, k))
        assertNull(SolarisAiCache.hit(SolarisAiCache.Entry(k, 1L, " "), k))
    }

    @Test
    fun keyIsStableAndSeparated() {
        assertEquals(64, k.length)
        assertNotEquals(SolarisAiCache.key("a", "b\nc", "d"), SolarisAiCache.key("a", "b", "c\nd"))
    }
}
