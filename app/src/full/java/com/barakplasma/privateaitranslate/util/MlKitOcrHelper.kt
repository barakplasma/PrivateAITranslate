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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object MlKitOcrHelper {
    // Languages whose scripts the bundled Latin recognizer cannot read.
    // These must go through Tesseract instead.
    private val NON_LATIN_SCRIPTS = setOf(
        "ar", "bn", "fa", "gu", "he", "hi", "hy", "ja",
        "ka", "km", "kn", "ko", "ml", "mr", "my", "ta",
        "te", "th", "ur", "zh", "zh-TW"
    )

    fun supportsLanguage(langCode: String): Boolean = langCode !in NON_LATIN_SCRIPTS

    suspend fun getText(bitmap: Bitmap): Pair<String, Map<Rect, String>>? {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
}
