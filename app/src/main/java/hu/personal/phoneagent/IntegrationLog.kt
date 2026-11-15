package hu.personal.phoneagent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Solaris-integráció üzemi naplója.
 *
 * Tárolás: noBackupFilesDir/integration_logs/integration-YYYY-MM-DD.jsonl
 * - soronként egy JSON-esemény (JSONL), UTC szerinti napi fájlok;
 * - legfeljebb 7 nap és összesen legfeljebb 5 MiB;
 * - egyetlen soros háttérszál ír, a hívót soha nem blokkolja;
 * - naplózási hiba soha nem dob kivételt a hívó felé;
 * - szándékosan nincs általános message/body paraméter.
 */
object IntegrationLog {

    private const val TAG = "SolarisIntegration"
    private const val DIR_NAME = "integration_logs"
    private const val FILE_PREFIX = "integration-"
    private const val FILE_SUFFIX = ".jsonl"

    private const val RETENTION_DAYS = 7L
    private const val MAX_TOTAL_BYTES = 5L * 1024 * 1024
    private const val MAX_LINE_BYTES = 2 * 1024
    private const val QUEUE_CAPACITY = 256

    private val FILE_NAME_PATTERN =
        Regex("""^integration-(\d{4}-\d{2}-\d{2})\.jsonl$""")

    enum class Event(val value: String) {
        SOLARIS_FETCH_STARTED("solaris.fetch.started"),
        SOLARIS_FETCH_COMPLETED("solaris.fetch.completed"),
        SOLARIS_FETCH_FAILED("solaris.fetch.failed"),
        AI_APPROVED("ai.analysis.approved"),
        AI_DECLINED("ai.analysis.declined"),
        AI_STARTED("ai.analysis.started"),
        AI_COMPLETED("ai.analysis.completed"),
        AI_FAILED("ai.analysis.failed")
    }

    enum class Status { OK, ERROR, CANCELLED }

    enum class ErrorCode {
        CONNECTION_FAILED,
        SUMMARY_NOT_AVAILABLE,
        INVALID_RESPONSE,
        AI_REQUEST_FAILED,
        CANCELLED,
        MISSING_LOCAL_TOKEN,
        UNAUTHORIZED,
        HTTP_ERROR
    }

    @Volatile private var logDir: File? = null
    @Volatile private var logcatEnabled = false

    // Az alábbi két mezőt kizárólag az író szál használja.
    private var totalBytes = 0L
    private var lastCleanupDate: LocalDate? = null

    private val droppedEntries = AtomicInteger(0)

    private val writer = ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "integration-log-writer").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    // ------------------------------------------------------------------
    // Inicializálás
    // ------------------------------------------------------------------

    /** Többször is hívható; csak az első hívás számít. */
    fun init(context: Context) {
        if (logDir != null) return
        synchronized(this) {
            if (logDir != null) return
            val app = context.applicationContext
            logcatEnabled =
                (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val dir = File(app.noBackupFilesDir, DIR_NAME)
            logDir = dir
            submit {
                if (ensureDir(dir)) cleanup(dir, todayUtc())
            }
        }
    }

    // ------------------------------------------------------------------
    // Esemény rögzítése
    // ------------------------------------------------------------------

    fun record(
        traceId: String,
        event: Event,
        status: Status,
        durationMs: Long? = null,
        summaryId: String? = null,
        provider: String? = null,
        model: String? = null,
        inputChars: Int? = null,
        outputChars: Int? = null,
        errorCode: ErrorCode? = null
    ) {
        val dir = logDir ?: return
        val now = Instant.now()

        val line = try {
            val entry = JSONObject()
                .put("timestamp", now.toString())
                .put("trace_id", traceId.take(64))
                .put("event", event.value)
                .put("status", status.name.lowercase())

            durationMs?.let { entry.put("duration_ms", it) }
            summaryId?.let { entry.put("summary_id", it.take(200)) }
            provider?.let { entry.put("provider", it.take(40)) }
            model?.let { entry.put("model", it.take(120)) }
            inputChars?.let { entry.put("input_chars", it) }
            outputChars?.let { entry.put("output_chars", it) }
            errorCode?.let { entry.put("error_code", it.name) }

            entry.toString()
        } catch (_: Exception) {
            droppedEntries.incrementAndGet()
            return
        }

        if (logcatEnabled) Log.i(TAG, line)

        submit { append(dir, now, line) }
    }

    /** Hány bejegyzés veszett el (teli sor, méretkorlát, írási hiba). */
    fun droppedCount(): Int = droppedEntries.get()

    // ------------------------------------------------------------------
    // Megtekintés, export, törlés (Beállítások › Diagnosztika)
    // ------------------------------------------------------------------

    /**
     * A legutóbbi legfeljebb [limit] bejegyzés, a legújabb elöl.
     * A callback a háttérszálon fut.
     */
    fun readRecent(limit: Int = 200, onResult: (List<String>) -> Unit) {
        val dir = logDir ?: return onResult(emptyList())
        val accepted = submit {
            val result = ArrayList<String>(limit)
            for ((_, file) in listLogFiles(dir).asReversed()) {
                if (result.size >= limit) break
                val lines = try {
                    file.readLines(Charsets.UTF_8)
                } catch (_: Exception) {
                    emptyList()
                }
                for (line in lines.asReversed()) {
                    if (line.isBlank()) continue
                    result.add(line)
                    if (result.size >= limit) break
                }
            }
            onResult(result)
        }
        if (!accepted) onResult(emptyList())
    }

    /**
     * Csak kifejezett felhasználói műveletre hívandó, a felhasználó által
     * kiválasztott célhelyre (ACTION_CREATE_DOCUMENT).
     * A callback a háttérszálon fut.
     */
    fun export(context: Context, target: Uri, onDone: (Boolean) -> Unit) {
        val dir = logDir ?: return onDone(false)
        val resolver = context.applicationContext.contentResolver
        val accepted = submit {
            val ok = try {
                resolver.openOutputStream(target, "w")?.use { out ->
                    for ((_, file) in listLogFiles(dir)) {
                        file.inputStream().use { it.copyTo(out) }
                    }
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            onDone(ok)
        }
        if (!accepted) onDone(false)
    }

    /** Az összes helyi naplófájl törlése. A callback a háttérszálon fut. */
    fun deleteAll(onDone: (Boolean) -> Unit) {
        val dir = logDir ?: return onDone(false)
        val accepted = submit {
            var ok = true
            for ((_, file) in listLogFiles(dir)) {
                if (!file.delete()) ok = false
            }
            totalBytes = listLogFiles(dir).sumOf { it.second.length() }
            onDone(ok)
        }
        if (!accepted) onDone(false)
    }

    // ------------------------------------------------------------------
    // Belső működés – kizárólag az író szálon
    // ------------------------------------------------------------------

    private fun append(dir: File, at: Instant, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LINE_BYTES) {
            droppedEntries.incrementAndGet()
            return
        }
        if (!ensureDir(dir)) {
            droppedEntries.incrementAndGet()
            return
        }

        val date = at.atOffset(ZoneOffset.UTC).toLocalDate()
        if (date != lastCleanupDate) cleanup(dir, date)

        val target = File(dir, fileName(date))

        if (totalBytes + bytes.size > MAX_TOTAL_BYTES) {
            trimToFit(dir, keep = target, needed = bytes.size.toLong())
            if (totalBytes + bytes.size > MAX_TOTAL_BYTES) {
                // Csak a mai fájl maradt, és az is megtelt: a bejegyzést eldobjuk.
                droppedEntries.incrementAndGet()
                return
            }
        }

        FileOutputStream(target, true).use { it.write(bytes) }
        totalBytes += bytes.size
    }

    /** 7 napnál régebbi fájlok törlése, majd a méretkorlát érvényesítése. */
    private fun cleanup(dir: File, today: LocalDate) {
        val oldestKept = today.minusDays(RETENTION_DAYS - 1)
        for ((date, file) in listLogFiles(dir)) {
            if (date.isBefore(oldestKept)) file.delete()
        }
        totalBytes = listLogFiles(dir).sumOf { it.second.length() }
        if (totalBytes > MAX_TOTAL_BYTES) {
            trimToFit(dir, keep = File(dir, fileName(today)), needed = 0L)
        }
        lastCleanupDate = today
    }

    /** A legrégebbi fájlokat törli, amíg a következő írás belefér a korlátba. */
    private fun trimToFit(dir: File, keep: File, needed: Long) {
        for ((_, file) in listLogFiles(dir)) {
            if (totalBytes + needed <= MAX_TOTAL_BYTES) return
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    /** Csak a saját névmintának megfelelő fájlok, dátum szerint növekvően. */
    private fun listLogFiles(dir: File): List<Pair<LocalDate, File>> {
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val match = FILE_NAME_PATTERN.matchEntire(file.name)
                ?: return@mapNotNull null
            try {
                LocalDate.parse(match.groupValues[1]) to file
            } catch (_: DateTimeParseException) {
                null
            }
        }.sortedBy { it.first }
    }

    private fun ensureDir(dir: File): Boolean =
        dir.isDirectory || dir.mkdirs()

    private fun fileName(date: LocalDate) =
        "$FILE_PREFIX$date$FILE_SUFFIX"

    private fun todayUtc(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    /** Hibát soha nem enged a hívóhoz; false, ha a sor megtelt. */
    private fun submit(task: () -> Unit): Boolean =
        try {
            writer.execute {
                try {
                    task()
                } catch (_: Throwable) {
                    droppedEntries.incrementAndGet()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            droppedEntries.incrementAndGet()
            false
        }
}
