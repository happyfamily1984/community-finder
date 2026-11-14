package com.communityfinder.app

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** 해외 커뮤니티(영어) 여부: 수집기에서 id를 reddit-/lemmy- 로 시작하게 정해둠 */
fun isGlobal(sourceId: String) = sourceId.startsWith("reddit") || sourceId.startsWith("lemmy")

/**
 * 영어 → 한국어 번역. 구글 ML Kit의 '기기 내 번역'을 사용:
 * 휴대폰 안에서 번역하므로 무료이고 AI 토큰을 쓰지 않음. 처음 한 번 번역 모델(약 30MB)을 내려받음.
 */
object Translator {
    private val client by lazy {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.KOREAN).build(),
        )
    }
    @Volatile private var ready = false

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }.addOnCanceledListener { c.resume(null) }
    }

    /** 번역 모델 준비 (없으면 내려받기). 실패하면 false */
    suspend fun prepare(): Boolean {
        if (ready) return true
        val ok = runCatching { client.downloadModelIfNeeded().let { t -> suspendCancellableCoroutine<Boolean> { c ->
            t.addOnSuccessListener { c.resume(true) }.addOnFailureListener { c.resume(false) }
        } } }.getOrDefault(false)
        ready = ok
        return ok
    }

    private val latin = Regex("[A-Za-z]{2,}")

    /** 영어가 들어 있는 문장만 번역. 실패하면 원문 그대로 */
    suspend fun translate(text: String): String {
        if (!latin.containsMatchIn(text)) return text
        return runCatching { client.translate(text).await() }.getOrNull() ?: text
    }

    // ---- 제목 번역 캐시 (글 id → 한국어 제목) ----
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "title-ko.json")

    fun cachedTitles(ctx: Context): Map<String, String> = runCatching {
        val o = JSONObject(cacheFile(ctx).readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** 해외 글 제목을 번역해 캐시에 저장하고 전체 맵 반환 */
    suspend fun translateTitles(ctx: Context, posts: List<Post>): Map<String, String> {
        val cache = cachedTitles(ctx).toMutableMap()
        val todo = posts.filter { isGlobal(it.source) && it.id !in cache }
        if (todo.isEmpty() || !prepare()) return cache
        todo.forEach { cache[it.id] = translate(it.title) }
        // 지금 피드에 있는 글만 남겨 파일이 계속 커지지 않게
        val ids = posts.map { it.id }.toSet()
        val keep = cache.filterKeys { it in ids }
        runCatching { cacheFile(ctx).writeText(JSONObject(keep as Map<*, *>).toString()) }
        return cache
    }
}
