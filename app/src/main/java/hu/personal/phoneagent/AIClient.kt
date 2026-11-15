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

    fun defaultModel(provider: String): String = when (provider) {
        PROVIDER_OPENAI -> "gpt-6-luna"
        PROVIDER_GEMINI -> "gemini-4-hydrogen"
        else -> "claude-sonnet-5-5"
    }

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
            )
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
            mapOf("Authorization" to "Bearer ${config.apiKey}")
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
            mapOf("x-goog-api-key" to config.apiKey)
        )
        val candidates = response.optJSONArray("candidates")
            ?: throw AiException("Érthetetlen válasz érkezett a szerverről.")
        if (candidates.length() == 0) throw AiException("A modell üres választ adott.")
        return candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
    }

    private fun post(urlString: String, body: String, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder()
            .url(urlString)
            .post(body.toRequestBody(jsonType))
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw AiException(httpErrorMessage(response.code, text))
                }
                return JSONObject(text)
            }
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

    private fun httpErrorMessage(code: Int, body: String): String {
        val detail = extractApiErrorMessage(body)
        val base = when (code) {
            400 -> "Hibás kérés (HTTP 400)"
            401, 403 -> "Az API-kulcs érvénytelen, vagy nincs jogosultsága (HTTP $code)"
            404 -> "A megadott modell nem található (HTTP 404). Ellenőrizd a modellnevet!"
            429 -> "Túl sok kérés – várj egy kicsit, majd próbáld újra (HTTP 429)"
            in 500..599 -> "A szolgáltatás átmenetesen nem elérhető (HTTP $code), próbáld később"
            else -> "Kapcsolódási hiba (HTTP $code)"
        }
        return if (detail.isNullOrBlank()) {
            base
        } else {
            "$base. Szerverüzenet: $detail"
        }
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
