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
            else -> noteQuery(normalized) ?: searchQuery(normalized) ?: Command.Unknown
        }
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
