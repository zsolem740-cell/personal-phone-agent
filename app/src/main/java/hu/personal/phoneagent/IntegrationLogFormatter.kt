package hu.personal.phoneagent

import android.content.Context
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Az IntegrationLog JSONL-sorait alakítja olvasható szöveggé
 * a „Solaris-kapcsolati napló” nézethez.
 *
 * Csak a naplóban ténylegesen szereplő mezőket jeleníti meg;
 * tartalmat (összefoglaló, AI-válasz) a napló nem is tárol.
 */
object IntegrationLogFormatter {

    private val HUNGARIAN = Locale("hu", "HU")
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM. dd. HH:mm:ss", HUNGARIAN)

    fun format(context: Context, lines: List<String>): String =
        lines.mapNotNull { formatLine(context, it) }.joinToString("\n\n")

    private fun formatLine(context: Context, line: String): String? {
        val entry = try {
            JSONObject(line)
        } catch (_: Exception) {
            return null
        }

        val time = try {
            Instant.parse(entry.getString("timestamp"))
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT)
        } catch (_: Exception) {
            "--"
        }

        val title = eventLabel(context, entry.optString("event"))
        val details = ArrayList<String>()

        when (entry.optString("status")) {
            "error" -> details.add(
                "hiba: " + entry.optString("error_code").ifBlank { "ismeretlen" }
            )
            "cancelled" -> details.add(context.getString(R.string.diag_status_cancelled))
        }

        val numbers = NumberFormat.getIntegerInstance(HUNGARIAN)
        if (entry.has("input_chars")) {
            details.add(
                context.getString(R.string.diag_chars, numbers.format(entry.optLong("input_chars")))
            )
        }
        if (entry.has("output_chars")) {
            details.add(
                context.getString(R.string.diag_chars, numbers.format(entry.optLong("output_chars")))
            )
        }

        val provider = entry.optString("provider")
        val model = entry.optString("model")
        if (provider.isNotBlank()) {
            details.add(if (model.isNotBlank()) "$provider · $model" else provider)
        }

        if (entry.has("duration_ms")) {
            details.add(formatDuration(entry.optLong("duration_ms")))
        }

        return buildString {
            append(time).append("  ").append(title)
            if (details.isNotEmpty()) {
                append('\n').append(details.joinToString(" · "))
            }
        }
    }

    private fun formatDuration(ms: Long): String =
        if (ms < 1000) {
            "$ms ms"
        } else {
            String.format(HUNGARIAN, "%.2f másodperc", ms / 1000.0)
        }

    private fun eventLabel(context: Context, event: String): String {
        val resId = when (event) {
            IntegrationLog.Event.SOLARIS_FETCH_STARTED.value -> R.string.diag_event_fetch_started
            IntegrationLog.Event.SOLARIS_FETCH_COMPLETED.value -> R.string.diag_event_fetch_completed
            IntegrationLog.Event.SOLARIS_FETCH_FAILED.value -> R.string.diag_event_fetch_failed
            IntegrationLog.Event.AI_APPROVED.value -> R.string.diag_event_ai_approved
            IntegrationLog.Event.AI_DECLINED.value -> R.string.diag_event_ai_declined
            IntegrationLog.Event.AI_STARTED.value -> R.string.diag_event_ai_started
            IntegrationLog.Event.AI_COMPLETED.value -> R.string.diag_event_ai_completed
            IntegrationLog.Event.AI_FAILED.value -> R.string.diag_event_ai_failed
            else -> return event.ifBlank { "?" }
        }
        return context.getString(resId)
    }
}
