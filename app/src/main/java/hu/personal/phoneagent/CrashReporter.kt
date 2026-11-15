package hu.personal.phoneagent

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Az összeomlás okát a telefonon tárolja, hogy a következő indításkor meg lehessen mutatni. */
object CrashReporter {
    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread.name, error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Elkapott, de rögzítendő hiba (az app nem omlik össze). */
    fun recordHandled(context: Context, where: String, error: Throwable) {
        try {
            write(context.applicationContext, "kezelt: $where", error)
        } catch (_: Throwable) {
        }
    }

    private fun file(context: Context) = File(File(context.noBackupFilesDir, DIR), FILE)

    private fun write(context: Context, threadName: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            "?"
        }
        val text = buildString {
            append("Időpont: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())).append('\n')
            append("App: ").append(version).append('\n')
            append("Eszköz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Szál: ").append(threadName).append("\n\n")
            append(trace.take(20_000))
        }
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    /** Visszaadja és törli az utolsó hibajelentést. */
    fun takeLastReport(context: Context): String? = try {
        val f = file(context)
        if (!f.exists()) null else f.readText().also { f.delete() }.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}

class PhoneAgentApp : Application() {
    override fun onCreate() {
        CrashReporter.install(this)
        super.onCreate()
    }
}
