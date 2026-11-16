package hu.personal.phoneagent

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogShareProviderTest {
    @Test
    fun onlyLogFileNamesAreServed() {
        val name = LogShareProvider.fileName(LocalDate.of(2026, 11, 16))
        assertEquals("naplo-2026-11-16.txt", name)
        assertTrue(LogShareProvider.NAME.matches(name))
        assertFalse(LogShareProvider.NAME.matches("../shared_prefs/ai_config.xml"))
        assertFalse(LogShareProvider.NAME.matches("ai_config.xml"))
        assertFalse(LogShareProvider.NAME.matches("naplo-2026-11-16.txt/../x"))
    }
}
