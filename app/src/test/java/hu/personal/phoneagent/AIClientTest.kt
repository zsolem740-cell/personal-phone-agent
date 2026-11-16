package hu.personal.phoneagent

import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AIClientTest {
    private val budapest = ZoneId.of("Europe/Budapest")
    private val dailyBody = """{"error":{"code":429,"message":"Quota exceeded","details":[{"violations":
        [{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
    private val minuteBody = """{"error":{"code":429,"message":"Quota exceeded","details":[{"violations":
        [{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}"""

    private fun bp(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, budapest)

    @Test
    fun geminiDailyQuotaResetsAtNineInWinter() {
        val msg = AIClient.httpErrorMessage(429, dailyBody, AIClient.PROVIDER_GEMINI, bp(2026, 11, 16, 6))
        assertTrue(msg, msg.contains("napi ingyenes kerete"))
        assertTrue(msg, msg.contains("ma 9:00-kor"))
    }

    @Test
    fun geminiDailyQuotaAfterResetMeansTomorrow() {
        assertEquals("holnap 9:00", AIClient.geminiQuotaReset(bp(2026, 11, 16, 10)))
    }

    @Test
    fun geminiResetInSummer() {
        assertEquals("holnap 9:00", AIClient.geminiQuotaReset(bp(2026, 7, 1, 12)))
    }

    @Test
    fun geminiResetInDstGap() {
        // Március közepén az USA már nyári időben van, Európa még nem.
        assertEquals("holnap 8:00", AIClient.geminiQuotaReset(bp(2026, 3, 16, 12)))
    }

    @Test
    fun perMinuteLimitIsShortWait() {
        val msg = AIClient.httpErrorMessage(429, minuteBody, AIClient.PROVIDER_GEMINI, bp(2026, 11, 16, 6))
        assertTrue(msg, msg.startsWith("Túl sok kérés rövid idő alatt"))
    }

    @Test
    fun openAiQuota() {
        val body = """{"error":{"message":"x","code":"insufficient_quota"}}"""
        val msg = AIClient.httpErrorMessage(429, body, AIClient.PROVIDER_OPENAI, bp(2026, 11, 16, 6))
        assertTrue(msg, msg.contains("OpenAI-fiók egyenlege"))
    }

    @Test
    fun geminiJoinsAllTextPartsAndSkipsThoughts() {
        val json = JSONObject("""{"candidates":[{"finishReason":"STOP","content":{"parts":[
            {"text":"gondolat","thought":true},{"text":"Első. "},{"text":"Második."}]}}]}""")
        assertEquals("Első. Második.", AIClient.parseGeminiResponse(json))
    }

    @Test
    fun geminiTruncatedAnswerIsMarked() {
        val json = JSONObject("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"Félig"}]}}]}""")
        assertTrue(AIClient.parseGeminiResponse(json).endsWith("(A válasz a hosszkorlát miatt megszakadt.)"))
    }

    @Test
    fun geminiBlockedPromptIsExplained() {
        val json = JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        try {
            AIClient.parseGeminiResponse(json)
            fail("kivételt vártunk")
        } catch (e: AIClient.AiException) {
            assertTrue(e.userMessage, e.userMessage.contains("elutasította a kérést (SAFETY)"))
        }
    }

    @Test
    fun geminiBlockedAnswerIsExplained() {
        val json = JSONObject("""{"candidates":[{"finishReason":"SAFETY"}]}""")
        try {
            AIClient.parseGeminiResponse(json)
            fail("kivételt vártunk")
        } catch (e: AIClient.AiException) {
            assertTrue(e.userMessage, e.userMessage.contains("visszatartotta a választ (SAFETY)"))
        }
    }
}
