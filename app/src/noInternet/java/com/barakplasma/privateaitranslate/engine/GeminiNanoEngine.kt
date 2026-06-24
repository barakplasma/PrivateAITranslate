package com.barakplasma.privateaitranslate.engine

import com.google.mlkit.genai.prompt.GenerativeModel
import net.youapps.translation_engines.ApiKeyState
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.Language
import net.youapps.translation_engines.Translation
import net.youapps.translation_engines.TranslationEngine

class GeminiNanoEngine(
    settingsProvider: EngineSettingsProvider,
) : TranslationEngine(settingsProvider) {
    override val name = "Gemini Nano (On-Device)"
    override val defaultUrl = ""
    override val urlModifiable = false
    override val apiKeyState = ApiKeyState.DISABLED
    override val autoLanguageCode = "auto"
    override val supportsAudio = false
    override val isOnDevice = true

    private var model: GenerativeModel? = null

    @Suppress("TooGenericExceptionCaught")
    override fun createOrRecreate(): TranslationEngine = apply {
        try {
            model = com.google.mlkit.genai.prompt.Generation.getClient()
        } catch (t: Throwable) {
            // Gemini Nano requires AI Core; unavailable on most devices
            model = null
        }
    }

    override suspend fun getLanguages(): List<Language> = SUPPORTED_LANGUAGES

    override suspend fun translate(
        query: String,
        source: String,
        target: String,
    ): Translation {
        val currentModel = model
        check(currentModel != null) { "Gemini Nano model is not initialized" }

        val sourceName =
            if (source.isEmpty() || source == "auto") {
                "the detected source language"
            } else {
                LANGUAGE_NAMES[source] ?: source
            }
        val targetName = LANGUAGE_NAMES[target] ?: target

        val prompt =
            "Translate the following text from $sourceName to $targetName. " +
                "Output only the translated text with no additional commentary or explanation:\n\n$query"

        val response = currentModel.generateContent(prompt)
        val text =
            response.candidates.firstOrNull()?.text
        check(!text.isNullOrEmpty()) { "Gemini Nano returned an empty response" }

        return Translation(translatedText = text.trim())
    }

    companion object {
        private val SUPPORTED_LANGUAGES =
            listOf(
                Language("ar", "Arabic"),
                Language("bn", "Bengali"),
                Language("bg", "Bulgarian"),
                Language("zh", "Chinese (Simplified)"),
                Language("zh-TW", "Chinese (Traditional)"),
                Language("hr", "Croatian"),
                Language("cs", "Czech"),
                Language("da", "Danish"),
                Language("nl", "Dutch"),
                Language("en", "English"),
                Language("et", "Estonian"),
                Language("fi", "Finnish"),
                Language("fr", "French"),
                Language("de", "German"),
                Language("el", "Greek"),
                Language("gu", "Gujarati"),
                Language("he", "Hebrew"),
                Language("hi", "Hindi"),
                Language("hu", "Hungarian"),
                Language("id", "Indonesian"),
                Language("it", "Italian"),
                Language("ja", "Japanese"),
                Language("ko", "Korean"),
                Language("lv", "Latvian"),
                Language("lt", "Lithuanian"),
                Language("ms", "Malay"),
                Language("ml", "Malayalam"),
                Language("mr", "Marathi"),
                Language("no", "Norwegian"),
                Language("fa", "Persian"),
                Language("pl", "Polish"),
                Language("pt", "Portuguese"),
                Language("ro", "Romanian"),
                Language("ru", "Russian"),
                Language("sr", "Serbian"),
                Language("sk", "Slovak"),
                Language("sl", "Slovenian"),
                Language("es", "Spanish"),
                Language("sw", "Swahili"),
                Language("sv", "Swedish"),
                Language("ta", "Tamil"),
                Language("te", "Telugu"),
                Language("th", "Thai"),
                Language("tr", "Turkish"),
                Language("uk", "Ukrainian"),
                Language("ur", "Urdu"),
                Language("vi", "Vietnamese"),
            )

        private val LANGUAGE_NAMES = SUPPORTED_LANGUAGES.associate { it.code to it.name }
    }
}
