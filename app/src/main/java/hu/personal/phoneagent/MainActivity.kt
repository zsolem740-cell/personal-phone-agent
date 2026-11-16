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
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowInsets
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
import android.widget.Toast
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

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
    private var ttsEngineTried = false
    private var ttsProblem: String? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var ttsReady = false
    private var speakEnabled = false
    private lateinit var preferences: SharedPreferences
    private var pendingCallName: String? = null
    private var notificationRequestPending = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets(findViewById(R.id.root))
        IntegrationLog.init(this)

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
        // A mikrofon közepén lévő ikon ImageButton, ezért magának is kell kezelő,
        // különben elnyeli az érintést.
        orbIcon.setOnClickListener { startSpeechInput() }
        (metricSpeech.parent as? View)?.setOnClickListener { toggleSpeak() }
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

        initTts(null)

        showOverdueReminders()
        appendAssistant(getString(R.string.welcome_message))
        updateMetrics()
        try {
            SolarisFeed.schedule(this)
        } catch (e: Exception) {
            CrashReporter.recordHandled(this, "SolarisFeed.schedule", e)
        }
        if (savedInstanceState == null) safeHandleIncomingIntent(intent)
        showLastCrashReportIfAny()
    }

    private fun safeHandleIncomingIntent(intent: Intent?) {
        try {
            handleIncomingIntent(intent)
        } catch (e: Exception) {
            CrashReporter.recordHandled(this, "handleIncomingIntent", e)
        }
    }

    private fun showLastCrashReportIfAny() {
        val report = CrashReporter.takeLastReport(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Hibajelentés az előző futásból")
            .setMessage(report.take(4000))
            .setPositiveButton("Megosztás") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Telefonasszisztens hibajelentés")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                try {
                    startActivity(Intent.createChooser(send, "Hibajelentés küldése"))
                } catch (_: Exception) {
                }
            }
            .setNeutralButton("Másolás") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("hibajelentés", report))
                Toast.makeText(this, "Vágólapra másolva", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Bezárás", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        safeHandleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        updateMetrics()
        clockHandler.postDelayed(clockRunnable, CLOCK_INTERVAL_MS)
        // Az engedélykérő ablak közben ne szóljunk; a válasz után úgyis ellenőrizzük.
        if (!notificationRequestPending) maybeWarnNotificationsOff()
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        orbAnimators.forEach { it.cancel() }
        speakPulse?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT_LOG) {
            handleExportResult(resultCode, data)
            return
        }
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
        if (requestCode == REQUEST_NOTIFICATIONS) {
            notificationRequestPending = false
            maybeWarnNotificationsOff()
            return
        }
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startInAppRecognizer()
            } else {
                startSpeechActivity()
            }
            return
        }
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
            notificationRequestPending = true
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
        findViewById<Button>(R.id.drawer_solaris).setOnClickListener { fetchAndInterpretSolaris() }
        findViewById<Button>(R.id.drawer_diagnostics).setOnClickListener {
            closeDrawer()
            showDiagnostics()
        }
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

    // ===== Solaris-összefoglaló fogadása megosztással =====

    private fun handleIncomingIntent(incoming: Intent?) {
        when (incoming?.action) {
            SolarisFeed.ACTION_INTERPRET -> {
                incoming.action = null
                interpretLatestSolaris()
            }
            Intent.ACTION_SEND -> handleSolarisShare(incoming)
        }
    }

    /** Értesítésre koppintás: a koppintás maga a jóváhagyás, nincs külön kérdés. */
    private fun interpretLatestSolaris() {
        SolarisFeed.cancelNotification(this)
        showChat()
        val snapshot = SolarisFeed.latest(this)
        if (snapshot == null) {
            appendAssistant(getString(R.string.solaris_feed_empty))
            return
        }
        presentSolaris(
            UUID.randomUUID().toString(), SolarisFeed.summaryId(snapshot), snapshot.updated,
            SolarisFeed.textForAi(snapshot), askFirst = false
        )
    }

    /** Menügomb: azonnali letöltés, majd értelmezés. */
    private fun fetchAndInterpretSolaris() {
        closeDrawer()
        showChat()
        appendAssistant(getString(R.string.solaris_feed_fetching))
        setOrbState(OrbState.THINK)
        val traceId = UUID.randomUUID().toString()
        Thread {
            val result = SolarisFeed.fetch(applicationContext, traceId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setOrbState(OrbState.IDLE)
                when (result) {
                    is SolarisFeed.FetchResult.Success -> {
                        SolarisFeed.cancelNotification(this)
                        presentSolaris(
                            traceId, SolarisFeed.summaryId(result.snapshot), result.snapshot.updated,
                            SolarisFeed.textForAi(result.snapshot), askFirst = false
                        )
                    }
                    is SolarisFeed.FetchResult.Failure -> {
                        val cached = SolarisFeed.latest(this)
                        appendAssistant(getString(R.string.solaris_feed_failed))
                        if (cached != null) {
                            presentSolaris(
                                traceId, SolarisFeed.summaryId(cached), cached.updated,
                                SolarisFeed.textForAi(cached), askFirst = false
                            )
                        }
                    }
                }
            }
        }.start()
    }

    private fun handleSolarisShare(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND || incoming.type?.startsWith("text/") != true) return
        // Egyszer dolgozzuk fel: forgatás vagy újraindítás ne indítsa újra.
        incoming.action = null
        val traceId = UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        showChat()
        if (text.isEmpty() || text.length > SOLARIS_MAX_CHARS) {
            IntegrationLog.record(
                traceId, IntegrationLog.Event.SOLARIS_FETCH_FAILED, IntegrationLog.Status.ERROR,
                durationMs = SystemClock.elapsedRealtime() - started,
                inputChars = text.length,
                errorCode = IntegrationLog.ErrorCode.INVALID_RESPONSE
            )
            appendAssistant(
                if (text.isEmpty()) getString(R.string.solaris_share_empty)
                else getString(R.string.solaris_share_too_long, SOLARIS_MAX_CHARS)
            )
            return
        }
        val generatedAt = Regex("^Készült:\\s*(\\S+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        val summaryId = generatedAt?.let { "solaris-$it" }
        IntegrationLog.record(
            traceId, IntegrationLog.Event.SOLARIS_FETCH_COMPLETED, IntegrationLog.Status.OK,
            durationMs = SystemClock.elapsedRealtime() - started,
            summaryId = summaryId,
            inputChars = text.length
        )
        presentSolaris(traceId, summaryId, generatedAt, text, askFirst = true)
    }

    private fun presentSolaris(
        traceId: String,
        summaryId: String?,
        generatedAt: String?,
        text: String,
        askFirst: Boolean
    ) {
        appendAssistant(getString(R.string.solaris_share_original, text))

        val config = AIClient.loadConfig(this)
        if (config == null) {
            appendAssistant(getString(R.string.solaris_share_no_ai))
            return
        }
        if (!askFirst) {
            IntegrationLog.record(
                traceId, IntegrationLog.Event.AI_APPROVED, IntegrationLog.Status.OK,
                summaryId = summaryId, provider = config.provider, model = config.model
            )
            runSolarisAnalysis(traceId, summaryId, generatedAt, text, config)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.solaris_share_confirm_title))
            .setMessage(getString(R.string.solaris_share_confirm_message, providerLabel(config.provider), config.model))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                IntegrationLog.record(
                    traceId, IntegrationLog.Event.AI_APPROVED, IntegrationLog.Status.OK,
                    summaryId = summaryId, provider = config.provider, model = config.model
                )
                runSolarisAnalysis(traceId, summaryId, generatedAt, text, config)
            }
            .setNegativeButton(getString(R.string.no)) { _, _ ->
                IntegrationLog.record(
                    traceId, IntegrationLog.Event.AI_DECLINED, IntegrationLog.Status.CANCELLED,
                    summaryId = summaryId, provider = config.provider, model = config.model,
                    errorCode = IntegrationLog.ErrorCode.CANCELLED
                )
                appendAssistant(getString(R.string.solaris_share_declined))
            }
            .setCancelable(false)
            .show()
    }

    private fun runSolarisAnalysis(
        traceId: String,
        summaryId: String?,
        generatedAt: String?,
        text: String,
        config: AIClient.Config
    ) {
        val payload = JSONObject()
            .put("task", "solaris_summary_interpretation")
            .put("summary_id", summaryId ?: JSONObject.NULL)
            .put("generated_at", generatedAt ?: JSONObject.NULL)
            .put("source_text", text)
            .toString()
        val systemPrompt = resources.openRawResource(R.raw.solaris_system_prompt)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        IntegrationLog.record(
            traceId, IntegrationLog.Event.AI_STARTED, IntegrationLog.Status.OK,
            summaryId = summaryId, provider = config.provider, model = config.model,
            inputChars = payload.length
        )
        appendAssistant(getString(R.string.ai_thinking))
        setOrbState(OrbState.THINK)
        val started = SystemClock.elapsedRealtime()
        AIClient.request(config, systemPrompt, emptyList(), payload) { result ->
            val duration = SystemClock.elapsedRealtime() - started
            when (result) {
                is AIClient.AiResult.Success -> IntegrationLog.record(
                    traceId, IntegrationLog.Event.AI_COMPLETED, IntegrationLog.Status.OK,
                    durationMs = duration, summaryId = summaryId,
                    provider = config.provider, model = config.model,
                    outputChars = result.text.length
                )
                is AIClient.AiResult.Error -> IntegrationLog.record(
                    traceId, IntegrationLog.Event.AI_FAILED, IntegrationLog.Status.ERROR,
                    durationMs = duration, summaryId = summaryId,
                    provider = config.provider, model = config.model,
                    errorCode = IntegrationLog.ErrorCode.AI_REQUEST_FAILED
                )
            }
            if (isFinishing || isDestroyed) return@request
            setOrbState(OrbState.IDLE)
            // Csak megjelenítjük: az AI válaszából itt soha nem indul parancs vagy művelet.
            when (result) {
                is AIClient.AiResult.Success -> appendAssistant(getString(R.string.solaris_share_result, result.text))
                is AIClient.AiResult.Error -> appendAssistant(getString(R.string.solaris_share_failed, result.message))
            }
        }
    }

    private fun providerLabel(provider: String): String = when (provider) {
        AIClient.PROVIDER_OPENAI -> getString(R.string.ai_provider_openai)
        AIClient.PROVIDER_GEMINI -> getString(R.string.ai_provider_gemini)
        else -> getString(R.string.ai_provider_anthropic)
    }

    private fun showAiSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_ai_settings, null)
        val providerSpinner = view.findViewById<Spinner>(R.id.ai_provider)
        val keyInput = view.findViewById<EditText>(R.id.ai_key)
        val modelInput = view.findViewById<EditText>(R.id.ai_model)
        val modelSpinner = view.findViewById<Spinner>(R.id.ai_model_choice)
        val customLabel = getString(R.string.ai_model_custom)
        var currentModels: List<String> = emptyList()

        // A listából választott modell beírja a nevet; az „Egyéni modellnév” pont a szövegmezőt mutatja.
        fun fillModels(provider: String, selected: String) {
            currentModels = AIClient.modelsFor(provider)
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currentModels + customLabel)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = adapter
            val index = currentModels.indexOf(selected)
            modelInput.setText(selected)
            if (index >= 0) {
                modelSpinner.setSelection(index)
                modelInput.visibility = View.GONE
            } else {
                modelSpinner.setSelection(currentModels.size)
                modelInput.visibility = View.VISIBLE
            }
        }

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selectedView: View?, position: Int, id: Long) {
                if (position < currentModels.size) {
                    modelInput.setText(currentModels[position])
                    modelInput.visibility = View.GONE
                } else {
                    if (modelInput.text.toString().trim() in currentModels) modelInput.setText("")
                    modelInput.visibility = View.VISIBLE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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
        fillModels(initialProvider, existing?.model ?: AIClient.defaultModel(initialProvider))

        var firstSelection = true
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                selectedView: View?,
                position: Int,
                id: Long
            ) {
                if (firstSelection) {
                    // A párbeszédpanel megnyitásakor induló automatikus hívás:
                    // ilyenkor a mentett modellnevet kell megtartani, nem felülírni.
                    firstSelection = false
                    return
                }
                val provider = providerKeys[position]
                fillModels(provider, AIClient.defaultModel(provider))
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
                    appendAssistant(
                        getString(
                            R.string.ai_enabled,
                            providerNames[providerSpinner.selectedItemPosition],
                            model
                        )
                    )
                }
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }

    // ------------------------------------------------------------------
    // Diagnosztika: Solaris-kapcsolati napló megtekintése, exportálása, törlése
    // ------------------------------------------------------------------

    private fun showDiagnostics() {
        val items = arrayOf(
            getString(R.string.diag_view_log),
            getString(R.string.diag_export_log),
            getString(R.string.diag_share_log),
            getString(R.string.diag_notifications),
            getString(R.string.diag_delete_logs)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.diag_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showConnectionLog()
                    1 -> startLogExport()
                    2 -> confirmShareLog()
                    3 -> showNotificationStatus()
                    4 -> confirmDeleteLogs()
                }
            }
            .setNegativeButton(getString(R.string.diag_close), null)
            .show()
    }

    private fun showConnectionLog() {
        IntegrationLog.readRecent(DIAG_LOG_LIMIT) { lines ->
            val body = IntegrationLogFormatter.format(this, lines)
            val dropped = IntegrationLog.droppedCount()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val text = buildString {
                    append(body.ifBlank { getString(R.string.diag_log_empty) })
                    if (dropped > 0) {
                        append("\n\n").append(getString(R.string.diag_log_dropped, dropped))
                    }
                    append("\n\n").append(getString(R.string.diag_log_footer, DIAG_LOG_LIMIT))
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.diag_log_title))
                    .setMessage(text)
                    .setPositiveButton(getString(R.string.diag_close), null)
                    .show()
            }
        }
    }

    private fun startLogExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "solaris-integration-log-${LocalDate.now()}.jsonl")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_LOG)
        } catch (_: ActivityNotFoundException) {
            showToast(getString(R.string.diag_export_missing))
        }
    }

    private fun handleExportResult(resultCode: Int, data: Intent?) {
        val target = data?.data
        if (resultCode != RESULT_OK || target == null) return
        IntegrationLog.export(this, target) { ok ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showToast(
                    getString(if (ok) R.string.diag_export_done else R.string.diag_export_failed)
                )
            }
        }
    }

    /** Napló megosztása fájlként, csak kifejezett jóváhagyás után. */
    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun confirmShareLog() {
        confirmAction(
            getString(R.string.diag_share_title),
            getString(R.string.diag_share_question)
        ) { shareLog() }
    }

    private fun shareLog() {
        IntegrationLog.readRecent(SHARE_LOG_LIMIT) { lines ->
            // Háttérszálon fut: itt írjuk a fájlt, a megosztást a fő szálon indítjuk.
            val file = if (lines.isEmpty()) null else try {
                val dir = LogShareProvider.dir(this)
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val today = LocalDate.now()
                val out = java.io.File(dir, LogShareProvider.fileName(today))
                val header = getString(
                    R.string.diag_share_header, appVersionName(), today.toString(), SHARE_LOG_LIMIT
                )
                out.writeText(header + "\n\n" + IntegrationLogFormatter.format(this, lines) + "\n", Charsets.UTF_8)
                out
            } catch (e: Exception) {
                CrashReporter.recordHandled(this, "shareLog", e)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    lines.isEmpty() -> showToast(getString(R.string.diag_share_empty))
                    file == null -> showToast(getString(R.string.diag_share_failed))
                    else -> {
                        val uri = LogShareProvider.uriFor(file)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                            clipData = android.content.ClipData.newRawUri(file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(send, getString(R.string.diag_share_chooser)))
                        } catch (_: ActivityNotFoundException) {
                            showToast(getString(R.string.diag_share_failed))
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Értesítési engedély jelzése
    // ------------------------------------------------------------------

    /** Naponta legfeljebb egyszer szól, ha az értesítések le vannak tiltva. */
    private fun maybeWarnNotificationsOff() {
        if (NotificationGate.allAllowed(this)) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_NEVER, false)) return
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_NOTIF_WARNED_DAY, null) == today) return
        prefs.edit().putString(KEY_NOTIF_WARNED_DAY, today).apply()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notif_title))
            .setMessage(getString(R.string.notif_off_message))
            .setPositiveButton(getString(R.string.notif_open_settings)) { _, _ -> openNotificationSettings() }
            .setNeutralButton(getString(R.string.notif_never)) { _, _ ->
                prefs.edit().putBoolean(KEY_NOTIF_NEVER, true).apply()
            }
            .setNegativeButton(getString(R.string.notif_later), null)
            .show()
    }

    /** A Diagnosztika menüből: mindig megmutatja az állapotot. */
    private fun showNotificationStatus() {
        val allowed = NotificationGate.allAllowed(this)
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(if (allowed) R.string.notif_on_title else R.string.notif_title))
            .setMessage(getString(if (allowed) R.string.notif_on_message else R.string.notif_off_message))
            .setNegativeButton(getString(R.string.diag_close), null)
        if (!allowed) {
            builder.setPositiveButton(getString(R.string.notif_open_settings)) { _, _ -> openNotificationSettings() }
        }
        builder.show()
    }

    private fun openNotificationSettings() {
        val appNotifications = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        val appDetails = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
        try {
            startActivity(appNotifications)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(appDetails)
            } catch (_: ActivityNotFoundException) {
                showToast(getString(R.string.notif_settings_missing))
            }
        }
    }

    private fun confirmDeleteLogs() {
        confirmAction(
            getString(R.string.diag_delete_title),
            getString(R.string.diag_delete_question)
        ) {
            IntegrationLog.deleteAll { ok ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showToast(
                        getString(if (ok) R.string.diag_delete_done else R.string.diag_delete_failed)
                    )
                }
            }
        }
    }

    /** Android 15+ alatt az app a rendszersávok alá rajzol: a sávok és a billentyűzet helyét kihagyjuk. */
    private fun applySystemInsets(root: View) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(
                    left + bars.left, top + bars.top, right + bars.right,
                    bottom + maxOf(bars.bottom, ime.bottom)
                )
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                    right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom
                )
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        root.requestApplyInsets()
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun startSpeechInput() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        tts?.stop()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            startSpeechActivity()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        startInAppRecognizer()
    }

    /** Alkalmazáson belüli beszédfelismerés: nem nyit külön ablakot, a felirat élőben frissül. */
    private fun startInAppRecognizer() {
        val sr = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        } catch (_: Exception) {
            null
        }
        if (sr == null) {
            startSpeechActivity()
            return
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                setOrbState(OrbState.LISTEN)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                setOrbState(OrbState.THINK)
            }
            override fun onError(error: Int) {
                listening = false
                setOrbState(OrbState.IDLE)
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        showToast(getString(R.string.speech_no_match))
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        appendAssistant(getString(R.string.speech_permission_denied))
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER ->
                        appendAssistant(getString(R.string.speech_network))
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        recognizer?.destroy()
                        recognizer = null
                        showToast(getString(R.string.speech_busy))
                    }
                    else -> {
                        // Például nem támogatott nyelv: a rendszer saját felismerő ablakát próbáljuk.
                        recognizer?.destroy()
                        recognizer = null
                        startSpeechActivity()
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                setOrbState(OrbState.IDLE)
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (recognized.isNullOrBlank()) {
                    showToast(getString(R.string.speech_no_match))
                } else {
                    appendUser(recognized)
                    handle(recognized)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) stateText.text = partial
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            listening = true
            setOrbState(OrbState.LISTEN)
            sr.startListening(intent)
        } catch (_: Exception) {
            listening = false
            setOrbState(OrbState.IDLE)
            startSpeechActivity()
        }
    }

    /** Tartalék: a rendszer beszédfelismerő ablaka. */
    private fun startSpeechActivity() {
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

    /** Felolvasó indítása; ha az alapmotor nem tud magyarul, a Google motorját is megpróbáljuk. */
    private fun initTts(engine: String?) {
        ttsReady = false
        val listener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                if (tryGoogleTts()) return@OnInitListener
                ttsProblem = getString(R.string.tts_init_failed)
                return@OnInitListener
            }
            val result = try {
                tts?.setLanguage(Locale("hu", "HU")) ?: TextToSpeech.LANG_NOT_SUPPORTED
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (tryGoogleTts()) return@OnInitListener
                ttsProblem = getString(R.string.tts_no_hungarian)
                return@OnInitListener
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = setOrbState(OrbState.SPEAK)
                override fun onDone(utteranceId: String?) = setOrbState(OrbState.IDLE)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = setOrbState(OrbState.IDLE)
            })
            ttsProblem = null
            ttsReady = true
        }
        tts = try {
            if (engine == null) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
        } catch (_: Exception) {
            ttsProblem = getString(R.string.tts_init_failed)
            null
        }
    }

    private fun tryGoogleTts(): Boolean {
        if (ttsEngineTried) return false
        ttsEngineTried = true
        val google = "com.google.android.tts"
        val current = tts?.defaultEngine
        val installed = tts?.engines?.any { it.name == google } == true
        if (current == google || !installed) return false
        mainHandler.post {
            tts?.shutdown()
            initTts(google)
        }
        return true
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (_: Exception) {
                appendAssistant(getString(R.string.action_denied))
            }
        }
    }

    private fun setSpeakEnabled(enabled: Boolean) {
        speakEnabled = enabled
        preferences.edit().putBoolean(PREF_SPEAK, enabled).apply()
        updateSpeakToggle()
        updateMetrics()
        appendAssistant(
            getString(if (enabled) R.string.answer_speak_on else R.string.answer_speak_off)
        )
        if (enabled && !ttsReady) {
            val problem = ttsProblem ?: getString(R.string.tts_not_ready)
            AlertDialog.Builder(this)
                .setTitle(R.string.tts_problem_title)
                .setMessage(problem)
                .setPositiveButton(R.string.tts_open_settings) { _, _ -> openTtsSettings() }
                .setNegativeButton(R.string.diag_close, null)
                .show()
        }
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
        private const val REQUEST_EXPORT_LOG = 1004
        private const val SHARE_LOG_LIMIT = 1000
        private const val UI_PREFS = "ui_state"
        private const val KEY_NOTIF_WARNED_DAY = "notif_warned_day"
        private const val KEY_NOTIF_NEVER = "notif_never"
        private const val REQUEST_AUDIO = 1005
        private const val SOLARIS_MAX_CHARS = 50_000
        private const val DIAG_LOG_LIMIT = 100
        private const val PREF_SPEAK = "speak_enabled"
        private const val PREF_NOTES = "notes"
        private const val CLOCK_INTERVAL_MS = 1000L
    }
}
