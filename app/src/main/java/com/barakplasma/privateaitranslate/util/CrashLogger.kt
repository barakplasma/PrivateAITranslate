package com.barakplasma.privateaitranslate.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.barakplasma.privateaitranslate.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val LOG_FILE = "crashlog.txt"
    private const val MAX_LOG_SIZE = 256 * 1024

    private lateinit var logFile: File
    private lateinit var appContext: Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logFile = File(appContext.filesDir, LOG_FILE)

        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            PrintWriter(sw).use { pw ->
                pw.println("=== CRASH ${isoNow()} ===")
                pw.println("Thread: ${thread.name}")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                pw.println("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println()
                throwable.printStackTrace(pw)
                pw.println()
            }
            val crashLog = sw.toString()
            appendLog(crashLog)
            if (isCrashReportingEnabled()) {
                Sentry.captureException(throwable)
                Sentry.flush(5_000) // ensure event reaches Bugsink before the process is killed
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        val entry = buildEntry("E", tag, msg, throwable)
        appendLog(entry)
        if (isCrashReportingEnabled()) {
            if (throwable != null) {
                Sentry.captureException(throwable)
            } else {
                Sentry.captureMessage(msg, SentryLevel.ERROR)
            }
        }
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg, throwable)
        val entry = buildEntry("W", tag, msg, throwable)
        appendLog(entry)
        // Warnings go to Sentry as breadcrumbs, not standalone issues.
        // They'll be attached to the next captured error for context.
        if (isCrashReportingEnabled()) {
            Sentry.addBreadcrumb("$tag: $msg")
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        val entry = buildEntry("I", tag, msg)
        appendLog(entry)
        // Info messages go to Sentry as breadcrumbs, not standalone issues.
        if (isCrashReportingEnabled()) {
            Sentry.addBreadcrumb("$tag: $msg")
        }
    }

    fun readLog(): String {
        return try {
            if (logFile.exists()) logFile.readText() else "(no logs)"
        } catch (e: Exception) {
            "(error reading log: ${e.message})"
        }
    }

    fun clearLog() {
        try {
            logFile.delete()
        } catch (_: Exception) {}
    }

    fun sendLogsToSentry() {
        if (!isCrashReportingEnabled()) {
            Log.i(TAG, "Crash reporting is disabled, logs not sent")
            return
        }
        val logs = readLog()
        if (logs.isBlank()) {
            Log.i(TAG, "Log file is empty, nothing to send to Sentry")
            return
        }
        Thread {
            Sentry.captureMessage("Local crash logs:\n$logs", SentryLevel.ERROR)
            Log.i(TAG, "Crash logs sent to Sentry")
        }.start()
    }

    private fun buildEntry(level: String, tag: String, msg: String, throwable: Throwable? = null): String {
        val sb = StringBuilder()
        sb.appendLine("${isoNow()} $level/$tag: $msg")
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.appendLine(sw.toString())
        }
        return sb.toString()
    }

    private fun appendLog(entry: String) {
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                val content = logFile.readText()
                val keep = content.takeLast(MAX_LOG_SIZE / 2)
                logFile.writeText(keep)
            }
            FileOutputStream(logFile, true).use { fos ->
                fos.write(entry.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
        } catch (_: Exception) {}
    }

    private fun isoNow(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun isCrashReportingEnabled(): Boolean {
        // Default to true (crash reporting enabled by default)
        return Preferences.get(Preferences.sendCrashReportsKey, true)
    }
}
