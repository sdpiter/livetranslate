package io.github.sdpiter.livetranslate.mt

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class MlKitTranslator {

    private var translator: Translator? = null
    private var fromTag = "en"
    private var toTag = "ru"

    private fun mlLang(tag: String): String {
        val t = tag.lowercase()
        val base = if (t.contains("-")) t.substringBefore("-") else t
        return TranslateLanguage.fromLanguageTag(base) ?: TranslateLanguage.ENGLISH
    }

    suspend fun ensure(fromLang: String, toLang: String) {
        val src = mlLang(fromLang)
        val dst = mlLang(toLang)
        if (translator != null && src == fromTag && dst == toTag) return
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(dst)
            .build()
        val tr = Translation.getClient(options)
        tr.downloadModelIfNeeded().await()
        translator = tr
        fromTag = src; toTag = dst
    }

    suspend fun translateSafe(text: String): String {
        val tr = translator ?: return text
        return try {
            tr.translate(text).await()
        } catch (_: CancellationException) {
            ""
        } catch (_: Exception) {
            ""
        }
    }

    fun close() { translator?.close(); translator = null }
}
