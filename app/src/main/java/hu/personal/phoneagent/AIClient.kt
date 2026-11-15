package hu.personal.phoneagent

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object AIClient {
    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_GEMINI = "gemini"

    private const val PREFS = "ai_config"

    data class Config(val provider: String, val apiKey: String, val model: String)

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
        onResult: (String?) -> Unit
    ) {
        Thread {
            val result = try {
                performRequest(config, systemPrompt, history, userText)
            } catch (_: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
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
        ) ?: return null
        val content = response.optJSONArray("content") ?: return null
        if (content.length() == 0) return null
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
        ) ?: return null
        val choices = response.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
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
        ) ?: return null
        val candidates = response.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        return candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
    }

    private fun post(urlString: String, body: String, headers: Map<String, String>): JSONObject? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
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
            if (code !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
