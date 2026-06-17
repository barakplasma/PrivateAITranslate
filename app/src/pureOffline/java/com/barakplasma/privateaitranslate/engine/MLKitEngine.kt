package com.barakplasma.privateaitranslate.engine

import net.youapps.translation_engines.ApiKeyState
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.Language
import net.youapps.translation_engines.Translation
import net.youapps.translation_engines.TranslationEngine

class MLKitEngine(settingsProvider: EngineSettingsProvider) : TranslationEngine(settingsProvider) {
    override val name = "Google ML Kit (On-Device)"
    override val defaultUrl = ""
    override val urlModifiable = false
    override val apiKeyState = ApiKeyState.DISABLED
    override val autoLanguageCode = null
    override val supportsAudio = false
    // isOnDevice = false so getAvailableEngines() filters this stub out entirely.
    override val isOnDevice = false

    override fun createOrRecreate(): TranslationEngine = this
    override suspend fun getLanguages(): List<Language> = emptyList()
    override suspend fun translate(query: String, source: String, target: String): Translation =
        throw UnsupportedOperationException("ML Kit is not included in this build")
}
