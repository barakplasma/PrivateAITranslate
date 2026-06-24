package com.barakplasma.privateaitranslate.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MLKitTranslation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import net.youapps.translation_engines.ApiKeyState
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.Language
import net.youapps.translation_engines.Translation
import net.youapps.translation_engines.TranslationEngine
import java.util.Locale

class MLKitEngine(
    settingsProvider: EngineSettingsProvider,
) : TranslationEngine(settingsProvider) {
    override val name = "Google ML Kit (On-Device)"
    override val defaultUrl = ""
    override val urlModifiable = false
    override val apiKeyState = ApiKeyState.DISABLED
    override val autoLanguageCode = null
    override val supportsAudio = false
    override val isOnDevice = true

    private var cachedTranslator: Translator? = null
    private var cachedSource: String? = null
    private var cachedTarget: String? = null

    override fun createOrRecreate(): TranslationEngine = apply {
        synchronized(this) {
            cachedTranslator?.close()
            cachedTranslator = null
            cachedSource = null
            cachedTarget = null
        }
    }

    override suspend fun getLanguages(): List<Language> =
        TranslateLanguage.getAllLanguages().map { code ->
            Language(code, Locale.Builder().setLanguage(code).build().displayLanguage)
        }.sortedBy { it.name }

    override suspend fun translate(
        query: String,
        source: String,
        target: String,
    ): Translation {
        require(source.isNotEmpty() && source != "auto") {
            "Google ML Kit does not support automatic source language detection. Please select a specific source language."
        }
        require(target.isNotEmpty() && target != "auto") {
            "A specific target language must be selected."
        }

        val translator =
            synchronized(this) {
                if (cachedSource == source && cachedTarget == target && cachedTranslator != null) {
                    cachedTranslator!!
                } else {
                    cachedTranslator?.close()
                    val options =
                        TranslatorOptions
                            .Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build()
                    val newTranslator = MLKitTranslation.getClient(options)
                    cachedTranslator = newTranslator
                    cachedSource = source
                    cachedTarget = target
                    newTranslator
                }
            }

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return Translation(translator.translate(query).await())
    }
}
