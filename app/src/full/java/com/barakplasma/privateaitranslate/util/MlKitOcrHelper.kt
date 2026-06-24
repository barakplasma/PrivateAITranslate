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

package com.barakplasma.privateaitranslate.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object MlKitOcrHelper {
    // Languages with no ML Kit v2 recognizer — must use Tesseract instead.
    private val UNSUPPORTED_SCRIPTS = setOf(
        "ar", "fa", "he", "hy",
        "bn", "gu", "ka", "km", "kn", "ml", "my", "ta", "te", "th", "ur"
    )

    fun supportsLanguage(langCode: String): Boolean = langCode !in UNSUPPORTED_SCRIPTS

    private fun recognizerFor(langCode: String): TextRecognizer = when (langCode) {
        "zh", "zh-TW" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        "hi", "mr" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private suspend fun runOcr(bitmap: Bitmap, langCode: String): Pair<String, Map<Rect, String>>? {
        val recognizer = recognizerFor(langCode)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val regions = mutableMapOf<Rect, String>()
            val fullText = StringBuilder()

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val rect = line.boundingBox ?: continue
                    val text = line.text
                    if (text.isNotBlank()) {
                        regions[rect] = text
                        if (fullText.isNotEmpty()) fullText.append('\n')
                        fullText.append(text)
                    }
                }
            }

            fullText.toString() to regions
        } catch (_: Exception) {
            null
        } finally {
            recognizer.close()
        }
    }

    // Maps an identified BCP-47 language code to a script-specific recognizer lang code,
    // or null if the Latin recognizer already handles it.
    private fun scriptRecognizerLang(langCode: String): String? = when {
        langCode.startsWith("zh") -> "zh"
        langCode == "ja" -> "ja"
        langCode == "ko" -> "ko"
        langCode in setOf("hi", "mr") -> "hi"
        else -> null
    }

    private suspend fun identifyScript(text: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            val detected = client.identifyLanguage(text).await()
            if (detected == "und") null else scriptRecognizerLang(detected)
        } catch (_: Exception) {
            null
        } finally {
            client.close()
        }
    }

    suspend fun getText(bitmap: Bitmap, langCode: String = ""): Pair<String, Map<Rect, String>>? {
        if (langCode.isNotEmpty()) return runOcr(bitmap, langCode)

        // Auto-detect: run Latin OCR first, then use Language ID to re-run with a
        // script-specific recognizer if the text is Chinese, Japanese, Korean, or Devanagari.
        val latinResult = runOcr(bitmap, "")
        val scriptCode = latinResult?.first?.takeIf { it.isNotBlank() }?.let { identifyScript(it) }
        return if (scriptCode != null) runOcr(bitmap, scriptCode) ?: latinResult else latinResult
    }
}
