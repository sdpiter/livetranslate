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

class VoskEngine(private val context: Context) {

    val results = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var model: Model? = null

    private val sampleRate = 16000
    private val bufSize by lazy {
        AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
    }

    fun start(languageTag: String) {
        stop()
        job = scope.launch {
            val lang = if (languageTag.lowercase().startsWith("ru")) "ru" else "en"
            val modelDir = ensureModel(lang)
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, sampleRate.toFloat())

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            recorder = rec
            try { rec.startRecording() } catch (e: Exception) { return@launch }

            val buffer = ByteArray(4096)
            while (isActive) {
                val n = try { rec.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                if (n <= 0) continue

                val isFinal = try { recognizer?.acceptWaveForm(buffer, n) == true } catch (_: Exception) { false }
                if (isFinal) {
                    val json = recognizer?.result ?: ""
                    val text = parse(json, "text")
                    if (text.isNotBlank()) results.emit(text)
                } else {
                    val json = recognizer?.partialResult ?: ""
                    val text = parse(json, "partial")
                    if (text.isNotBlank()) results.emit(text)
                }
            }
        }
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
    }

    private fun parse(json: String, key: String): String =
        try { JSONObject(json).optString(key).orEmpty() } catch (_: Exception) { "" }

    private suspend fun ensureModel(lang: String): File = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models/vosk/$lang")
        val flag = File(modelsDir, ".ready")
        if (flag.exists() && modelsDir.isDirectory && (modelsDir.listFiles()?.isNotEmpty() == true)) return@withContext modelsDir

        modelsDir.mkdirs()
        val (url, topFolder) = when (lang) {
            "ru" -> "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" to "vosk-model-small-ru-0.22"
            else -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" to "vosk-model-small-en-us-0.15"
        }
        val tmp = File(modelsDir, "model.zip")
        if (!tmp.exists()) URL(url).openStream().use { it.copyTo(FileOutputStream(tmp)) }
        unzip(tmp, modelsDir); tmp.delete()
        val nested = File(modelsDir, topFolder)
        if (nested.exists()) {
            nested.listFiles()?.forEach { f -> f.renameTo(File(modelsDir, f.name)) }
            nested.delete()
        }
        flag.writeText("ok")
        modelsDir
    }

    private fun unzip(zip: File, out: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val target = File(out, e.name)
                if (e.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
    }
}
