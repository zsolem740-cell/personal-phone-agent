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
        val normalized = text.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trimEnd('?', '.', '!')
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
            else -> reminderQuery(normalized)
                ?: callQuery(normalized)
                ?: noteQuery(normalized)
                ?: searchQuery(normalized)
                ?: Command.Unknown
        }
    }

    private fun reminderQuery(normalized: String): Command? {
        for (prefix in listOf("emlékeztess ", "emlekeztess ", "emlékeztess:", "emlekeztess:")) {
            if (!normalized.startsWith(prefix)) continue
            val rest = normalized.removePrefix(prefix).trim()

            val relativeMinutes = Regex("^(\\d{1,3})\\s*perc\\s*(múlva|mulva)\\s+(.+)$").find(rest)
            if (relativeMinutes != null) {
                val minutes = relativeMinutes.groupValues[1].toLongOrNull() ?: return Command.ReminderUsage
                return Command.ReminderIn(minutes * 60_000L, relativeMinutes.groupValues[3])
            }

            val relativeHours = Regex("^(\\d{1,2})\\s*(óra|ora)\\s*(múlva|mulva)\\s+(.+)$").find(rest)
            if (relativeHours != null) {
                val hours = relativeHours.groupValues[1].toLongOrNull() ?: return Command.ReminderUsage
                return Command.ReminderIn(hours * 3_600_000L, relativeHours.groupValues[4])
            }

            val absolute = Regex("^(\\d{1,2}):(\\d{2})\\s+(.+)$").find(rest)
            if (absolute != null) {
                val hour = absolute.groupValues[1].toIntOrNull()
                val minute = absolute.groupValues[2].toIntOrNull()
                if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                    return Command.ReminderAt(hour, minute, absolute.groupValues[3])
                }
            }
            return Command.ReminderUsage
        }
        return null
    }

    private fun callQuery(normalized: String): Command.Call? {
        for (prefix in listOf("hívd fel ", "hivd fel ")) {
            if (normalized.startsWith(prefix)) {
                val name = normalized.removePrefix(prefix).trim()
                if (name.isNotEmpty()) return Command.Call(name)
            }
        }
        return null
    }

    private fun noteQuery(normalized: String): Command.Note? {
        for (prefix in listOf("jegyzetelj ", "jegyzetelj:", "jegyzet: ")) {
            if (normalized.startsWith(prefix)) {
                val note = normalized.removePrefix(prefix).trim()
                if (note.isNotEmpty()) return Command.Note(note)
            }
        }
        return null
    }

    private fun searchQuery(normalized: String): Command.Search? {
        for (prefix in listOf("keresés ", "kereses ", "keress ")) {
            if (normalized.startsWith(prefix)) {
                val query = normalized.removePrefix(prefix).trim()
                if (query.isNotEmpty()) return Command.Search(query)
            }
        }
        return null
    }
}
