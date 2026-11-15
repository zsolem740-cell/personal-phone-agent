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
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val messages = ArrayList<ChatMessage>()
    private lateinit var adapter: MessageAdapter
    private lateinit var list: ListView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        list = findViewById(R.id.message_list)
        adapter = MessageAdapter(this, messages)
        list.adapter = adapter

        input = findViewById(R.id.command_input)
        findViewById<Button>(R.id.send_button).setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }

        setupQuickCommands()
        appendAssistant(getString(R.string.welcome_message))
    }

    private fun setupQuickCommands() {
        val container = findViewById<android.widget.LinearLayout>(R.id.chips_container)
        val commands = resources.getStringArray(R.array.quick_commands)
        for (command in commands) {
            val chip = layoutInflater.inflate(R.layout.item_chip, container, false) as Button
            chip.text = command
            chip.setOnClickListener {
                input.setText(command)
                submit()
            }
            container.addView(chip)
        }
    }

    private fun submit() {
        val request = input.text.toString().trim().take(500)
        input.text.clear()
        if (request.isEmpty()) {
            appendAssistant(getString(R.string.empty_command))
            return
        }
        appendUser(request)
        handle(request)
    }

    private fun handle(request: String) {
        when (val command = CommandRouter.parse(request)) {
            is Command.Greeting -> appendAssistant(getString(R.string.answer_greeting))
            is Command.Time -> appendAssistant(getString(R.string.answer_time, currentTime("HH:mm")))
            is Command.Date -> appendAssistant(getString(R.string.answer_date, currentTime("yyyy. MMMM d., EEEE")))
            is Command.Battery -> {
                val percent = batteryPercent()
                if (percent < 0) {
                    appendAssistant(getString(R.string.answer_battery_unknown))
                } else {
                    appendAssistant(getString(R.string.answer_battery, percent))
                }
            }
            is Command.Help -> appendAssistant(getString(R.string.answer_help))
            is Command.Empty -> appendAssistant(getString(R.string.empty_command))
            is Command.Unknown -> appendAssistant(getString(R.string.answer_unknown))
            is Command.Calculator -> confirmAction(
                getString(R.string.calculator_title),
                getString(R.string.calculator_question)
            ) { openCalculator() }
            is Command.Alarms -> confirmAction(
                getString(R.string.alarms_title),
                getString(R.string.alarms_question)
            ) { openAlarms() }
            is Command.Browser -> confirmAction(
                getString(R.string.browser_title),
                getString(R.string.browser_question)
            ) { openBrowser() }
            is Command.Settings -> confirmAction(
                getString(R.string.settings_title),
                getString(R.string.settings_question)
            ) { openSettings() }
            is Command.Camera -> confirmAction(
                getString(R.string.camera_title),
                getString(R.string.camera_question)
            ) { openCamera() }
            is Command.Wifi -> confirmAction(
                getString(R.string.wifi_title),
                getString(R.string.wifi_question)
            ) { openWifiSettings() }
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
            appendAssistant(getString(R.string.calculator_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.calculator_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openAlarms() {
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            appendAssistant(getString(R.string.alarms_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.alarms_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openBrowser() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER))
            appendAssistant(getString(R.string.browser_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.browser_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            appendAssistant(getString(R.string.settings_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.settings_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openCamera() {
        try {
            startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            appendAssistant(getString(R.string.camera_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.camera_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            appendAssistant(getString(R.string.wifi_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.wifi_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openWebSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
            startActivity(intent)
            appendAssistant(getString(R.string.search_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.search_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun batteryPercent(): Int {
        val batteryManager = getSystemService(BatteryManager::class.java)
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (percent in 1..100) percent else -1
    }

    private fun currentTime(pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    private fun appendUser(text: String) = appendMessage(fromUser = true, text = text)

    private fun appendAssistant(text: String) = appendMessage(fromUser = false, text = text)

    private fun appendMessage(fromUser: Boolean, text: String) {
        messages.add(ChatMessage(fromUser, text, currentTime("HH:mm")))
        adapter.notifyDataSetChanged()
        list.post { list.setSelection(messages.size - 1) }
    }
}
