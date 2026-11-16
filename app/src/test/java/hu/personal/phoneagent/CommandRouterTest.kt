package hu.personal.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandRouterTest {
    @Test
    fun noteKeepsCaseAndPunctuation() {
        assertEquals(Command.Note("Tejet venni!"), CommandRouter.parse("Jegyzetelj Tejet venni!"))
    }

    @Test
    fun reminderKeepsCase() {
        assertEquals(
            Command.ReminderIn(10 * 60_000L, "Hívd fel Annát!"),
            CommandRouter.parse("Emlékeztess 10 perc múlva Hívd fel Annát!")
        )
        assertEquals(Command.ReminderAt(7, 30, "Gyógyszer."), CommandRouter.parse("emlékeztess 7:30 Gyógyszer."))
    }

    @Test
    fun searchKeepsCase() {
        assertEquals(Command.Search("Körmend időjárás?"), CommandRouter.parse("Keresés Körmend időjárás?"))
    }

    @Test
    fun callNameKeepsCaseWithoutTrailingPunctuation() {
        assertEquals(Command.Call("Kovács Anna"), CommandRouter.parse("Hívd fel Kovács Anna."))
    }

    @Test
    fun fixedCommandsStillMatch() {
        assertEquals(Command.Time, CommandRouter.parse("  Hány   óra van? "))
        assertEquals(Command.NotesList, CommandRouter.parse("Jegyzetek."))
        assertEquals(Command.ReminderUsage, CommandRouter.parse("emlékeztess holnap"))
        assertEquals(Command.Unknown, CommandRouter.parse("mi újság"))
    }
}
