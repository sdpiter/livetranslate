package io.github.sdpiter.livetranslate.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class VoskEngine(private val context: Context) {

    val results = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null

    private val sampleRate = 16000
    private val bufSize by lazy {
        AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
    }

    fun start(langTag: String) {
        stop()
        job = scope.launch {
            val lang = if (langTag.lowercase().startsWith("ru")) "ru" else "en"
            val modelDir = ensureModel(lang) // загрузит/распакует при необходимости
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, sampleRate.toFloat())

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            recorder = record
            record.startRecording()

            val buffer = ByteArray(4096)
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    val isFinal = recognizer?.acceptWaveForm(buffer, n) == true
                    if (isFinal) {
                        val json = recognizer?.result().orEmpty()
                        val text = parseText(json, "text")
                        if (text.isNotBlank()) emit(text)
                    } else {
                        val json = recognizer?.partialResult().orEmpty()
                        val text = parseText(json, "partial")
                        if (text.isNotBlank()) emit(text)
                    }
                }
            }
        }
    }

    private suspend fun emit(text: String) {
        withContext(Dispatchers.Main) { results.emit(text) }
    }

    fun stop() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        job?.cancel()
        job = null
    }

    private fun parseText(json: String, key: String): String =
        try { JSONObject(json).optString(key).orEmpty() } catch (_: Exception) { "" }

    private suspend fun ensureModel(lang: String): File = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models/vosk/$lang")
        val readyFlag = File(modelsDir, ".ready")
        if (readyFlag.exists() && modelsDir.isDirectory && modelsDir.listFiles()?.isNotEmpty() == true) {
            return@withContext modelsDir
        }
        modelsDir.mkdirs()

        val (url, topFolder) = when (lang) {
            "ru" -> "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" to "vosk-model-small-ru-0.22"
            else -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" to "vosk-model-small-en-us-0.15"
        }

        // Скачиваем zip
        val tmpZip = File(modelsDir, "model.zip")
        if (!tmpZip.exists()) {
            URL(url).openStream().use { input ->
                FileOutputStream(tmpZip).use { output -> input.copyTo(output) }
            }
        }

        // Распаковываем в models/vosk/<lang>/
        unzip(tmpZip, modelsDir)
        tmpZip.delete()

        // В некоторых архивах внутри есть верхняя папка — перенесём содержимое наверх
        val nested = File(modelsDir, topFolder)
        if (nested.exists() && nested.isDirectory) {
            nested.listFiles()?.forEach { f ->
                f.renameTo(File(modelsDir, f.name))
            }
            nested.delete()
        }

        readyFlag.writeText("ok")
        return@withContext modelsDir
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
