/*
 * Copyright (c) 2026 You Apps
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

package com.barakplasma.privateaitranslate.engine

import android.content.Context
import android.os.Build
import com.barakplasma.privateaitranslate.util.CrashLogger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.collect
import net.youapps.translation_engines.ApiKeyState
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.Language
import net.youapps.translation_engines.Translation
import net.youapps.translation_engines.TranslationEngine
import java.io.File
import java.text.BreakIterator

private const val TAG = "TranslateGemma"

class TranslateGemmaEngine(
    settingsProvider: EngineSettingsProvider,
    private val appContext: Context
) : TranslationEngine(settingsProvider) {
    override val name = "TranslateGemma (On-Device)"
    override val defaultUrl = ""
    override val urlModifiable = false
    override val apiKeyState = ApiKeyState.DISABLED
    override val autoLanguageCode = "auto"
    override val supportsAudio = false
    override val isOnDevice = true
    override val supportedModels = listOf("CPU", "GPU")

    private var liveEngine: Engine? = null
    private var backendAvailability: MutableMap<String, Boolean?> = mutableMapOf()

    override fun createOrRecreate(): TranslationEngine = apply {
        closeLiveEngine()
    }

    private fun closeLiveEngine() {
        try {
            liveEngine?.close()
        } catch (e: Exception) {
            CrashLogger.e(TAG, "Failed to close engine: ${e.message}", e)
        }
        liveEngine = null
    }

    private fun isBackendAvailable(backendName: String): Boolean {
        backendAvailability[backendName]?.let { return it }

        val available = try {
            when (backendName) {
                "GPU" -> {
                    Backend.GPU()
                    true
                }
                else -> {
                    Backend.CPU()
                    true
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            CrashLogger.w(TAG, "Backend '$backendName' not available: ${e.message}")
            false
        } catch (e: UnsupportedOperationException) {
            CrashLogger.w(TAG, "Backend '$backendName' not supported on this device: ${e.message}")
            false
        } catch (e: Exception) {
            CrashLogger.w(TAG, "Failed to check backend '$backendName' availability: ${e.message}")
            false
        }

        backendAvailability[backendName] = available
        return available
    }

    private fun getBackendWithFallback(): Pair<String, Backend> {
        val selectedBackend = getSelectedModel() ?: "CPU"
        // Priority order: selected backend first, then the other.
        // This ensures CPU is tried before GPU when CPU is selected (the default),
        // preventing a fallthrough to GPU which causes SIGSEGV on unsupported devices.
        val order = listOf(selectedBackend) + listOf("CPU", "GPU").filter { it != selectedBackend }

        for (backendName in order) {
            if (!isBackendAvailable(backendName)) continue
            try {
                val backend = when (backendName) {
                    "GPU" -> Backend.GPU()
                    else -> Backend.CPU()
                }
                if (backendName != selectedBackend) {
                    CrashLogger.w(TAG, "Falling back from '$selectedBackend' to '$backendName'")
                } else {
                    CrashLogger.i(TAG, "Using selected backend: $backendName")
                }
                return Pair(backendName, backend)
            } catch (e: Exception) {
                CrashLogger.w(TAG, "Backend '$backendName' failed to create: ${e.message}", e)
            }
        }

        throw IllegalStateException("All translation backends failed. Device may not support TranslateGemma.")
    }

    @Synchronized
    private fun getOrCreateEngine(): Engine {
        liveEngine?.let { return it }

        val modelFile = getModelFile(appContext)
        check(modelFile.exists()) {
            "TranslateGemma model not downloaded. Expected location: ${modelFile.absolutePath}. Open Settings → TranslateGemma to download or import it."
        }

        // Validate model file size (minimum ~1.5GB for 2GB quantized model)
        val minModelSize = 1_500_000_000L
        val modelSizeGB = String.format("%.2f", modelFile.length() / 1_000_000_000.0)
        check(modelFile.length() >= minModelSize) {
            "TranslateGemma model appears corrupted or incomplete ($modelSizeGB GB, expected >1.5 GB). Delete and re-download."
        }

        var backendName = "unknown"
        return try {
            val (name, backend) = getBackendWithFallback()
            backendName = name
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend
            )
            CrashLogger.i(TAG, "Initializing engine ($backendName) with model: ${modelFile.absolutePath} ($modelSizeGB GB)")
            val engine = Engine(config)
            engine.initialize()
            liveEngine = engine
            CrashLogger.i(TAG, "Engine initialized successfully ($backendName)")
            engine
        } catch (e: UnsatisfiedLinkError) {
            val msg = "TranslateGemma engine initialization failed ($backendName backend): Native library not available. This typically means required GPU libraries are missing on your device. Switching to CPU backend."
            CrashLogger.e(TAG, msg, e)
            throw IllegalStateException(msg, e)
        } catch (e: OutOfMemoryError) {
            closeLiveEngine()
            val msg = "TranslateGemma engine initialization failed: Device is out of memory. Please close other apps and try again."
            CrashLogger.e(TAG, msg, e)
            throw IllegalStateException(msg, e)
        } catch (e: Exception) {
            CrashLogger.e(TAG, "Failed to initialize engine after all fallbacks: ${e.message}", e)
            throw IllegalStateException("TranslateGemma engine initialization failed: ${e.message}. Check that the model file is valid and not corrupted.", e)
        }
    }

    override suspend fun getLanguages(): List<Language> = SUPPORTED_LANGUAGES

    override suspend fun translate(query: String, source: String, target: String): Translation {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw IllegalStateException("TranslateGemma requires Android 12 (API 31) or higher")
        }

        val engine = getOrCreateEngine()
        val sourceLang = if (source.isEmpty() || source == autoLanguageCode) "auto" else source
        val prompt = "<src>$sourceLang</src><dst>$target</dst><text>$query</text>"

        return try {
            val sb = StringBuilder()
            val maxOutputChars = (query.length * 6).coerceAtLeast(200)
            val convConfig = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.1)
            )
            val conversation = engine.createConversation(convConfig)

            conversation.use { conv ->
                val flow = conv.sendMessageAsync(prompt)

                flow.collect { chunk ->
                    chunk?.let {
                        try {
                            if (sb.length < maxOutputChars) sb.append(it)
                        } catch (e: Exception) {
                            CrashLogger.w(TAG, "Failed to append chunk: ${e.message}", e)
                        }
                    }
                }
            }

            val result = sb.toString().trim()
            if (result.isEmpty()) {
                throw IllegalStateException("Translation resulted in empty output. The model may not have generated a response.")
            }
            Translation(translatedText = result)
        } catch (e: OutOfMemoryError) {
            CrashLogger.e(TAG, "Translation failed: Out of memory during text generation", e)
            closeLiveEngine()
            throw IllegalStateException("Translation failed: Device out of memory. Try a smaller input text or restart the app.", e)
        } catch (e: Exception) {
            CrashLogger.e(TAG, "Translation failed: ${e.message}", e)
            throw IllegalStateException("Translation failed: ${e.message}", e)
        }
    }

    companion object {
        const val MODEL_FILENAME = "translategemma-4b-it-int4-generic.litertlm"
        const val MODEL_DIR = "translategemma"
        const val MODEL_SIZE_BYTES = 2_000_000_000L
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/barakplasma/translategemma-4b-it-android-task-quantized/resolve/main/artifacts/int4-generic/translategemma-4b-it-int4-generic.litertlm"
        const val MAX_INPUT_CHARS = 1000

        fun getModelFile(context: Context): File =
            File(context.getExternalFilesDir(null), "$MODEL_DIR/$MODEL_FILENAME")

        fun splitIntoChunks(text: String, maxCharsPerChunk: Int = MAX_INPUT_CHARS): List<String> {
            if (text.length <= maxCharsPerChunk) return listOf(text)

            val bi = BreakIterator.getSentenceInstance()
            bi.setText(text)

            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            var sentenceStart = 0
            var boundary = bi.next()

            while (boundary != BreakIterator.DONE) {
                val sentence = text.substring(sentenceStart, boundary)
                if (current.length + sentence.length > maxCharsPerChunk && current.isNotEmpty()) {
                    chunks.add(current.toString().trim())
                    current.clear()
                }
                if (sentence.length > maxCharsPerChunk) {
                    // Single sentence too long — hard-split
                    var s = 0
                    while (s < sentence.length) {
                        chunks.add(sentence.substring(s, minOf(s + maxCharsPerChunk, sentence.length)).trim())
                        s += maxCharsPerChunk
                    }
                } else {
                    current.append(sentence)
                }
                sentenceStart = boundary
                boundary = bi.next()
            }

            if (current.isNotEmpty()) chunks.add(current.toString().trim())
            return chunks.filter { it.isNotBlank() }
        }

        private val SUPPORTED_LANGUAGES = listOf(
            Language("af", "Afrikaans"),
            Language("ar", "Arabic"),
            Language("be", "Belarusian"),
            Language("bg", "Bulgarian"),
            Language("bn", "Bengali"),
            Language("ca", "Catalan"),
            Language("cs", "Czech"),
            Language("cy", "Welsh"),
            Language("da", "Danish"),
            Language("de", "German"),
            Language("el", "Greek"),
            Language("en", "English"),
            Language("es", "Spanish"),
            Language("et", "Estonian"),
            Language("eu", "Basque"),
            Language("fa", "Persian"),
            Language("fi", "Finnish"),
            Language("fr", "French"),
            Language("ga", "Irish"),
            Language("gl", "Galician"),
            Language("gu", "Gujarati"),
            Language("he", "Hebrew"),
            Language("hi", "Hindi"),
            Language("hr", "Croatian"),
            Language("hu", "Hungarian"),
            Language("hy", "Armenian"),
            Language("id", "Indonesian"),
            Language("is", "Icelandic"),
            Language("it", "Italian"),
            Language("ja", "Japanese"),
            Language("ka", "Georgian"),
            Language("kk", "Kazakh"),
            Language("km", "Khmer"),
            Language("kn", "Kannada"),
            Language("ko", "Korean"),
            Language("lt", "Lithuanian"),
            Language("lv", "Latvian"),
            Language("mk", "Macedonian"),
            Language("ml", "Malayalam"),
            Language("mn", "Mongolian"),
            Language("mr", "Marathi"),
            Language("ms", "Malay"),
            Language("mt", "Maltese"),
            Language("my", "Burmese"),
            Language("nb", "Norwegian"),
            Language("nl", "Dutch"),
            Language("pl", "Polish"),
            Language("pt", "Portuguese"),
            Language("ro", "Romanian"),
            Language("ru", "Russian"),
            Language("sk", "Slovak"),
            Language("sl", "Slovenian"),
            Language("sq", "Albanian"),
            Language("sr", "Serbian"),
            Language("sv", "Swedish"),
            Language("sw", "Swahili"),
            Language("ta", "Tamil"),
            Language("te", "Telugu"),
            Language("th", "Thai"),
            Language("tr", "Turkish"),
            Language("uk", "Ukrainian"),
            Language("ur", "Urdu"),
            Language("uz", "Uzbek"),
            Language("vi", "Vietnamese"),
            Language("zh", "Chinese (Simplified)"),
            Language("zh-TW", "Chinese (Traditional)"),
        )
    }
}
