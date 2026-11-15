package hu.personal.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val messages = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.message_list)
        adapter = ArrayAdapter(this, R.layout.item_message, R.id.message_text, messages)
        list.adapter = adapter

        val input = findViewById<EditText>(R.id.command_input)
        findViewById<Button>(R.id.send_button).setOnClickListener { submit(input) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(input)
                true
            } else {
                false
            }
        }

        append(getString(R.string.welcome_message))
    }

    private fun submit(input: EditText) {
        val request = input.text.toString().trim().take(500)
        input.text.clear()
        if (request.isEmpty()) {
            append(getString(R.string.empty_command))
            return
        }
        append(getString(R.string.you_prefix, request))
        handle(request)
    }

    private fun handle(request: String) {
        when (val command = CommandRouter.parse(request)) {
            is Command.Greeting -> append(getString(R.string.answer_greeting))
            is Command.Time -> append(getString(R.string.answer_time, currentTime("HH:mm")))
            is Command.Date -> append(getString(R.string.answer_date, currentTime("yyyy. MMMM d., EEEE")))
            is Command.Battery -> {
                val percent = batteryPercent()
                if (percent < 0) {
                    append(getString(R.string.answer_battery_unknown))
                } else {
                    append(getString(R.string.answer_battery, percent))
                }
            }
            is Command.Help -> append(getString(R.string.answer_help))
            is Command.Empty -> append(getString(R.string.empty_command))
            is Command.Unknown -> append(getString(R.string.answer_unknown))
            is Command.Calculator -> confirmAction(
                getString(R.string.calculator_title),
                getString(R.string.calculator_question)
            ) { openCalculator() }
            is Command.Alarms -> confirmAction(
                getString(R.string.alarms_title),
                getString(R.string.alarms_question)
            ) { openAlarms() }
            is Command.Search -> confirmAction(
                getString(R.string.search_title),
                getString(R.string.search_question, command.query)
            ) { openWebSearch(command.query) }
        }
    }

    private fun confirmAction(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.yes)) { _, _ -> action() }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }

    private fun openCalculator() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
            append(getString(R.string.calculator_done))
        } catch (_: ActivityNotFoundException) {
            append(getString(R.string.calculator_missing))
        } catch (_: SecurityException) {
            append(getString(R.string.action_denied))
        }
    }

    private fun openAlarms() {
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            append(getString(R.string.alarms_done))
        } catch (_: ActivityNotFoundException) {
            append(getString(R.string.alarms_missing))
        } catch (_: SecurityException) {
            append(getString(R.string.action_denied))
        }
    }

    private fun openWebSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
            startActivity(intent)
            append(getString(R.string.search_done))
        } catch (_: ActivityNotFoundException) {
            append(getString(R.string.search_missing))
        } catch (_: SecurityException) {
            append(getString(R.string.action_denied))
        }
    }

    private fun batteryPercent(): Int {
        val batteryManager = getSystemService(BatteryManager::class.java)
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (percent in 1..100) percent else -1
    }

    private fun currentTime(pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    private fun append(text: String) {
        messages.add(text)
        adapter.notifyDataSetChanged()
        list.post { list.setSelection(messages.size - 1) }
    }
}
