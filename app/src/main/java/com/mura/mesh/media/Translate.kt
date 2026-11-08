package com.mura.mesh.media

import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponseValue
import android.view.translation.TranslationSpec
import com.mura.mesh.data.Db
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Message translation that never leaves the phone.
 *
 * Uses only Android's own on-device translation service (Android 12+, on phones whose maker ships
 * one, e.g. Pixel's Live Translate). Mura downloads nothing and talks to no server for it — Mura
 * deliberately has no third-party translator, because those contact their maker over the normal
 * internet, which Mura never uses. Language packs are managed by Android itself.
 *
 * Language detection uses Android's on-device TextClassifier. Translations are stored only in this
 * phone's encrypted database, and are never sent to anyone.
 */
object Translate {

    const val ENGINE = "tr_engine"            // system | mlkit
    const val MY_LANG = "tr_lang"             // BCP-47 language, default = phone language
    fun autoKey(chat: String) = "tr_auto:$chat"

    class Unavailable(msg: String) : Exception(msg)

    data class Lang(val tag: String, val name: String, val state: Int)

    /** Pack states, shared by both engines. */
    const val READY = 3
    const val DOWNLOADABLE = 1
    const val DOWNLOADING = 2
    const val UNSUPPORTED = 4

    private val exec = Executors.newSingleThreadExecutor()

    fun engine(@Suppress("UNUSED_PARAMETER") db: Db): String = "system"
    fun myLanguage(db: Db): String = db.getSetting(MY_LANG, "").ifBlank { Locale.getDefault().language }
    fun displayName(tag: String): String = Locale.forLanguageTag(tag).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }.ifBlank { tag }

    fun systemAvailable(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && ctx.getSystemService(TranslationManager::class.java) != null

    // ------------------------------------------------------------------ detection

    /** On-device language detection. Returns a language tag, or null if unsure. */
    suspend fun detect(ctx: Context, text: String): String? = withContext(Dispatchers.Default) {
        if (text.isBlank() || Build.VERSION.SDK_INT < 29) return@withContext null
        try {
            val tc = ctx.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return@withContext null
            val r: TextLanguage = tc.detectLanguage(TextLanguage.Request.Builder(text.take(500)).build())
            if (r.localeHypothesisCount == 0) return@withContext null
            val top = r.getLocale(0)
            if (r.getConfidenceScore(top) < 0.5f) null else top.language
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ translate

    /** Translates [text] into [to]. [from] is detected when null. Returns (sourceLang, translation). */
    suspend fun translate(ctx: Context, db: Db, text: String, to: String, from: String? = null): Pair<String, String> {
        val src = from ?: detect(ctx, text) ?: throw Unavailable("Couldn't tell which language this is.")
        if (src == to) return src to text
        val out = systemTranslate(ctx, text, src, to)
        return src to out
    }

    private suspend fun systemTranslate(ctx: Context, text: String, from: String, to: String): String {
        if (Build.VERSION.SDK_INT < 31) throw Unavailable("On-device translation needs Android 12 or newer.")
        val tm = ctx.getSystemService(TranslationManager::class.java) ?: throw Unavailable("This phone has no on-device translator.")
        val srcSpec = TranslationSpec(ULocale(from), TranslationSpec.DATA_FORMAT_TEXT)
        val dstSpec = TranslationSpec(ULocale(to), TranslationSpec.DATA_FORMAT_TEXT)
        val cap = withContext(Dispatchers.IO) {
            tm.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)
                .firstOrNull { it.sourceSpec.locale.language == from && it.targetSpec.locale.language == to }
        }
        when (cap?.state) {
            TranslationCapability.STATE_ON_DEVICE -> {}
            TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD, TranslationCapability.STATE_DOWNLOADING ->
                throw Unavailable("${displayName(from)} → ${displayName(to)} isn't downloaded yet. Get it in Settings › Translation.")
            else -> throw Unavailable("Your phone's translator doesn't support ${displayName(from)} → ${displayName(to)}.")
        }
        return withTimeout(20_000) {
            val translator = suspendCancellableCoroutine { cont ->
                tm.createOnDeviceTranslator(TranslationContext.Builder(srcSpec, dstSpec).build(), exec) { t ->
                    if (t == null) cont.resumeWithException(Unavailable("Couldn't start the on-device translator.")) else cont.resume(t)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    val req = TranslationRequest.Builder().setTranslationRequestValues(listOf(TranslationRequestValue.forText(text))).build()
                    translator.translate(req, cancel, exec) { resp ->
                        val v = resp.translationResponseValues.let { if (it.size() > 0) it.valueAt(0) else null }
                        if (v != null && v.statusCode == TranslationResponseValue.STATUS_SUCCESS && v.text != null) cont.resume(v.text.toString())
                        else cont.resumeWithException(Unavailable("Translation failed."))
                    }
                }
            } finally { translator.destroy() }
        }
    }

    // ------------------------------------------------------------------ language packs

    /** Languages and their state for the Settings screen, for the current engine. */
    suspend fun languages(ctx: Context, db: Db): List<Lang> = withContext(Dispatchers.IO) {
        val me = myLanguage(db)
        run {
            run {
                if (Build.VERSION.SDK_INT < 31) return@withContext emptyList()
                val tm = ctx.getSystemService(TranslationManager::class.java) ?: return@withContext emptyList()
                // A language counts as ready when it can be translated into my language on the device.
                val caps = tm.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT)
                    .filter { it.targetSpec.locale.language == me }
                caps.groupBy { it.sourceSpec.locale.language }.filterKeys { it != me }.map { (lang, list) ->
                    val st = list.maxOf { c ->
                        when (c.state) {
                            TranslationCapability.STATE_ON_DEVICE -> READY
                            TranslationCapability.STATE_DOWNLOADING -> DOWNLOADING
                            TranslationCapability.STATE_NOT_AVAILABLE -> 0
                            else -> DOWNLOADABLE
                        }
                    }
                    Lang(lang, displayName(lang), if (st == 0) UNSUPPORTED else st)
                }
            }
        }.filter { it.state != UNSUPPORTED }.sortedWith(compareBy({ it.state != READY }, { it.name }))
    }

    /** System engine: open Android's own screen for downloading language packs. */
    fun systemSettingsIntent(ctx: Context): android.app.PendingIntent? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(TranslationManager::class.java)?.onDeviceTranslationSettingsActivityIntent else null

}
