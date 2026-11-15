package hu.personal.phoneagent

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
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
    private lateinit var micButton: View
    private lateinit var orbIcon: ImageButton
    private lateinit var stateText: TextView
    private lateinit var ringOne: View
    private lateinit var ringTwo: View
    private lateinit var metricBattery: TextView
    private lateinit var metricAssistant: TextView
    private lateinit var metricSpeech: TextView
    private val orbAnimators = ArrayList<ObjectAnimator>()
    private var speakPulse: ObjectAnimator? = null
    private lateinit var consoleView: View
    private lateinit var chatView: View
    private lateinit var drawerOverlay: View
    private lateinit var chatPreviewText: TextView

    private val notes = ArrayList<String>()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakEnabled = false
    private lateinit var preferences: SharedPreferences
    private var pendingCallName: String? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())
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

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

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

        micButton = findViewById(R.id.mic_button)
        orbIcon = findViewById(R.id.orb_icon)
        stateText = findViewById(R.id.state_text)
        ringOne = findViewById(R.id.ring_one)
        ringTwo = findViewById(R.id.ring_two)
        metricBattery = findViewById(R.id.metric_battery)
        metricAssistant = findViewById(R.id.metric_assistant)
        metricSpeech = findViewById(R.id.metric_speech)

        micButton.setOnClickListener { startSpeechInput() }
        findViewById<ImageButton>(R.id.ai_settings_button).setOnClickListener { showAiSettings() }
        findViewById<ImageButton>(R.id.chat_clear_button).setOnClickListener { clearChat() }

        setupQuickActions()
        startRingAnimations()

        consoleView = findViewById(R.id.console_view)
        chatView = findViewById(R.id.chat_view)
        drawerOverlay = findViewById(R.id.drawer_overlay)
        chatPreviewText = findViewById(R.id.chat_preview_text)

        setupNavigation()
        findViewById<View>(R.id.chat_preview).setOnClickListener { showChat() }
        findViewById<ImageButton>(R.id.chat_mic_button).setOnClickListener { startSpeechInput() }
        showConsole()

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("hu", "HU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = setOrbState(OrbState.SPEAK)
                    override fun onDone(utteranceId: String?) = setOrbState(OrbState.IDLE)
                    override fun onError(utteranceId: String?) = setOrbState(OrbState.IDLE)
                })
            }
        }

        showOverdueReminders()
        appendAssistant(getString(R.string.welcome_message))
        updateMetrics()
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        updateMetrics()
        clockHandler.postDelayed(clockRunnable, CLOCK_INTERVAL_MS)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        orbAnimators.forEach { it.cancel() }
        speakPulse?.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SPEECH) return
        setOrbState(OrbState.IDLE)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CONTACTS) return
        val name = pendingCallName
        pendingCallName = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && name != null) {
            lookupAndConfirmCall(name)
        } else {
            appendAssistant(getString(R.string.call_permission_denied))
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ReminderReceiver.CHANNEL_ID,
            getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun updateClock() {
        clockTime.text = currentTime("HH:mm:ss")
        clockDate.text = currentTime("yyyy. MMMM d., EEEE")
        val percent = batteryPercent()
        metricBattery.text =
            if (percent < 0) "--%" else getString(R.string.metric_battery_value, percent)
    }

    private fun updateMetrics() {
        metricAssistant.text = getString(
            if (AIClient.loadConfig(this) != null) {
                R.string.metric_assistant_ai
            } else {
                R.string.metric_assistant_local
            }
        )
        metricSpeech.text = getString(
            if (speakEnabled) R.string.metric_speech_on else R.string.metric_speech_off
        )
    }

    private fun setupQuickActions() {
        val quickCommands = mapOf(
            R.id.quick_phone to getString(R.string.quick_command_phone),
            R.id.quick_sms to getString(R.string.quick_command_sms),
            R.id.quick_maps to getString(R.string.quick_command_maps),
            R.id.quick_calc to getString(R.string.quick_command_calc),
            R.id.quick_contacts to getString(R.string.quick_command_contacts),
            R.id.quick_camera to getString(R.string.quick_command_camera),
            R.id.quick_settings to getString(R.string.quick_command_settings),
            R.id.quick_notes to getString(R.string.quick_command_notes)
        )
        for ((buttonId, command) in quickCommands) {
            findViewById<View>(buttonId).setOnClickListener { runCommand(command) }
        }
    }

    private fun runCommand(command: String) {
        showChat()
        appendUser(command)
        handle(command)
    }

    private fun clearChat() {
        messages.clear()
        adapter.notifyDataSetChanged()
        appendAssistant(getString(R.string.chat_cleared))
    }

    private fun setupNavigation() {
        findViewById<View>(R.id.nav_earth).setOnClickListener { showConsole() }
        findViewById<View>(R.id.nav_ai).setOnClickListener { openDrawer() }
        findViewById<View>(R.id.nav_call).setOnClickListener { showChat(); runCommand(getString(R.string.quick_command_phone)) }
        findViewById<View>(R.id.nav_maps).setOnClickListener { showChat(); runCommand(getString(R.string.quick_command_maps)) }
        findViewById<View>(R.id.nav_sms).setOnClickListener { showChat(); runCommand(getString(R.string.quick_command_sms)) }

        drawerOverlay.setOnClickListener { closeDrawer() }
        findViewById<ImageButton>(R.id.drawer_close).setOnClickListener { closeDrawer() }
        findViewById<Button>(R.id.drawer_ai_console).setOnClickListener {
            closeDrawer()
            showChat()
        }
        findViewById<Button>(R.id.drawer_notes).setOnClickListener {
            closeDrawer()
            showChat()
            listNotes()
        }
        findViewById<Button>(R.id.drawer_reminders).setOnClickListener {
            closeDrawer()
            showChat()
            listReminders()
        }
        findViewById<Button>(R.id.drawer_phone_settings).setOnClickListener {
            closeDrawer()
            showChat()
            runCommand(getString(R.string.quick_command_settings))
        }
        findViewById<Button>(R.id.drawer_ai_settings).setOnClickListener { showAiSettings() }
    }

    private fun showConsole() {
        consoleView.visibility = View.VISIBLE
        chatView.visibility = View.GONE
        updateNavSelection(R.id.nav_earth)
    }

    private fun showChat() {
        consoleView.visibility = View.GONE
        chatView.visibility = View.VISIBLE
        updateNavSelection(R.id.nav_ai)
    }

    private fun openDrawer() {
        drawerOverlay.visibility = View.VISIBLE
    }

    private fun closeDrawer() {
        drawerOverlay.visibility = View.GONE
    }

    private fun updateNavSelection(selectedId: Int) {
        val items = listOf(
            Triple(R.id.nav_earth, R.id.nav_earth_icon, R.id.nav_earth_label),
            Triple(R.id.nav_ai, R.id.nav_ai_icon, R.id.nav_ai_label),
            Triple(R.id.nav_call, R.id.nav_call_icon, R.id.nav_call_label),
            Triple(R.id.nav_maps, R.id.nav_maps_icon, R.id.nav_maps_label),
            Triple(R.id.nav_sms, R.id.nav_sms_icon, R.id.nav_sms_label)
        )
        for ((layoutId, iconId, labelId) in items) {
            val selected = layoutId == selectedId
            findViewById<ImageView>(iconId).setColorFilter(
                getColor(if (selected) R.color.cyan else R.color.text_muted)
            )
            findViewById<TextView>(labelId).setTextColor(
                getColor(if (selected) R.color.cyan else R.color.text_muted)
            )
        }
    }

    private enum class OrbState { IDLE, LISTEN, THINK, SPEAK }

    private fun setOrbState(state: OrbState) {
        mainHandler.post { applyOrbState(state) }
    }

    private fun applyOrbState(state: OrbState) {
        when (state) {
            OrbState.LISTEN -> {
                micButton.setBackgroundResource(R.drawable.mic_orb_listen)
                orbIcon.setColorFilter(getColor(R.color.green_status))
                stateText.setText(R.string.state_listening)
            }
            OrbState.THINK -> {
                micButton.setBackgroundResource(R.drawable.mic_orb_think)
                orbIcon.setColorFilter(getColor(R.color.orb_purple))
                stateText.setText(R.string.state_thinking)
            }
            OrbState.SPEAK -> {
                micButton.setBackgroundResource(R.drawable.mic_orb)
                orbIcon.setColorFilter(getColor(R.color.cyan))
                stateText.setText(R.string.state_speaking)
                startSpeakPulse()
            }
            OrbState.IDLE -> {
                micButton.setBackgroundResource(R.drawable.mic_orb)
                orbIcon.setColorFilter(getColor(R.color.cyan))
                stateText.setText(R.string.mic_hint)
                stopSpeakPulse()
            }
        }
    }

    private fun startRingAnimations() {
        for (ring in listOf(ringOne, ringTwo)) {
            val animators = listOf(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.92f, 1.13f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.92f, 1.13f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 0.8f, 0f)
            )
            for (animator in animators) {
                animator.repeatCount = ObjectAnimator.INFINITE
                animator.repeatMode = ObjectAnimator.RESTART
                animator.duration = 4000
                if (ring === ringTwo) {
                    animator.startDelay = 2000
                }
                animator.start()
                orbAnimators.add(animator)
            }
        }
    }

    private fun startSpeakPulse() {
        if (speakPulse?.isRunning == true) return
        speakPulse = ObjectAnimator.ofFloat(micButton, View.SCALE_X, 1f, 1.04f).apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            duration = 600
            start()
        }
    }

    private fun stopSpeakPulse() {
        speakPulse?.cancel()
        speakPulse = null
        micButton.scaleX = 1f
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
            is Command.Unknown -> handleUnknownWithAi(request)
            is Command.SpeakOn -> setSpeakEnabled(true)
            is Command.SpeakOff -> setSpeakEnabled(false)
            is Command.Note -> saveNote(command.text)
            is Command.NotesList -> listNotes()
            is Command.NotesClear -> confirmAction(
                getString(R.string.notes_clear_title),
                getString(R.string.notes_clear_question)
            ) { clearNotes() }
            is Command.ReminderUsage -> appendAssistant(getString(R.string.reminder_usage))
            is Command.ReminderIn -> {
                val hours = command.delayMillis / 3_600_000L
                val whenText = if (command.delayMillis % 3_600_000L == 0L && hours > 0) {
                    getString(R.string.reminder_in_hours, hours)
                } else {
                    getString(R.string.reminder_in_minutes, command.delayMillis / 60_000L)
                }
                confirmAction(
                    getString(R.string.reminder_confirm_title),
                    getString(R.string.reminder_confirm_question, whenText, command.text)
                ) { scheduleReminder(System.currentTimeMillis() + command.delayMillis, command.text) }
            }
            is Command.ReminderAt -> {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, command.hour)
                    set(Calendar.MINUTE, command.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                val whenText = SimpleDateFormat("MM. dd. HH:mm", Locale.getDefault())
                    .format(Date(calendar.timeInMillis))
                confirmAction(
                    getString(R.string.reminder_confirm_title),
                    getString(R.string.reminder_confirm_question, whenText, command.text)
                ) { scheduleReminder(calendar.timeInMillis, command.text) }
            }
            is Command.RemindersList -> listReminders()
            is Command.RemindersClear -> confirmAction(
                getString(R.string.reminders_clear_title),
                getString(R.string.reminders_clear_question)
            ) { clearReminders() }
            is Command.Call -> startCallFlow(command.name)
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

    private fun handleUnknownWithAi(request: String) {
        val config = AIClient.loadConfig(this)
        if (config == null) {
            appendAssistant(getString(R.string.answer_unknown))
            return
        }
        appendAssistant(getString(R.string.ai_thinking))
        setOrbState(OrbState.THINK)
        val history = messages.dropLast(1).takeLast(12)
        AIClient.request(config, getString(R.string.ai_system_prompt), history, request) { result ->
            if (isFinishing || isDestroyed) return@request
            setOrbState(OrbState.IDLE)
            when (result) {
                is AIClient.AiResult.Success -> applyAiResponse(result.text)
                is AIClient.AiResult.Error -> appendAssistant(result.message)
            }
        }
    }

    private fun applyAiResponse(response: String) {
        val trimmed = response.trim()
        val actionLine = trimmed.lineSequence()
            .firstOrNull { it.startsWith("ACTION:", ignoreCase = true) }
        if (actionLine == null) {
            appendAssistant(trimmed)
            return
        }
        val commandText = actionLine.removePrefix("ACTION:").trim()
        val explanation = trimmed.lineSequence()
            .filter { it != actionLine }
            .joinToString("\n")
            .trim()
        if (explanation.isNotBlank()) {
            appendAssistant(explanation)
        }
        val command = CommandRouter.parse(commandText)
        if (command is Command.Unknown || command is Command.Empty) {
            appendAssistant(getString(R.string.ai_action_unrecognized, commandText))
        } else {
            handle(commandText)
        }
    }

    private fun showAiSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_ai_settings, null)
        val providerSpinner = view.findViewById<Spinner>(R.id.ai_provider)
        val keyInput = view.findViewById<EditText>(R.id.ai_key)
        val modelInput = view.findViewById<EditText>(R.id.ai_model)

        val providerKeys = listOf(
            AIClient.PROVIDER_ANTHROPIC,
            AIClient.PROVIDER_OPENAI,
            AIClient.PROVIDER_GEMINI
        )
        val providerNames = listOf(
            getString(R.string.ai_provider_anthropic),
            getString(R.string.ai_provider_openai),
            getString(R.string.ai_provider_gemini)
        )
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = spinnerAdapter

        val existing = AIClient.loadConfig(this)
        val initialProvider = existing?.provider ?: AIClient.PROVIDER_ANTHROPIC
        providerSpinner.setSelection(providerKeys.indexOf(initialProvider).coerceAtLeast(0))
        keyInput.setText(existing?.apiKey ?: "")
        modelInput.setText(existing?.model ?: AIClient.defaultModel(initialProvider))

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                selectedView: View?,
                position: Int,
                id: Long
            ) {
                modelInput.setText(AIClient.defaultModel(providerKeys[position]))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val testButton = view.findViewById<Button>(R.id.ai_test_button)
        val testStatus = view.findViewById<TextView>(R.id.ai_test_status)
        testButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val provider = providerKeys[providerSpinner.selectedItemPosition]
            if (key.isEmpty()) {
                testStatus.text = getString(R.string.ai_test_no_key)
                return@setOnClickListener
            }
            val model = modelInput.text.toString().trim()
                .ifBlank { AIClient.defaultModel(provider) }
            testStatus.text = getString(R.string.ai_test_running)
            testButton.isEnabled = false
            AIClient.testConnection(AIClient.Config(provider, key, model)) { result ->
                if (isFinishing || isDestroyed) return@testConnection
                testButton.isEnabled = true
                testStatus.text = when (result) {
                    is AIClient.AiResult.Success ->
                        getString(R.string.ai_test_ok, result.text.take(80))
                    is AIClient.AiResult.Error ->
                        getString(R.string.ai_test_fail, result.message)
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ai_settings_title))
            .setView(view)
            .setPositiveButton(getString(R.string.ai_save)) { _, _ ->
                val key = keyInput.text.toString().trim()
                val provider = providerKeys[providerSpinner.selectedItemPosition]
                if (key.isEmpty()) {
                    AIClient.saveConfig(this, provider, "", "")
                    appendAssistant(getString(R.string.ai_disabled))
                } else {
                    val model = modelInput.text.toString().trim()
                        .ifBlank { AIClient.defaultModel(provider) }
                    AIClient.saveConfig(this, provider, key, model)
                    appendAssistant(getString(R.string.ai_enabled))
                }
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
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
        updateMetrics()
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

    private fun scheduleReminder(timeMillis: Long, text: String) {
        val reminder = ReminderStore.add(this, timeMillis, text)
        val intent = Intent(this, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            .putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
        val whenText = SimpleDateFormat("MM. dd. HH:mm", Locale.getDefault()).format(Date(timeMillis))
        appendAssistant(getString(R.string.reminder_scheduled, whenText))
    }

    private fun listReminders() {
        val reminders = ReminderStore.list(this)
        if (reminders.isEmpty()) {
            appendAssistant(getString(R.string.reminders_empty))
            return
        }
        val format = SimpleDateFormat("MM. dd. HH:mm", Locale.getDefault())
        val listed = reminders.joinToString("\n") { reminder ->
            "- ${reminder.text} (${format.format(Date(reminder.timeMillis))})"
        }
        appendAssistant(getString(R.string.reminders_list_header) + "\n" + listed)
    }

    private fun clearReminders() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        for (reminder in ReminderStore.list(this)) {
            val intent = Intent(this, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                reminder.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
        ReminderStore.clear(this)
        appendAssistant(getString(R.string.reminders_cleared))
    }

    private fun showOverdueReminders() {
        for (reminder in ReminderStore.overdue(this)) {
            appendAssistant(getString(R.string.reminder_notification_title) + ": " + reminder.text)
            ReminderStore.remove(this, reminder.id)
        }
    }

    private fun startCallFlow(name: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            lookupAndConfirmCall(name)
        } else {
            pendingCallName = name
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        }
    }

    private fun lookupAndConfirmCall(name: String) {
        val contact = findContact(name)
        if (contact == null) {
            appendAssistant(getString(R.string.call_contact_missing, name))
            return
        }
        val (displayName, number) = contact
        confirmAction(
            getString(R.string.call_title),
            getString(R.string.call_question, displayName, number)
        ) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
                appendAssistant(getString(R.string.call_done))
            } catch (_: ActivityNotFoundException) {
                appendAssistant(getString(R.string.phone_missing))
            }
        }
    }

    private fun findContact(name: String): Pair<String, String>? {
        val target = name.lowercase(Locale.ROOT)
        var exact: Pair<String, String>? = null
        var partial: Pair<String, String>? = null
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        ) ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val displayName = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val lower = displayName.lowercase(Locale.ROOT)
                if (lower == target) {
                    exact = displayName to number
                    break
                }
                if (exact == null && partial == null &&
                    (lower.contains(target) || target.contains(lower))
                ) {
                    partial = displayName to number
                }
            }
        }
        return exact ?: partial
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
        if (!fromUser) chatPreviewText.text = text
        if (!fromUser && speakEnabled && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_response")
        }
    }

    companion object {
        private const val REQUEST_SPEECH = 1001
        private const val REQUEST_CONTACTS = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
        private const val PREF_SPEAK = "speak_enabled"
        private const val PREF_NOTES = "notes"
        private const val CLOCK_INTERVAL_MS = 1000L
    }
}
