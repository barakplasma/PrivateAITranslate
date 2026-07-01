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
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
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

    @Volatile private var activeConversation: AutoCloseable? = null
    @Volatile private var engineValid = false
    @Volatile private var visionAvailable = false
    @Volatile var initializedBackendName: String? = null
        private set
    val isVisionAvailable: Boolean
        get() = visionAvailable
    private var backendAvailability: MutableMap<String, Boolean> = mutableMapOf()

    override fun createOrRecreate(): TranslationEngine = apply {
        closeLiveEngine()
    }

    @Synchronized
    private fun closeActiveConversation() {
        try {
            activeConversation?.close()
        } catch (e: Exception) {
            CrashLogger.w(TAG, "Failed to close stale conversation: ${e.message}", e)
        } finally {
            activeConversation = null
        }
    }

    @Synchronized
    private fun closeLiveEngine() {
        // Signal before any native teardown so in-flight collect() loops can exit via
        // CancellationException rather than hitting native code on a freed engine/session.
        engineValid = false
        // Close active conversation first — closing the Engine while a session is open
        // can orphan the native session, causing FAILED_PRECONDITION on the next createConversation.
        closeActiveConversation()
        try {
            liveEngine?.close()
        } catch (e: Exception) {
            CrashLogger.e(TAG, "Failed to close engine: ${e.message}", e)
        }
        liveEngine = null
        visionAvailable = false
        initializedBackendName = null
    }

    // Persists a flag synchronously before GPU Engine init so that a SIGSEGV (which kills the
    // process without going through any Java handler) is detected on the next launch.
    private fun isGpuCrashGuardSet(): Boolean =
        appContext.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            .getBoolean(GPU_CRASH_GUARD_KEY, false)

    private fun setGpuCrashGuard(active: Boolean) {
        appContext.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(GPU_CRASH_GUARD_KEY, active)
            .commit() // synchronous — must persist before the potential native crash
    }

    // Creates the Backend and caches availability in one step, avoiding double instantiation.
    private fun tryCreateBackend(backendName: String): Backend? {
        if (backendAvailability[backendName] == false) return null
        return try {
            when (backendName) {
                "GPU" -> {
                    if (isGpuCrashGuardSet()) {
                        CrashLogger.w(TAG, "GPU backend disabled: previous initialization caused a crash. Falling back to CPU.")
                        backendAvailability["GPU"] = false
                        return null
                    }
                    Backend.GPU()
                }
                else -> Backend.CPU()
            }.also { backendAvailability[backendName] = true }
        } catch (e: UnsatisfiedLinkError) {
            CrashLogger.w(TAG, "Backend '$backendName' not available: ${e.message}")
            backendAvailability[backendName] = false
            null
        } catch (e: UnsupportedOperationException) {
            CrashLogger.w(TAG, "Backend '$backendName' not supported on this device: ${e.message}")
            backendAvailability[backendName] = false
            null
        } catch (e: Exception) {
            CrashLogger.w(TAG, "Backend '$backendName' failed to create: ${e.message}", e)
            backendAvailability[backendName] = false
            null
        }
    }

    private fun getBackendWithFallback(): Pair<String, Backend> {
        val selectedBackend = getSelectedModel() ?: "CPU"
        // Priority order: selected backend first, then the other.
        // CPU-first default prevents fallthrough to GPU which causes SIGSEGV on unsupported devices.
        val order = listOf(selectedBackend) + listOf("CPU", "GPU").filter { it != selectedBackend }

        for (backendName in order) {
            val backend = tryCreateBackend(backendName) ?: continue
            if (backendName != selectedBackend) {
                CrashLogger.w(TAG, "Falling back from '$selectedBackend' to '$backendName'")
            } else {
                CrashLogger.i(TAG, "Using selected backend: $backendName")
            }
            return Pair(backendName, backend)
        }

        error("All translation backends failed. Device may not support TranslateGemma.")
    }

    @Synchronized
    @Suppress("UseCheckOrError") // catch-rethrow blocks need cause parameter; error() does not support it
    private fun getOrCreateEngine(): Engine {
        liveEngine?.let { return it }

        val modelFile = getModelFile(appContext)
        check(modelFile.exists()) {
            "TranslateGemma model not downloaded. Expected location: ${modelFile.absolutePath}. Open Settings → TranslateGemma to download or import it."
        }

        // Validate model file size (minimum ~1.5GB for 2GB quantized model)
        val minModelSize = 1_500_000_000L
        val modelSizeGB = String.format(java.util.Locale.ROOT, "%.2f", modelFile.length() / 1_000_000_000.0)
        check(modelFile.length() >= minModelSize) {
            "TranslateGemma model appears corrupted or incomplete ($modelSizeGB GB, expected >1.5 GB). Delete and re-download."
        }

        var backendName = "unknown"
        return try {
            val (name, backend) = getBackendWithFallback()
            backendName = name
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = backend,
                maxNumImages = 1,
                cacheDir = appContext.cacheDir.absolutePath
            )
            CrashLogger.i(TAG, "Initializing engine ($backendName) with model: ${modelFile.absolutePath} ($modelSizeGB GB)")
            // Set the crash guard before native GPU init — if Engine(config) causes a SIGSEGV the
            // process is killed without any Java handler running, so this flag persists to the next
            // launch and disables GPU automatically.
            if (backendName == "GPU") setGpuCrashGuard(true)
            val engine = try {
                val eng = Engine(config)
                try {
                    eng.initialize()
                    visionAvailable = true
                    eng
                } catch (initEx: Exception) {
                    try {
                        eng.close()
                    } catch (closeEx: Exception) {
                        CrashLogger.w(TAG, "Failed to close partially initialized engine: ${closeEx.message}", closeEx)
                    }
                    throw initEx
                }
            } catch (e: Exception) {
                CrashLogger.w(TAG, "Vision initialization failed; retrying text-only engine: ${e.message}", e)
                Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                ).also {
                    it.initialize()
                    visionAvailable = false
                }
            }
            if (backendName == "GPU") setGpuCrashGuard(false) // GPU init succeeded; clear guard
            liveEngine = engine
            engineValid = true
            initializedBackendName = backendName
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

    suspend fun translateImage(imageFile: File, source: String, target: String): Translation {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            error("TranslateGemma requires Android 12 (API 31) or higher")
        }
        check(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }

        val engine = getOrCreateEngine()
        check(visionAvailable) {
            "The installed TranslateGemma model does not include vision support. Download or import the multimodal TranslateGemma bundle."
        }

        val sourceLang = requireExplicitSourceLanguage(source)

        return try {
            val convConfig = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.1)
            )

            closeActiveConversation()
            val conversation = synchronized(this) {
                if (!engineValid) throw CancellationException("Engine was closed before conversation could be created")
                val conv = engine.createConversation(convConfig)
                activeConversation = conv
                conv
            }

            try {
                val result = sendStructuredMessage(
                    conversation,
                    buildStructuredImageMessage(imageFile, sourceLang, target)
                )
                if (!engineValid) throw CancellationException("Engine was closed during image translation")
                return Translation(translatedText = cleanupTranslationOutput(result))
            } finally {
                synchronized(this) {
                    if (activeConversation === conversation) {
                        activeConversation = null
                    }
                }
                try {
                    conversation.close()
                } catch (e: Exception) {
                    CrashLogger.w(TAG, "Failed to close image conversation: ${e.message}", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            CrashLogger.e(TAG, "Image translation failed: out of memory", e)
            closeLiveEngine()
            throw IllegalStateException("Image translation failed: Device out of memory. Try a smaller crop or restart the app.", e)
        } catch (e: Exception) {
            CrashLogger.e(TAG, "Image translation failed: ${e.message}", e)
            throw IllegalStateException("Image translation failed: ${e.message}", e)
        }
    }

    override suspend fun getLanguages(): List<Language> = SUPPORTED_LANGUAGES

    @Suppress("UseCheckOrError") // catch-rethrow blocks need cause parameter; error() does not support it
    override suspend fun translate(query: String, source: String, target: String): Translation {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            error("TranslateGemma requires Android 12 (API 31) or higher")
        }

        val engine = getOrCreateEngine()
        val sourceLang = requireExplicitSourceLanguage(source)

        return try {
            val convConfig = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.1)
            )

            // Close any session left open by a cancelled/interrupted prior translation
            // before creating a new one — the engine only supports one session at a time.
            closeActiveConversation()
            // Hold the same lock as closeLiveEngine() for the check-and-create block so
            // there is no window between validating engineValid and calling the native
            // createConversation() — both run under the same monitor.
            val conversation = synchronized(this) {
                if (!engineValid) throw CancellationException("Engine was closed before conversation could be created")
                val conv = engine.createConversation(convConfig)
                activeConversation = conv
                conv
            }

            try {
                val result = sendStructuredMessage(
                    conversation,
                    buildStructuredTextMessage(query, sourceLang, target)
                )
                if (!engineValid) throw CancellationException("Engine was closed during text translation")
                return Translation(translatedText = cleanupTranslationOutput(result))
            } finally {
                // Only clear the field if it still points to *our* conversation — a concurrent
                // translate() call may have already replaced it with a newer one.
                synchronized(this) {
                    if (activeConversation === conversation) {
                        activeConversation = null
                    }
                }
                try {
                    conversation.close()
                } catch (e: Exception) {
                    CrashLogger.w(TAG, "Failed to close conversation: ${e.message}", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
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
        const val GPU_CRASH_GUARD_KEY = "translategemma_gpu_init_incomplete"
        const val MODEL_FILENAME = "translategemma-4b-it-int4-multimodal.litertlm"
        const val MODEL_DIR = "translategemma"
        const val MODEL_SIZE_BYTES = 2_500_000_000L
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/barakplasma/translategemma-4b-it-android-task-quantized/resolve/main/artifacts/int4-multimodal/translategemma-4b-it-int4-multimodal.litertlm"
        const val MAX_INPUT_CHARS = 1000
        const val MAX_SAFE_CHUNK_CHARS = (MAX_INPUT_CHARS * 8) / 10  // 80% → 800 chars

        fun getModelFile(context: Context): File =
            File(context.getExternalFilesDir(null), "$MODEL_DIR/$MODEL_FILENAME")

        fun splitIntoChunks(text: String, maxCharsPerChunk: Int = MAX_SAFE_CHUNK_CHARS): List<String> {
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

    private fun requireExplicitSourceLanguage(source: String): String {
        if (source.isEmpty() || source == autoLanguageCode) {
            error("TranslateGemma requires a source language. Choose the source language instead of Auto.")
        }
        return source.replace('_', '-')
    }

    private fun buildStructuredTextMessage(text: String, source: String, target: String): JsonObject =
        JsonObject().apply {
            addProperty("role", "user")
            val content = JsonArray()
            content.add(
                JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("source_lang_code", source)
                    addProperty("target_lang_code", target.replace('_', '-'))
                    addProperty("text", text)
                }
            )
            add("content", content)
        }

    private fun buildStructuredImageMessage(imageFile: File, source: String, target: String): JsonObject =
        JsonObject().apply {
            addProperty("role", "user")
            val content = JsonArray()
            content.add(
                JsonObject().apply {
                    addProperty("type", "image")
                    addProperty("path", imageFile.absolutePath)
                    addProperty("image", imageFile.absolutePath)
                    addProperty("source_lang_code", source)
                    addProperty("target_lang_code", target.replace('_', '-'))
                }
            )
            add("content", content)
        }

    private fun sendStructuredMessage(conversation: AutoCloseable, message: JsonObject): String {
        val handleField = Class.forName("com.google.ai.edge.litertlm.Conversation").getDeclaredField("handle")
        handleField.isAccessible = true
        val handle = handleField.getLong(conversation)
        val responseJson = callNativeSendMessage(handle, message.toString())
        val response = JsonParser.parseString(responseJson).asJsonObject
        val content = response.getAsJsonArray("content")
            ?: error("TranslateGemma returned no content: $responseJson")

        val text = buildString {
            content.forEach { item ->
                val obj = item.asJsonObject
                if (obj.get("type")?.asString == "text") {
                    append(obj.get("text")?.asString.orEmpty())
                }
            }
        }.trim()
        if (text.isEmpty()) {
            error("TranslateGemma returned an empty response: $responseJson")
        }
        return text
    }

    private fun cleanupTranslationOutput(text: String): String =
        text.trim().trimEnd('*').trim()

    private fun callNativeSendMessage(handle: Long, messageJson: String): String {
        val jniClass = Class.forName("com.google.ai.edge.litertlm.LiteRtLmJni")
        val instance = jniClass.getField("INSTANCE").get(null)
        val method = jniClass.getDeclaredMethod(
            "nativeSendMessage",
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            Integer::class.java
        )
        method.isAccessible = true
        return method.invoke(instance, handle, messageJson, "{}", null) as String
    }
}
