package hu.personal.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * A Solaris háttérfigyelője óránként közzéteszi a data/status.json fájlt a GitHub Pages oldalon.
 * Ez az osztály letölti, olvasható szöveggé alakítja, helyben eltárolja, és szükség esetén
 * értesítést küld. AI-hívás itt soha nem történik: azt csak a felhasználó koppintása indítja.
 */
object SolarisFeed {
    const val STATUS_URL = "https://zsolem740-cell.github.io/solaris/data/status.json"
    const val ACTION_INTERPRET = "hu.personal.phoneagent.action.SOLARIS_INTERPRET"
    const val CHANNEL_ID = "solaris"

    private const val JOB_ID = 4201
    private const val NOTIFICATION_ID = 4201
    private const val PREFS = "solaris_feed"
    private const val MAX_BYTES = 256 * 1024
    private const val QUIET_FROM_HOUR = 22
    private const val QUIET_TO_HOUR = 7
    private const val STALE_HOURS = 3L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Snapshot(val updated: String, val text: String, val activeKeys: String)

    sealed class FetchResult {
        data class Success(val snapshot: Snapshot, val isNew: Boolean) : FetchResult()
        data class Failure(val code: IntegrationLog.ErrorCode) : FetchResult()
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SolarisJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.HOURS.toMillis(1), TimeUnit.MINUTES.toMillis(15))
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** Blokkoló hívás: háttérszálon kell futtatni. */
    fun fetch(context: Context, traceId: String = UUID.randomUUID().toString()): FetchResult {
        IntegrationLog.init(context)
        val started = SystemClock.elapsedRealtime()
        IntegrationLog.record(traceId, IntegrationLog.Event.SOLARIS_FETCH_STARTED, IntegrationLog.Status.OK)
        val result = try {
            val request = Request.Builder().url(STATUS_URL).header("Cache-Control", "no-cache").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    FetchResult.Failure(IntegrationLog.ErrorCode.HTTP_ERROR)
                } else {
                    val body = response.body?.string().orEmpty()
                    if (body.isEmpty() || body.length > MAX_BYTES) {
                        FetchResult.Failure(IntegrationLog.ErrorCode.INVALID_RESPONSE)
                    } else {
                        val snapshot = parse(body)
                        if (snapshot == null) {
                            FetchResult.Failure(IntegrationLog.ErrorCode.INVALID_RESPONSE)
                        } else {
                            FetchResult.Success(snapshot, store(context, snapshot))
                        }
                    }
                }
            }
        } catch (_: IOException) {
            FetchResult.Failure(IntegrationLog.ErrorCode.CONNECTION_FAILED)
        }
        val duration = SystemClock.elapsedRealtime() - started
        when (result) {
            is FetchResult.Success -> IntegrationLog.record(
                traceId, IntegrationLog.Event.SOLARIS_FETCH_COMPLETED, IntegrationLog.Status.OK,
                durationMs = duration, summaryId = summaryId(result.snapshot),
                inputChars = result.snapshot.text.length
            )
            is FetchResult.Failure -> IntegrationLog.record(
                traceId, IntegrationLog.Event.SOLARIS_FETCH_FAILED, IntegrationLog.Status.ERROR,
                durationMs = duration, errorCode = result.code
            )
        }
        return result
    }

    fun summaryId(snapshot: Snapshot): String = "solaris-status-${snapshot.updated}"

    fun latest(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getString("updated", null) ?: return null
        val text = prefs.getString("text", null) ?: return null
        return Snapshot(updated, text, prefs.getString("active", "").orEmpty())
    }

    /** Elmenti az adatot; igazat ad, ha újabb, mint az eddig tárolt. */
    private fun store(context: Context, snapshot: Snapshot): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isNew = prefs.getString("updated", null) != snapshot.updated
        if (isNew) {
            prefs.edit()
                .putString("updated", snapshot.updated)
                .putString("text", snapshot.text)
                .putString("active", snapshot.activeKeys)
                .apply()
        }
        return isNew
    }

    /**
     * Értesítés szabálya: ha változtak az aktív figyelmeztetések, vagy aznap még nem volt értesítés.
     * Csendes időben (22 és 7 óra között) nem szól; a következő futás pótolja.
     */
    fun maybeNotify(context: Context, snapshot: Snapshot) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = java.time.ZonedDateTime.now()
        if (now.hour >= QUIET_FROM_HOUR || now.hour < QUIET_TO_HOUR) return
        val today = now.toLocalDate().toString()
        val alertsChanged = prefs.getString("notified_active", "") != snapshot.activeKeys
        val notifiedToday = prefs.getString("notified_day", null) == today
        if (!alertsChanged && notifiedToday) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.solaris_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_INTERPRET)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alerts = activeTitles(snapshot)
        val body = if (alerts.isEmpty()) {
            context.getString(R.string.solaris_notification_calm)
        } else {
            context.getString(R.string.solaris_notification_alerts, alerts)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(context.getString(R.string.solaris_notification_title))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val posted = try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
        if (posted) {
            prefs.edit()
                .putString("notified_day", today)
                .putString("notified_active", snapshot.activeKeys)
                .apply()
        }
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun activeTitles(snapshot: Snapshot): String {
        val line = snapshot.text.lineSequence().firstOrNull { it.startsWith(ACTIVE_PREFIX) } ?: return ""
        val value = line.removePrefix(ACTIVE_PREFIX).trim()
        return if (value == NONE) "" else value
    }

    private const val ACTIVE_PREFIX = "Aktív figyelmeztetések:"
    private const val NONE = "nincs"

    /** A status.json olvasható, forráshű szöveggé alakítása. Hibás szerkezetnél null. */
    fun parse(json: String): Snapshot? = try {
        val root = JSONObject(json)
        val updated = root.getString("updated")
        val updatedAt = Instant.parse(updated)
        val place = root.optString("place", "ismeretlen hely")
        val cur = root.optJSONObject("current") ?: JSONObject()
        val recent = root.optJSONArray("recent")
        val active = root.optJSONObject("rules")?.optJSONArray("active")
        val activeKeys = (0 until (active?.length() ?: 0)).map { active!!.getString(it) }

        val titles = mutableMapOf<String, String>()
        val recentLines = mutableListOf<String>()
        val dayAgo = updatedAt.minusSeconds(24 * 3600)
        if (recent != null) {
            for (i in 0 until recent.length()) {
                val item = recent.optJSONObject(i) ?: continue
                val key = item.optString("key")
                val title = item.optString("title")
                if (key.isNotEmpty() && title.isNotEmpty() && key !in titles) titles[key] = title
                val t = runCatching { Instant.parse(item.optString("t")) }.getOrNull() ?: continue
                if (t.isAfter(dayAgo) && title.isNotEmpty()) recentLines += "- ${local(t)}: $title"
            }
        }
        val activeText = if (activeKeys.isEmpty()) NONE else activeKeys.joinToString(", ") { titles[it] ?: it }

        val lines = mutableListOf(
            "SOLARIS-ÁLLAPOT (automatikus letöltés a háttérfigyelőtől)",
            "Készült: $updated",
            "Hely: $place",
            "$ACTIVE_PREFIX $activeText",
            "",
            "MÉRT ÉS SZÁMÍTOTT ÉRTÉKEK:"
        )
        fun num(key: String): Double? = if (cur.isNull(key)) null else cur.optDouble(key).takeIf { !it.isNaN() }
        fun add(label: String, value: Double?, unit: String, digits: Int = 1) {
            if (value != null) lines += "- $label: ${fmt(value, digits)}$unit"
        }
        add("Kp-index", num("kp"), "")
        add("Kp-index 24 órás maximuma", num("kpMax24"), "", 2)
        cur.optString("flare").takeIf { it.isNotEmpty() && it != "null" }?.let { lines += "- Napkitörés röntgenosztálya: $it" }
        add("Napszél sebessége", num("speed"), " km/s")
        add("Bz mágneses komponens", num("bz"), " nT")
        add("12 órás minimum-hőmérséklet", num("tmin12"), " °C")
        add("12 órás legnagyobb széllökés", num("gust12"), " km/h")
        add("Légnyomás", num("pressure"), " hPa")
        add("Légnyomás-változás 3 óra alatt", num("dp3"), " hPa")
        root.optJSONObject("csi")?.let { csi ->
            csi.optDouble("csi").takeIf { !it.isNaN() }?.let { lines += "- Összetett kockázati index (CSI, 0–1): ${fmt(it, 3)}" }
        }
        root.optJSONObject("obs")?.let { obs ->
            val st = obs.optJSONObject("station")
            if (st != null) {
                val dist = st.optDouble("distKm").takeIf { !it.isNaN() }?.let { ", ${fmt(it, 0)} km-re" } ?: ""
                val stale = if (obs.optBoolean("stale")) ", elavult" else ""
                lines += "- Mérőállomás: ${st.optString("name")}$dist, utolsó mérés: ${obs.optString("last")} (UTC)$stale"
            }
        }
        root.optJSONObject("sources")?.let { src ->
            val keys = src.keys().asSequence().toList()
            val ok = keys.count { src.optJSONObject(it)?.optBoolean("ok") == true }
            lines += "- Elérhető adatforrások: $ok / ${keys.size}"
        }
        lines += ""
        lines += "AZ ELMÚLT 24 ÓRA JELZÉSEI:"
        if (recentLines.isEmpty()) lines += "- nem volt jelzés" else lines.addAll(recentLines)
        val ageHours = (System.currentTimeMillis() - updatedAt.toEpochMilli()) / 3_600_000L
        if (ageHours >= STALE_HOURS) {
            lines += ""
            lines += "Figyelem: az adat $ageHours órája frissült utoljára."
        }
        Snapshot(updated, lines.joinToString("\n"), activeKeys.sorted().joinToString(","))
    } catch (_: JSONException) {
        null
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }

    private fun fmt(value: Double, digits: Int): String =
        String.format(Locale.forLanguageTag("hu-HU"), "%.${digits}f", value)

    private fun local(t: Instant): String =
        t.atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)
}
