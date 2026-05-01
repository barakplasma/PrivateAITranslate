/*
 * Copyright (c) 2023 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.barakplasma.privateaitranslate

import android.app.Application
import com.barakplasma.privateaitranslate.engine.GeminiNanoEngine
import com.barakplasma.privateaitranslate.engine.MLKitEngine
import com.barakplasma.privateaitranslate.engine.TranslateGemmaEngine
import com.barakplasma.privateaitranslate.util.CrashLogger
import com.barakplasma.privateaitranslate.util.EnginePreferencesProviderImpl
import com.barakplasma.privateaitranslate.util.Preferences
import com.barakplasma.privateaitranslate.util.SpeechHelper
import io.sentry.android.core.SentryAndroid
import net.youapps.translation_engines.TranslationEngine
import net.youapps.translation_engines.TranslationEngines
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            doOnCreate()
        } catch (t: Throwable) {
            appendFallbackCrashLog(t)
            // Sentry's UncaughtExceptionHandler (registered via SentryInitProvider ContentProvider
            // before App.onCreate()) captures this automatically when rethrown, respecting the
            // user's privacy/crash-reporting preferences.
            throw t
        }
    }

    private fun doOnCreate() {
        Preferences.initialize(this)

        initializeSentry()

        CrashLogger.init(this)

        // Disable crash reporting in high security mode or on-device only mode
        if (BuildConfig.ON_DEVICE_ONLY) {
            Preferences.put(Preferences.sendCrashReportsKey, false)
        } else {
            val highSecurityMode = Preferences.get(Preferences.highSecurityModeKey, false)
            if (highSecurityMode) {
                Preferences.put(Preferences.sendCrashReportsKey, false)
            }
        }

        DatabaseHolder().initDb(this)

        SpeechHelper.initTTS(this)

        val settingsProvider = EnginePreferencesProviderImpl()
        translationEngines = buildEngineList(settingsProvider)

        // initialize all translation engines
        updateAllTranslationEngines()
    }

    private fun appendFallbackCrashLog(t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val entry = "=== STARTUP CRASH $ts ===\n$sw\n"
            val logFile = File(filesDir, "crashlog.txt")
            if (logFile.exists() && logFile.length() > 256 * 1024) {
                logFile.writeText(logFile.readText().takeLast(128 * 1024))
            }
            FileOutputStream(logFile, true).use { it.write(entry.toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {}
    }

    private fun buildEngineList(settingsProvider: EnginePreferencesProviderImpl): List<TranslationEngine> {
        val engines = mutableListOf<TranslationEngine>()
        val factories: List<Pair<String, () -> TranslationEngine>> = buildList {
            add("GeminiNano" to { GeminiNanoEngine(settingsProvider) })
            add("MLKit" to { MLKitEngine(settingsProvider) })
            add("TranslateGemma" to { TranslateGemmaEngine(settingsProvider, this@App) })
            if (!BuildConfig.ON_DEVICE_ONLY) {
                try {
                    addAll(TranslationEngines.getAllEngines(settingsProvider).map { it.name to { it } })
                } catch (t: Throwable) {
                    CrashLogger.e("App", "Failed to load additional engines: ${t.message}", t)
                }
            }
        }
        for ((label, factory) in factories) {
            try {
                engines.add(factory())
            } catch (t: Throwable) {
                CrashLogger.e("App", "Engine '$label' failed to construct: ${t.message}", t)
            }
        }
        return engines
    }

    private fun initializeSentry() {
        SentryAndroid.init(this) { options ->
            options.dsn = "https://c270c03278e541f4ad537702fec77b1e@barakplasma.bugsink.com/1"
            options.environment = if (BuildConfig.DEBUG) "debug" else "production"
            options.release = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            options.sampleRate = 1.0  // Capture 100% of errors
            options.isAttachScreenshot = false
            options.isSendDefaultPii = false
            options.maxBreadcrumbs = 50
        }
    }

    companion object {
        var translationEngines: List<TranslationEngine> = emptyList()

        fun updateAllTranslationEngines() {
            for (engine in translationEngines) {
                try {
                    engine.createOrRecreate()
                } catch (t: Throwable) {
                    CrashLogger.e("App", "Failed to initialize engine '${engine.name}': ${t.message}", t)
                }
            }
        }

        fun getAvailableEngines(): List<TranslationEngine> {
            if (BuildConfig.ON_DEVICE_ONLY) return translationEngines.filter { it.isOnDevice }
            val highSecurityMode = Preferences.get(Preferences.highSecurityModeKey, false)
            return if (highSecurityMode) translationEngines.filter { it.isOnDevice } else translationEngines
        }
    }
}
