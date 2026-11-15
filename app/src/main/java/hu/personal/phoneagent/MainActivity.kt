package hu.personal.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private val history = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history.addAll(savedInstanceState?.getStringArrayList("history") ?: arrayListOf("Asszisztens: Helyi prototípus. Írd be: segítség. A beszélgetést nem mentem tartósan."))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        layout.addView(TextView(this).apply { text = "Telefonasszisztens"; textSize = 24f })
        transcript = TextView(this).apply {
            id = R.id.response_output
            textSize = 18f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(transcript) }
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val input = EditText(this).apply {
            id = R.id.command_input
            hint = "Parancs, például: segítség"
            maxLines = 3
        }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "Küldés"
            setOnClickListener {
                val request = input.text.toString().trim().take(2000)
                input.text.clear()
                handle(request)
            }
        })
        setContentView(layout)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("history", ArrayList(history))
        super.onSaveInstanceState(outState)
    }

    private fun append(text: String) {
        history.add(text)
        while (history.size > 100) history.removeAt(0)
        render()
    }

    private fun render() {
        transcript.text = history.joinToString("\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun handle(request: String) {
        if (request.isNotEmpty()) append("Te: $request")
        when (CommandRouter.parse(request)) {
            CommandRouter.Command.GREETING -> append("Asszisztens: Szia!")
            CommandRouter.Command.TIME -> append("Asszisztens: " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            CommandRouter.Command.HELP -> append("Asszisztens: Parancsok: szia; idő; segítség; nyisd meg a számológépet. A megnyitáshoz külön jóváhagyást kérek. Nincs AI-modell vagy hangvezérlés.")
            CommandRouter.Command.EMPTY -> append("Asszisztens: Először írj be egy parancsot.")
            CommandRouter.Command.UNKNOWN -> append("Asszisztens: Ezt még nem ismerem. Írd be: segítség.")
            CommandRouter.Command.CALCULATOR -> confirmCalculator()
        }
    }

    private fun confirmCalculator() {
        append("Asszisztens: Megerősítést kérek a számológép megnyitásához.")
        AlertDialog.Builder(this)
            .setTitle("Számológép megnyitása")
            .setMessage("Megnyithatom a telefon számológép-alkalmazását? Nem küldök neki adatot.")
            .setPositiveButton("Megnyitás") { _, _ ->
                try {
                    startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
                    append("Asszisztens: Átadtam a megnyitási kérést az Androidnak.")
                } catch (_: ActivityNotFoundException) {
                    append("Asszisztens: Nincs a kérést kezelő számológép.")
                } catch (_: SecurityException) {
                    append("Asszisztens: Az Android nem engedélyezte a megnyitást.")
                }
            }
            .setNegativeButton("Mégse") { _, _ -> append("Asszisztens: Megnyitás megszakítva.") }
            .setOnCancelListener { append("Asszisztens: Megnyitás megszakítva.") }
            .show()
    }
}
