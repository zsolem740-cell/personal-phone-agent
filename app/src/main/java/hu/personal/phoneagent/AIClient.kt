package hu.personal.phoneagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object AIClient {
    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_GEMINI = "gemini"

    private const val PREFS = "ai_config"

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
                            "volt elérhető időben. Ezt okozhatja a hálózat korlátozása vagy átmeneti " +
                            "túlterheltség. Részletek: ${apiResult.message}"
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
            message.contains("internetkapcsolat") ||
            message.contains("megnyitni a kapcsolatot")

    private fun hasInternet(): Boolean = try {
        val connection = URL("https://www.google.com/generate_204")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
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
        val connection = try {
            URL(urlString).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw AiException("Nem sikerült megnyitni a kapcsolatot. Nincs internet?", e)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            for ((key, value) in headers) {
                connection.setRequestProperty(key, value)
            }
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = try {
                stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            } catch (e: IOException) {
                ""
            }
            if (code !in 200..299) {
                throw AiException(httpErrorMessage(code, text))
            }
            JSONObject(text)
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException(
                "Nem sikerült elérni a szervert – nincs internetkapcsolat vagy időtúllépés. (${e.javaClass.simpleName})",
                e
            )
        } finally {
            connection.disconnect()
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
