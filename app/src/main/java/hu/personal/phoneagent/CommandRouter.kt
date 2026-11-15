package hu.personal.phoneagent

import java.util.Locale

object CommandRouter {
    enum class Command { GREETING, TIME, HELP, CALCULATOR, EMPTY, UNKNOWN }

    fun parse(text: String): Command {
        val normalized = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trimEnd('?', '.', '!')
        return when (normalized) {
            "szia", "hello" -> Command.GREETING
            "idő", "ido", "mennyi az idő" -> Command.TIME
            "segítség", "segitseg" -> Command.HELP
            "számológép", "szamologep", "nyisd meg a számológépet", "nyisd meg a szamologepet" -> Command.CALCULATOR
            "" -> Command.EMPTY
            else -> Command.UNKNOWN
        }
    }
}
