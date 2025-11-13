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
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class VoskEngine(
    private val context: Context,
    private val onError: ((Throwable) -> Unit)? = null,
    private val onInfo: ((String) -> Unit)? = null
) {

    val results = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    fun start(languageTag: String) {
        stop()
        job = scope.launch {
            try {
                val lang = if (languageTag.lowercase().startsWith("ru")) "ru" else "en"
                onInfo?.invoke("vosk.ensureModel:$lang")
                val modelDir = ensureModel(lang)
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, sampleRate.toFloat())

                var source = MediaRecorder.AudioSource.VOICE_RECOGNITION
                var record = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    try { record.release() } catch (_: Exception) {}
                    source = MediaRecorder.AudioSource.MIC
                    record = AudioRecord(
                        source,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("AudioRecord not initialized (MIC)")
                    }
                }
                recorder = record
                onInfo?.invoke("vosk.record.start($source)")
                record.startRecording()

                val buffer = ByteArray(4096)
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        val isFinal = recognizer?.acceptWaveForm(buffer, n) == true
                        if (isFinal) {
                            val json = recognizer?.result ?: ""
                            val text = parseText(json, "text")
                            if (text.isNotBlank()) emit(text)
                        } else {
                            val json = recognizer?.partialResult ?: ""
                            val text = parseText(json, "partial")
                            if (text.isNotBlank()) emit(text)
                        }
                    }
                }
            } catch (e: Throwable) {
                onError?.invoke(e)
            }
        }
    }

    private suspend fun emit(text: String) {
        results.emit(text)
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
        onInfo?.invoke("vosk.stop")
    }

    suspend fun stopAndJoin() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        job?.cancelAndJoin()
        job = null
        onInfo?.invoke("vosk.stopAndJoin")
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

        val tmpZip = File(modelsDir, "model.zip")
        if (!tmpZip.exists()) {
            URL(url).openStream().use { input ->
                FileOutputStream(tmpZip).use { output -> input.copyTo(output) }
            }
        }

        unzip(tmpZip, modelsDir)
        tmpZip.delete()

        val nested = File(modelsDir, topFolder)
        if (nested.exists() && isDirectory) {
            nested.listFiles()?.forEach { f -> f.renameTo(File(modelsDir, f.name)) }
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
                if (entry.isDirectory) outFile.mkdirs()
                else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
