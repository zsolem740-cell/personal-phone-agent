package hu.personal.phoneagent

import android.content.Context
import java.security.MessageDigest

/**
 * A legutóbbi Solaris-AI-értelmezés helyi tárolója, hogy ugyanarra az adatra ne fogyjon
 * újra a keret. A Solaris óránként frissül, ezért óránként legfeljebb egy AI-kérés megy el.
 * Csak a sikeres választ tárolja; más szolgáltató, más modell vagy más adat új kérést indít.
 */
object SolarisAiCache {
    private const val PREFS = "solaris_ai_cache"

    data class Entry(val key: String, val savedAtMillis: Long, val text: String)

    /** A küldött adat, a szolgáltató és a modell ujjlenyomata. */
    fun key(provider: String, model: String, sourceText: String): String {
        // Hosszelőtagos összefűzés: a részek határa nem csúszhat el (pl. sortörés a modellnévben).
        val joined = listOf(provider, model, sourceText).joinToString("") { "${it.length}:$it;" }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hit(stored: Entry?, key: String): Entry? =
        stored?.takeIf { it.key == key && it.text.isNotBlank() }

    fun load(context: Context): Entry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString("key", null) ?: return null
        val text = prefs.getString("text", null) ?: return null
        return Entry(key, prefs.getLong("saved_at", 0L), text)
    }

    fun save(context: Context, entry: Entry) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", entry.key)
            .putLong("saved_at", entry.savedAtMillis)
            .putString("text", entry.text)
            .apply()
    }
}
