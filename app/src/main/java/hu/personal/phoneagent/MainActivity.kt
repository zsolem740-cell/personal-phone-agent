package hu.personal.phoneagent

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = "Telefonasszisztens"
            textSize = 26f
        }
        val input = EditText(this).apply {
            hint = "Írd be: segítség vagy idő"
            id = R.id.command_input
        }
        val output = TextView(this).apply {
            text = "Helyi prototípus. Nem használ hálózatot és nem vezérli a telefont."
            textSize = 18f
            id = R.id.response_output
            setFreezesText(true)
        }
        val send = Button(this).apply {
            text = "Küldés"
            setOnClickListener { output.text = respond(input.text.toString()) }
        }
        layout.addView(title)
        layout.addView(input)
        layout.addView(send)
        layout.addView(output)
        setContentView(layout)
    }

    private fun respond(command: String): String =
        when (command.trim().lowercase(Locale.ROOT)) {
            "szia", "hello" -> "Szia! A helyi telefonasszisztensed vagyok."
            "idő", "ido", "mennyi az idő", "mennyi az idő?" ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            "segítség", "segitseg" ->
                "Elérhető parancsok: szia, idő, segítség. Ez még nem AI-modell, hanem helyi parancsfeldolgozó."
            "" -> "Először írj be egy parancsot."
            else -> "Ezt még nem ismerem. Írd be: segítség."
        }
}
