/*
 * Copyright (c) 2026 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.barakplasma.privateaitranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Debug
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.barakplasma.privateaitranslate.engine.TranslateGemmaEngine
import kotlinx.coroutines.runBlocking
import net.youapps.translation_engines.EngineSettingsProvider
import net.youapps.translation_engines.TranslationEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TranslateGemmaRealDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resultFile by lazy {
        File(
            requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)),
            "translategemma-real-device-result.json"
        )
    }
    private val results = JSONArray()

    @Test
    fun validatesTextAndImageTranslationOnCpuAndGpu() = runBlocking {
        val modelFile = TranslateGemmaEngine.getModelFile(context)
        ensureModelAvailable(modelFile)
        assertTrue("Missing model file at ${modelFile.absolutePath}", modelFile.exists())
        assertTrue(
            "Model file is too small: ${modelFile.length()} bytes",
            modelFile.length() >= TranslateGemmaEngine.MODEL_SIZE_BYTES
        )

        val imageFile = createSpanishTestImage()
        try {
            verifyBackend("CPU", imageFile)
            verifyBackend("GPU", imageFile)
        } finally {
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(
                JSONObject()
                    .put("packageName", context.packageName)
                    .put("device", android.os.Build.DEVICE)
                    .put("model", android.os.Build.MODEL)
                    .put("manufacturer", android.os.Build.MANUFACTURER)
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("results", results)
                    .toString(2)
            )
        }
    }

    private fun ensureModelAvailable(modelFile: File) {
        if (modelFile.exists() && modelFile.length() >= TranslateGemmaEngine.MODEL_SIZE_BYTES) {
            return
        }

        val stagingPath = InstrumentationRegistry.getArguments().getString("MODEL_STAGING_PATH")
        val parent = requireNotNull(modelFile.parentFile) {
            "Model file has no parent directory: ${modelFile.absolutePath}"
        }
        parent.mkdirs()

        if (stagingPath.isNullOrBlank()) {
            downloadModel(modelFile)
            return
        }

        val appCopyError = runCatching {
            File(stagingPath).inputStream().use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }.exceptionOrNull()

        if (modelFile.exists() && modelFile.length() >= TranslateGemmaEngine.MODEL_SIZE_BYTES) {
            return
        }

        val output = runShell(
            listOf(
                "id",
                "ls -l ${shellQuote(stagingPath)}",
                "mkdir -p ${shellQuote(parent.absolutePath)}",
                "cp ${shellQuote(stagingPath)} ${shellQuote(modelFile.absolutePath)}",
                "chmod 644 ${shellQuote(modelFile.absolutePath)}",
                "ls -l ${shellQuote(modelFile.absolutePath)}"
            ).joinToString(" && ") + " 2>&1"
        )

        assertTrue(
            "Missing model file after staging copy from $stagingPath to ${modelFile.absolutePath}. appCopy=${appCopyError?.javaClass?.name}: ${appCopyError?.message}. shell=$output",
            modelFile.exists() && modelFile.length() >= TranslateGemmaEngine.MODEL_SIZE_BYTES
        )
    }

    private fun downloadModel(modelFile: File) {
        val url = InstrumentationRegistry.getArguments().getString("MODEL_DOWNLOAD_URL")
            ?: TranslateGemmaEngine.MODEL_DOWNLOAD_URL
        val partialFile = File(modelFile.parentFile, "${modelFile.name}.partial")
        partialFile.delete()

        val started = System.nanoTime()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PrivateAITranslate-FirebaseTest/1.0")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Model download failed with HTTP $status from $url: ${error.take(500)}")
            }
            connection.inputStream.use { input ->
                partialFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        assertTrue(
            "Downloaded model is too small from $url: ${partialFile.length()} bytes",
            partialFile.length() >= TranslateGemmaEngine.MODEL_SIZE_BYTES
        )
        if (modelFile.exists()) {
            modelFile.delete()
        }
        assertTrue(
            "Downloaded model could not be moved from ${partialFile.absolutePath} to ${modelFile.absolutePath}",
            partialFile.renameTo(modelFile)
        )
        assertTrue(
            "Downloaded model could not be moved to ${modelFile.absolutePath} after ${(System.nanoTime() - started) / 1_000_000_000L}s",
            modelFile.exists() && modelFile.length() >= TranslateGemmaEngine.MODEL_SIZE_BYTES
        )
    }

    private fun runShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return try {
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            descriptor.close()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun verifyBackend(backend: String, imageFile: File) {
        val engine = TranslateGemmaEngine(TestSettingsProvider(backend), context)
        val started = System.nanoTime()
        val result = JSONObject()
            .put("requestedBackend", backend)
            .put("status", "started")
            .put("memoryBefore", memorySnapshot())
        results.put(result)

        try {
            val text = engine.translate(
                query = "Hola mundo. Gracias por probar este modelo.",
                source = "es",
                target = "en"
            ).translatedText
            assertExpectedTranslation("text/$backend", text)

            assertEquals(
                "Requested backend $backend did not initialize as $backend",
                backend,
                engine.initializedBackendName
            )

            val image = engine.translateImage(
                imageFile = imageFile,
                source = "es",
                target = "en"
            ).translatedText
            assertExpectedTranslation("image/$backend", image)
            assertTrue("Vision was not available for $backend", engine.isVisionAvailable)

            result
                .put("status", "passed")
                .put("actualBackend", engine.initializedBackendName)
                .put("visionAvailable", engine.isVisionAvailable)
                .put("textTranslation", text)
                .put("imageTranslation", image)
        } catch (t: Throwable) {
            result
                .put("status", "failed")
                .put("actualBackend", engine.initializedBackendName)
                .put("visionAvailable", engine.isVisionAvailable)
                .put("errorType", t.javaClass.name)
                .put("error", t.message.orEmpty())
            throw t
        } finally {
            engine.createOrRecreate()
            result
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000L)
                .put("memoryAfter", memorySnapshot())
        }
    }

    private fun assertExpectedTranslation(label: String, translatedText: String) {
        val normalized = translatedText.lowercase(Locale.US)
        assertTrue("$label did not contain 'hello': $translatedText", normalized.contains("hello"))
        assertTrue("$label did not contain 'world': $translatedText", normalized.contains("world"))
        assertTrue("$label did not contain 'thank': $translatedText", normalized.contains("thank"))
    }

    private fun createSpanishTestImage(): File {
        val bitmap = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("HOLA MUNDO", 80f, 180f, paint)
        canvas.drawText("GRACIAS", 80f, 330f, paint)

        return File(context.cacheDir, "translategemma-real-device-spanish.png").also { file ->
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
        }
    }

    private fun memorySnapshot(): JSONObject {
        val runtime = Runtime.getRuntime()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        return JSONObject()
            .put("runtimeTotalBytes", runtime.totalMemory())
            .put("runtimeFreeBytes", runtime.freeMemory())
            .put("runtimeMaxBytes", runtime.maxMemory())
            .put("nativeHeapAllocatedBytes", nativeHeap)
    }

    private class TestSettingsProvider(
        private val selectedBackend: String
    ) : EngineSettingsProvider {
        override fun getApiUrl(engine: TranslationEngine): String? = null

        override fun getApiKey(engine: TranslationEngine): String? = null

        override fun getSelectedModel(engine: TranslationEngine): String = selectedBackend
    }
}
