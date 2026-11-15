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
            else -> searchQuery(normalized) ?: Command.Unknown
        }
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
