package hu.personal.phoneagent

import java.util.Locale

sealed class Command {
    data object Greeting : Command()
    data object Time : Command()
    data object Date : Command()
    data object Battery : Command()
    data object Help : Command()
    data object Calculator : Command()
    data object Alarms : Command()
    data object Browser : Command()
    data object Settings : Command()
    data object Camera : Command()
    data object Wifi : Command()
    data object SpeakOn : Command()
    data object SpeakOff : Command()
    data object Phone : Command()
    data object Sms : Command()
    data object Contacts : Command()
    data object Maps : Command()
    data class Note(val text: String) : Command()
    data object NotesList : Command()
    data object NotesClear : Command()
    data class ReminderIn(val delayMillis: Long, val text: String) : Command()
    data class ReminderAt(val hour: Int, val minute: Int, val text: String) : Command()
    data object ReminderUsage : Command()
    data object RemindersList : Command()
    data object RemindersClear : Command()
    data class Call(val name: String) : Command()
    data class Search(val query: String) : Command()
    data object Empty : Command()
    data object Unknown : Command()
}

object CommandRouter {
    fun parse(text: String): Command {
        // Az egyezéshez kisbetűs, írásjel nélküli alakot használunk, a tartalomhoz
        // (jegyzet, emlékeztető, keresés, név) viszont az eredeti írásmódot.
        val original = text.trim().replace(Regex("\\s+"), " ")
        val normalized = original.lowercase(Locale.ROOT).trimEnd('?', '.', '!')
        return when (normalized) {
            "" -> Command.Empty
            "szia", "hello", "helló" -> Command.Greeting
            "idő", "ido", "mennyi az idő", "hány óra van", "hany ora van" -> Command.Time
            "dátum", "datum", "milyen nap van" -> Command.Date
            "akku", "akkumulátor", "akkumulator" -> Command.Battery
            "segítség", "segitseg", "súgó", "sugo" -> Command.Help
            "számológép", "szamologep", "nyisd meg a számológépet", "nyisd meg a szamologepet" -> Command.Calculator
            "ébresztők", "ebresztok", "ébresztő", "ebreszto" -> Command.Alarms
            "böngésző", "bongeszo", "internet", "nyisd meg a böngészőt", "nyisd meg a bongeszot" -> Command.Browser
            "beállítások", "beallitasok", "nyisd meg a beállításokat", "nyisd meg a beallitasokat" -> Command.Settings
            "kamera", "nyisd meg a kamerát", "nyisd meg a kamerat" -> Command.Camera
            "wi-fi", "wifi", "wlan" -> Command.Wifi
            "felolvasás", "felolvasas", "felolvasás be", "felolvasas be", "olvasd fel a válaszokat" -> Command.SpeakOn
            "felolvasás ki", "felolvasas ki", "elhallgatás", "elhallgatas", "hallgass el" -> Command.SpeakOff
            "telefon", "hívás", "hivas", "tárcsázó", "tarcsazo", "nyisd meg a telefont" -> Command.Phone
            "sms", "üzenetek", "uzenetek", "nyisd meg az üzeneteket", "nyisd meg az uzeneteket" -> Command.Sms
            "névjegyek", "nevjegyek", "kontaktok", "nyisd meg a névjegyeket", "nyisd meg a nevjegyeket" -> Command.Contacts
            "térkép", "terkep", "navigáció", "navigacio", "nyisd meg a térképet", "nyisd meg a terkepet" -> Command.Maps
            "jegyzetek" -> Command.NotesList
            "jegyzetek törlése", "jegyzetek torlese" -> Command.NotesClear
            "emlékeztetők", "emlekeztetok" -> Command.RemindersList
            "emlékeztetők törlése", "emlekeztetok torlese" -> Command.RemindersClear
            else -> reminderQuery(original)
                ?: callQuery(original)
                ?: noteQuery(original)
                ?: searchQuery(original)
                ?: Command.Unknown
        }
    }

    /** Kis- és nagybetűtől független előtag; a maradékot eredeti írásmóddal adja vissza. */
    private fun afterPrefix(original: String, prefixes: List<String>): String? {
        for (prefix in prefixes) {
            if (original.startsWith(prefix, ignoreCase = true)) {
                return original.substring(prefix.length).trim()
            }
        }
        return null
    }

    private val reminderMinutes = Regex("^(\\d{1,3})\\s*perc\\s*(múlva|mulva)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reminderHours = Regex("^(\\d{1,2})\\s*(óra|ora)\\s*(múlva|mulva)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val reminderClock = Regex("^(\\d{1,2}):(\\d{2})\\s+(.+)$")

    private fun reminderQuery(original: String): Command? {
        val rest = afterPrefix(original, listOf("emlékeztess ", "emlekeztess ", "emlékeztess:", "emlekeztess:"))
            ?: return null

        val relativeMinutes = reminderMinutes.find(rest)
        if (relativeMinutes != null) {
            val minutes = relativeMinutes.groupValues[1].toLongOrNull() ?: return Command.ReminderUsage
            return Command.ReminderIn(minutes * 60_000L, relativeMinutes.groupValues[3])
        }

        val relativeHours = reminderHours.find(rest)
        if (relativeHours != null) {
            val hours = relativeHours.groupValues[1].toLongOrNull() ?: return Command.ReminderUsage
            return Command.ReminderIn(hours * 3_600_000L, relativeHours.groupValues[4])
        }

        val absolute = reminderClock.find(rest)
        if (absolute != null) {
            val hour = absolute.groupValues[1].toIntOrNull()
            val minute = absolute.groupValues[2].toIntOrNull()
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                return Command.ReminderAt(hour, minute, absolute.groupValues[3])
            }
        }
        return Command.ReminderUsage
    }

    private fun callQuery(original: String): Command.Call? {
        val name = afterPrefix(original, listOf("hívd fel ", "hivd fel "))?.trimEnd('?', '.', '!')?.trim()
        return if (name.isNullOrEmpty()) null else Command.Call(name)
    }

    private fun noteQuery(original: String): Command.Note? {
        val note = afterPrefix(original, listOf("jegyzetelj ", "jegyzetelj:", "jegyzet: "))
        return if (note.isNullOrEmpty()) null else Command.Note(note)
    }

    private fun searchQuery(original: String): Command.Search? {
        val query = afterPrefix(original, listOf("keresés ", "kereses ", "keress "))
        return if (query.isNullOrEmpty()) null else Command.Search(query)
    }
}
