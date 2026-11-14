package com.communityfinder.app

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** 해외 커뮤니티 여부: 수집기에서 id를 reddit-/lemmy-/jp- 로 시작하게 정해둠 */
fun isGlobal(sourceId: String) = sourceId.startsWith("reddit") || sourceId.startsWith("lemmy") || sourceId.startsWith("jp-")

/** 원문 언어 */
fun langOf(sourceId: String) = if (sourceId.startsWith("jp-")) "ja" else "en"

private val FOREIGN = Regex("[A-Za-z]{2,}|[\\u3040-\\u30ff\\u4e00-\\u9fff]")

/** 번역할 글자(영어 2자 이상 또는 일본어)가 들어 있는지 */
fun hasForeignText(text: String) = FOREIGN.containsMatchIn(text)

/**
 * 한국어 번역.
 *  1순위: Gemini (설정에 무료 API 키를 넣었을 때) — 밈·속어까지 자연스럽게
 *  2순위: Groq (무료 API 키를 넣었을 때) — Gemini 한도가 떨어지면 자동 전환
 *  3순위: 휴대폰 내장 번역(ML Kit) — 키가 없거나, Gemini 한도를 다 썼거나, 오류일 때 자동 전환. 무료·토큰 없음
 */
object Translator {
    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val ready = HashSet<String>()

    private fun client(lang: String) = synchronized(clients) {
        clients.getOrPut(lang) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(if (lang == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.KOREAN).build(),
            )
        }
    }

    /** 내장 번역 모델 준비 (없으면 내려받기, 언어당 약 30MB) */
    private suspend fun prepareLocal(lang: String): Boolean {
        if (lang in ready) return true
        val ok = runCatching {
            suspendCancellableCoroutine<Boolean> { c ->
                client(lang).downloadModelIfNeeded().addOnSuccessListener { c.resume(true) }.addOnFailureListener { c.resume(false) }
            }
        }.getOrDefault(false)
        if (ok) ready += lang
        return ok
    }

    private suspend fun local(text: String, lang: String): String {
        if (!hasForeignText(text)) return text
        return runCatching {
            suspendCancellableCoroutine<String?> { c ->
                client(lang).translate(text).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
            }
        }.getOrNull() ?: text
    }

    /** 지금 쓰이는 엔진 이름 (화면 표시용) */
    fun engineName(ctx: Context): String {
        val p = Prefs(ctx)
        return when {
            Gemini.usable(p) -> "Gemini"
            Groq.usable(p) -> "Groq"
            else -> "휴대폰 내장 번역"
        }
    }

    /** 여러 문장을 한 번에 번역. 결과는 입력과 같은 순서·길이 */
    suspend fun translateAll(ctx: Context, texts: List<String>, lang: String, context: String = ""): List<String> {
        if (texts.none { hasForeignText(it) }) return texts
        // Gemini → (한도 소진·오류 시) Groq → (그래도 안 되면) 휴대폰 내장 번역
        Gemini.translate(ctx, texts, context)?.let { return it }
        Groq.translate(ctx, texts, context)?.let { return it }
        if (!prepareLocal(lang)) return texts
        return texts.map { local(it, lang) }
    }

    // ---- 제목 번역 캐시 (글 id → 한국어 제목). 엔진별로 따로 저장해 키를 넣으면 다시 번역됨 ----
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, if (Prefs(ctx).let { it.geminiKey.isNotBlank() || it.groqKey.isNotBlank() }) "title-ko-gemini.json" else "title-ko.json")

    fun cachedTitles(ctx: Context): Map<String, String> = runCatching {
        val o = JSONObject(cacheFile(ctx).readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** 해외 글 제목을 번역해 캐시에 저장하고 전체 맵 반환 */
    suspend fun translateTitles(ctx: Context, posts: List<Post>): Map<String, String> {
        val cache = cachedTitles(ctx).toMutableMap()
        val todo = posts.filter { isGlobal(it.source) && it.id !in cache }
        if (todo.isEmpty()) return cache
        todo.groupBy { langOf(it.source) }.forEach { (lang, list) ->
            val ko = translateAll(ctx, list.map { it.title }, lang, "각 항목은 서로 다른 게시글의 제목이다.")
            list.forEachIndexed { i, p -> if (ko[i] != p.title) cache[p.id] = ko[i] }
        }
        val ids = posts.map { it.id }.toSet()
        runCatching { cacheFile(ctx).writeText(JSONObject(cache.filterKeys { it in ids } as Map<*, *>).toString()) }
        return cache
    }
}
