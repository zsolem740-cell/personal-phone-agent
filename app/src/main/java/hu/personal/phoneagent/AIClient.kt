package hu.personal.phoneagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object AIClient {
    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_GEMINI = "gemini"

    private const val PREFS = "ai_config"

    /** IPv4-címeket próbálja először: sok otthoni és mobilhálózaton az IPv6 hibás. */
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            return all.filterIsInstance<Inet4Address>() + all.filterNot { it is Inet4Address }
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class Config(val provider: String, val apiKey: String, val model: String)

    sealed class AiResult {
        data class Success(val text: String) : AiResult()
        data class Error(val message: String) : AiResult()
    }

    class AiException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

    /**
     * A választólista modelljei szolgáltatónként, az ajánlott elöl. A nevek a szolgáltatók
     * modelllistáiból származnak (2026. november); más név az „Egyéni modellnév” ponttal adható meg.
     */
    fun modelsFor(provider: String): List<String> = when (provider) {
        PROVIDER_OPENAI -> listOf("gpt-6-luna")
        PROVIDER_GEMINI -> listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3.1-flash-lite",
            "gemini-4-hydrogen"
        )
        else -> listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
    }

    fun defaultModel(provider: String): String = modelsFor(provider).first()

    fun loadConfig(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) return null
        val provider = prefs.getString("provider", PROVIDER_ANTHROPIC) ?: PROVIDER_ANTHROPIC
        val model = prefs.getString("model", "") ?: ""
        return Config(provider, key, model.ifBlank { defaultModel(provider) })
    }

    fun saveConfig(context: Context, provider: String, apiKey: String, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("provider", provider)
            .putString("api_key", apiKey)
            .putString("model", model)
            .apply()
    }

    fun request(
        config: Config,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String,
        onResult: (AiResult) -> Unit
    ) {
        Thread {
            val result = runRequest(config, systemPrompt, history, userText)
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    fun testConnection(config: Config, onResult: (AiResult) -> Unit) {
        Thread {
            val online = hasInternet()
            val result = if (!online) {
                AiResult.Error(
                    "A telefonnak nincs működő internetkapcsolata – még a google.com sem érhető el. " +
                        "Próbáld mobil adatkapcsolattal, vagy ellenőrizd a Wi-Fi hálózatot " +
                        "(nyilvános hotspotoknál gyakran először bejelentkezni kell a böngészőben)."
                )
            } else {
                val apiResult = runRequest(
                    config,
                    "Röviden válaszolj.",
                    emptyList(),
                    "Ez egy kapcsolat teszt. Válaszolj pontosan ennyit: OK"
                )
                if (apiResult is AiResult.Error && isNetworkErrorMessage(apiResult.message)) {
                    AiResult.Error(
                        "Az internetkapcsolat működik (google.com elérhető), de az AI szervere nem " +
                            "válaszolt időben. Részletek: ${apiResult.message}\n\n" +
                            probeHost(hostFor(config.provider))
                    )
                } else {
                    apiResult
                }
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun isNetworkErrorMessage(message: String): Boolean =
        message.contains("elérni a szervert") ||
            message.contains("Időtúllépés") ||
            message.contains("internetkapcsolat") ||
            message.contains("megnyitni a kapcsolatot")

    private fun hasInternet(): Boolean = try {
        val request = Request.Builder().url("https://www.google.com/generate_204").get().build()
        http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { it.code in 200..399 }
    } catch (_: Exception) {
        false
    }

    /** Megpróbál TCP-kapcsolatot nyitni a szerver minden címére, és összefoglalja az eredményt. */
    private fun probeHost(host: String): String {
        val addresses = try {
            Dns.SYSTEM.lookup(host)
        } catch (e: Exception) {
            return "A(z) $host név nem oldható fel (DNS-hiba: ${e.javaClass.simpleName})."
        }
        val lines = addresses.take(4).map { address ->
            val family = if (address is Inet4Address) "IPv4" else "IPv6"
            val started = System.currentTimeMillis()
            try {
                Socket().use { it.connect(InetSocketAddress(address, 443), 4_000) }
                "$family ${address.hostAddress}: OK (${System.currentTimeMillis() - started} ms)"
            } catch (e: Exception) {
                "$family ${address.hostAddress}: SIKERTELEN (${e.javaClass.simpleName})"
            }
        }
        return "Címek ellenőrzése ($host):\n" + lines.joinToString("\n")
    }

    private fun hostFor(provider: String): String = when (provider) {
        PROVIDER_OPENAI -> "api.openai.com"
        PROVIDER_GEMINI -> "generativelanguage.googleapis.com"
        else -> "api.anthropic.com"
    }

    private fun runRequest(
        config: Config,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String
    ): AiResult = try {
        val text = performRequest(config, systemPrompt, history, userText)
        if (text.isNullOrBlank()) {
            AiResult.Error("A szerver válaszolt, de a válasz üres volt. Próbáld újra.")
        } else {
            AiResult.Success(text)
        }
    } catch (e: AiException) {
        AiResult.Error(e.userMessage)
    } catch (e: IOException) {
        AiResult.Error("Nincs internetkapcsolat, vagy a szolgáltatás nem elérhető. (${e.javaClass.simpleName})")
    } catch (e: Exception) {
        AiResult.Error("Váratlan hiba: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun performRequest(
        config: Config,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String
    ): String? = when (config.provider) {
        PROVIDER_OPENAI -> requestOpenAi(config, systemPrompt, history, userText)
        PROVIDER_GEMINI -> requestGemini(config, systemPrompt, history, userText)
        else -> requestAnthropic(config, systemPrompt, history, userText)
    }

    private fun requestAnthropic(
        config: Config,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String
    ): String? {
        val messages = JSONArray()
        for (message in history) {
            messages.put(
                JSONObject()
                    .put("role", if (message.fromUser) "user" else "assistant")
                    .put("content", message.text)
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 1024)
            .put("system", systemPrompt)
            .put("messages", messages)
        val response = post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            ),
            PROVIDER_ANTHROPIC
        )
        val content = response.optJSONArray("content")
            ?: throw AiException("Érthetetlen válasz érkezett a szerverről.")
        if (content.length() == 0) throw AiException("A modell üres választ adott.")
        return content.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
    }

    private fun requestOpenAi(
        config: Config,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String
    ): String? {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (message in history) {
            messages.put(
                JSONObject()
                    .put("role", if (message.fromUser) "user" else "assistant")
                    .put("content", message.text)
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
        val response = post(
            "https://api.openai.com/v1/chat/completions",
            body.toString(),
            mapOf("Authorization" to "Bearer ${config.apiKey}"),
            PROVIDER_OPENAI
        )
        val choices = response.optJSONArray("choices")
            ?: throw AiException("Érthetetlen válasz érkezett a szerverről.")
        if (choices.length() == 0) throw AiException("A modell üres választ adott.")
        return choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    }

    private fun requestGemini(
        config: Config,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String
    ): String? {
        val contents = JSONArray()
        for (message in history) {
            contents.put(
                JSONObject()
                    .put("role", if (message.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", message.text)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userText)))
        )
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put("contents", contents)
        val response = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent",
            body.toString(),
            mapOf("x-goog-api-key" to config.apiKey),
            PROVIDER_GEMINI
        )
        return parseGeminiResponse(response)
    }

    /**
     * A Gemini-válasz feldolgozása: minden szövegrészt összefűz (a gondolkodási részeket
     * kihagyja), és a blokkolt, illetve a hosszkorlát miatt levágott választ külön jelzi.
     */
    internal fun parseGeminiResponse(response: JSONObject): String {
        val blockReason = response.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        if (blockReason.isNotEmpty()) {
            throw AiException("A Gemini biztonsági szűrője elutasította a kérést ($blockReason). Fogalmazd át, és próbáld újra.")
        }
        val candidates = response.optJSONArray("candidates")
            ?: throw AiException("Érthetetlen válasz érkezett a szerverről.")
        if (candidates.length() == 0) throw AiException("A modell üres választ adott.")
        val candidate = candidates.optJSONObject(0)
            ?: throw AiException("Érthetetlen válasz érkezett a szerverről.")
        val finish = candidate.optString("finishReason")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    append(part.optString("text"))
                }
            }
        }.trim()
        if (finish in GEMINI_BLOCKED) {
            throw AiException("A Gemini biztonsági szűrője visszatartotta a választ ($finish). Fogalmazd át a kérdést.")
        }
        if (finish == "MAX_TOKENS") {
            if (text.isEmpty()) throw AiException("A Gemini válasza a hosszkorlát miatt üresen szakadt meg. Kérj rövidebb választ.")
            return "$text\n\n(A válasz a hosszkorlát miatt megszakadt.)"
        }
        if (text.isEmpty()) throw AiException("A modell üres választ adott.")
        return text
    }

    private val GEMINI_BLOCKED = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")

    private val RETRYABLE_CODES = setOf(500, 502, 503, 504, 529)
    private val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)

    private fun post(
        urlString: String,
        body: String,
        headers: Map<String, String>,
        provider: String
    ): JSONObject {
        val builder = Request.Builder()
            .url(urlString)
            .post(body.toRequestBody(jsonType))
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        try {
            val request = builder.build()
            var attempt = 0
            while (true) {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (response.isSuccessful) return JSONObject(text)
                    // Túlterhelt szolgáltatás: rövid várakozás után automatikusan újrapróbáljuk.
                    if (response.code in RETRYABLE_CODES && attempt < RETRY_DELAYS_MS.size) {
                        Thread.sleep(RETRY_DELAYS_MS[attempt])
                        attempt++
                    } else {
                        throw AiException(httpErrorMessage(response.code, text, provider))
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiException("A kérés megszakadt.", e)
        } catch (e: AiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException(
                "Időtúllépés: a szerver nem válaszolt időben. (${e.message ?: "SocketTimeoutException"})",
                e
            )
        } catch (e: IOException) {
            throw AiException(
                "Nem sikerült elérni a szervert – nincs internetkapcsolat vagy a hálózat blokkolja. (${e.javaClass.simpleName})",
                e
            )
        }
    }

    internal fun httpErrorMessage(
        code: Int,
        body: String,
        provider: String,
        now: ZonedDateTime = ZonedDateTime.now()
    ): String {
        val detail = extractApiErrorMessage(body)
        val base = when (code) {
            400 -> "Hibás kérés (HTTP 400)"
            401, 403 -> "Az API-kulcs érvénytelen, vagy nincs jogosultsága (HTTP $code)"
            404 -> "A megadott modell nem található (HTTP 404). Ellenőrizd a modellnevet!"
            429 -> rateLimitMessage(body, provider, now)
            in 500..599 -> "A szolgáltatás átmenetesen nem elérhető (HTTP $code), próbáld később"
            else -> "Kapcsolódási hiba (HTTP $code)"
        }
        return if (detail.isNullOrBlank()) {
            base
        } else {
            "$base. Szerverüzenet: $detail"
        }
    }

    /** A napi keret kimerülését megkülönbözteti a rövid, percenkénti korláttól. */
    private fun rateLimitMessage(body: String, provider: String, now: ZonedDateTime): String = when {
        provider == PROVIDER_GEMINI && isDailyQuota(body) ->
            "Elfogyott a Gemini napi ingyenes kerete erre a modellre (HTTP 429). " +
                "A keret ${geminiQuotaReset(now)}-kor nullázódik. Addig válassz másik modellt, " +
                "vagy kapcsold be a fizetős keretet a Google AI Studióban."
        provider == PROVIDER_OPENAI && body.contains("insufficient_quota") ->
            "Elfogyott az OpenAI-fiók egyenlege vagy kerete (HTTP 429). Ellenőrizd a számlázást."
        else -> "Túl sok kérés rövid idő alatt (HTTP 429). Várj egy percet, majd próbáld újra."
    }

    private fun isDailyQuota(body: String): Boolean =
        body.contains("PerDay", ignoreCase = true) || body.contains("per day", ignoreCase = true)

    /**
     * A Gemini napi kerete csendes-óceáni idő szerint éjfélkor nullázódik. Az időpontot
     * a telefon időzónájában adja vissza (Budapesten általában 9:00, az óraátállítások
     * közötti néhány hétben 8:00).
     */
    internal fun geminiQuotaReset(now: ZonedDateTime): String {
        val pacific = ZoneId.of("America/Los_Angeles")
        val nextMidnight = now.withZoneSameInstant(pacific).toLocalDate().plusDays(1).atStartOfDay(pacific)
        val local = nextMidnight.withZoneSameInstant(now.zone)
        val day = if (local.toLocalDate() == now.toLocalDate()) "ma" else "holnap"
        return "$day ${local.hour}:${"%02d".format(local.minute)}"
    }

    private fun extractApiErrorMessage(body: String): String? = try {
        if (body.isBlank()) null else JSONObject(body)
            .optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        body.take(200).takeIf { it.isNotBlank() }
    }
}
