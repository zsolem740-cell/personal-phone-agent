package hu.personal.phoneagent

import android.content.Context

object ReminderStore {
    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT_ID = "next_id"

    data class Reminder(val id: Int, val timeMillis: Long, val text: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(context: Context, timeMillis: Long, rawText: String): Reminder {
        val id = prefs(context).getInt(KEY_NEXT_ID, 1)
        prefs(context).edit().putInt(KEY_NEXT_ID, id + 1).apply()
        val text = rawText.replace('|', ' ').replace('\n', ' ').take(500)
        val reminder = Reminder(id, timeMillis, text)
        val all = list(context).toMutableList()
        all.add(reminder)
        save(context, all)
        return reminder
    }

    fun list(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY_LIST, "") ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size == 3 && parts[0].toIntOrNull() != null && parts[1].toLongOrNull() != null) {
                Reminder(parts[0].toInt(), parts[1].toLong(), parts[2])
            } else {
                null
            }
        }
    }

    fun remove(context: Context, id: Int) = save(context, list(context).filter { it.id != id })

    fun clear(context: Context) = save(context, emptyList())

    fun overdue(context: Context): List<Reminder> =
        list(context).filter { it.timeMillis <= System.currentTimeMillis() }

    private fun save(context: Context, reminders: List<Reminder>) {
        val raw = reminders.joinToString("\n") { "${it.id}|${it.timeMillis}|${it.text}" }
        prefs(context).edit().putString(KEY_LIST, raw).apply()
    }
}
