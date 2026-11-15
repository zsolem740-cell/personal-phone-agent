package hu.personal.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val messages = ArrayList<ChatMessage>()
    private lateinit var adapter: MessageAdapter
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var speakToggle: ImageButton
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView

    private val notes = ArrayList<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakEnabled = false
    private lateinit var preferences: SharedPreferences

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferences = getPreferences(MODE_PRIVATE)
        speakEnabled = preferences.getBoolean(PREF_SPEAK, false)
        preferences.getString(PREF_NOTES, "")?.let { stored ->
            notes.addAll(stored.split('\n').filter { it.isNotBlank() })
        }

        clockTime = findViewById(R.id.clock_time)
        clockDate = findViewById(R.id.clock_date)

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

        speakToggle = findViewById(R.id.speak_toggle)
        speakToggle.setOnClickListener { toggleSpeak() }
        updateSpeakToggle()

        findViewById<ImageButton>(R.id.mic_button).setOnClickListener { startSpeechInput() }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("hu", "HU")
            }
        }

        setupQuickCommands()
        appendAssistant(getString(R.string.welcome_message))
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        clockHandler.postDelayed(clockRunnable, CLOCK_INTERVAL_MS)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SPEECH) return
        if (resultCode == RESULT_OK) {
            val recognized = data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!recognized.isNullOrBlank()) {
                appendUser(recognized)
                handle(recognized)
            }
        }
    }

    private fun updateClock() {
        clockTime.text = currentTime("HH:mm:ss")
        clockDate.text = currentTime("yyyy. MMMM d., EEEE")
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
            is Command.SpeakOn -> setSpeakEnabled(true)
            is Command.SpeakOff -> setSpeakEnabled(false)
            is Command.Note -> saveNote(command.text)
            is Command.NotesList -> listNotes()
            is Command.NotesClear -> confirmAction(
                getString(R.string.notes_clear_title),
                getString(R.string.notes_clear_question)
            ) { clearNotes() }
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
            is Command.Phone -> confirmAction(
                getString(R.string.phone_title),
                getString(R.string.phone_question)
            ) { openPhone() }
            is Command.Sms -> confirmAction(
                getString(R.string.sms_title),
                getString(R.string.sms_question)
            ) { openSms() }
            is Command.Contacts -> confirmAction(
                getString(R.string.contacts_title),
                getString(R.string.contacts_question)
            ) { openContacts() }
            is Command.Maps -> confirmAction(
                getString(R.string.maps_title),
                getString(R.string.maps_question)
            ) { openMaps() }
            is Command.Search -> confirmAction(
                getString(R.string.search_title),
                getString(R.string.search_question, command.query)
            ) { openWebSearch(command.query) }
        }
    }

    private fun startSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
        }
        try {
            startActivityForResult(intent, REQUEST_SPEECH)
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.speech_missing))
        }
    }

    private fun toggleSpeak() = setSpeakEnabled(!speakEnabled)

    private fun setSpeakEnabled(enabled: Boolean) {
        speakEnabled = enabled
        preferences.edit().putBoolean(PREF_SPEAK, enabled).apply()
        updateSpeakToggle()
        appendAssistant(
            getString(if (enabled) R.string.answer_speak_on else R.string.answer_speak_off)
        )
    }

    private fun updateSpeakToggle() {
        speakToggle.alpha = if (speakEnabled) 1.0f else 0.35f
    }

    private fun saveNote(text: String) {
        notes.add(text.replace('\n', ' ').take(500))
        persistNotes()
        appendAssistant(getString(R.string.note_saved))
    }

    private fun listNotes() {
        if (notes.isEmpty()) {
            appendAssistant(getString(R.string.notes_empty))
            return
        }
        val listed = notes.withIndex().joinToString("\n") { (index, note) ->
            "${index + 1}. $note"
        }
        appendAssistant(getString(R.string.notes_list_header) + "\n" + listed)
    }

    private fun clearNotes() {
        notes.clear()
        persistNotes()
        appendAssistant(getString(R.string.notes_cleared))
    }

    private fun persistNotes() {
        preferences.edit().putString(PREF_NOTES, notes.joinToString("\n")).apply()
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

    private fun openPhone() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL))
            appendAssistant(getString(R.string.phone_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.phone_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openSms() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
            appendAssistant(getString(R.string.sms_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.sms_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openContacts() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS))
            appendAssistant(getString(R.string.contacts_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.contacts_missing))
        } catch (_: SecurityException) {
            appendAssistant(getString(R.string.action_denied))
        }
    }

    private fun openMaps() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
            appendAssistant(getString(R.string.maps_done))
        } catch (_: ActivityNotFoundException) {
            appendAssistant(getString(R.string.maps_missing))
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
        if (!fromUser && speakEnabled && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_response")
        }
    }

    companion object {
        private const val REQUEST_SPEECH = 1001
        private const val PREF_SPEAK = "speak_enabled"
        private const val PREF_NOTES = "notes"
        private const val CLOCK_INTERVAL_MS = 1000L
    }
}
