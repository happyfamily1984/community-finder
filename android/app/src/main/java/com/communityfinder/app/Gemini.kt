package com.communityfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini 번역 (사용자가 구글 AI Studio에서 발급한 무료 API 키 사용).
 * 무료 한도를 다 쓰거나(429) 오류가 나면 null을 돌려주고, 호출한 쪽이 휴대폰 내장 번역으로 넘어감.
 */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val COOLDOWN_MS = 60 * 60_000L
    const val CHUNK = 40

    /** 마지막 실패 사유 (설정 화면 표시용) */
    @Volatile var lastError: String? = null

    const val SYSTEM = "너는 해외 유머 커뮤니티 글을 한국어로 옮기는 번역가다. 입력은 JSON 문자열 배열이고 각 항목은 영어 또는 일본어다. " +
        "각 항목을 한국 인터넷 커뮤니티에서 쓰는 자연스러운 구어체 반말로 번역해라. 밈, 속어, 말장난, 비꼬는 표현은 직역하지 말고 한국 사람이 바로 이해하도록 의역해라. " +
        "고유명사, 닉네임, URL, 숫자는 그대로 둔다. 이미 한국어이거나 번역할 내용이 없는 항목은 그대로 둔다. " +
        "설명 없이, 입력과 같은 길이의 JSON 문자열 배열만 출력해라."

    fun inCooldown(prefs: Prefs) = System.currentTimeMillis() < prefs.geminiCooldownUntil
    fun usable(prefs: Prefs) = prefs.geminiKey.isNotBlank() && !inCooldown(prefs)

    private fun request(method: String, url: String, key: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10000; conn.readTimeout = 30000
        conn.setRequestProperty("x-goog-api-key", key)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** 구글이 돌려준 오류 설명 (원인 파악용) */
    private fun errorMessage(body: String) =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault(body).take(160)

    /** 키로 쓸 수 있는 flash 모델들을 선호 순서대로 (정식 > 미리보기, 최신 버전 우선, 일반 > lite) */
    fun rankModels(listJson: String): List<String> {
        val models = JSONObject(listJson).optJSONArray("models") ?: return emptyList()
        val names = (0 until models.length()).map { models.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") && it.contains("flash") && !Regex("image|tts|live|audio|embedding|exp|thinking|8b|learnlm|native|robotics|computer").containsMatchIn(it) }
        fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return names.sortedWith(
            compareByDescending<String> { !it.contains("preview") }.thenByDescending { version(it) }.thenByDescending { !it.contains("lite") }.thenBy { it.length },
        )
    }

    fun pickModel(listJson: String): String? = rankModels(listJson).firstOrNull()

    /** 시도할 모델 목록: 마지막으로 성공한 모델이 맨 앞 */
    private fun candidates(prefs: Prefs): List<String> {
        if (prefs.geminiModels.isBlank()) {
            val (code, text) = request("GET", "$BASE/models?pageSize=200", prefs.geminiKey)
            if (code != 200) { lastError = "모델 목록 HTTP $code: ${errorMessage(text)}"; return emptyList() }
            prefs.geminiModels = rankModels(text).take(6).joinToString(",")
        }
        val all = prefs.geminiModels.split(',').filter { it.isNotBlank() }
        return (listOf(prefs.geminiModel).filter { it in all } + all).distinct()
    }

    private fun callOnce(model: String, key: String, chunk: List<String>, context: String): Pair<Int, List<String>?> {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM + if (context.isNotEmpty()) " 참고: $context" else ""))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", JSONArray(chunk).toString())))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
        val (code, text) = request("POST", "$BASE/models/$model:generateContent", key, body.toString())
        if (code != 200) { lastError = "HTTP $code ($model): ${errorMessage(text)}"; return code to null }
        val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val joined = parts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }.orEmpty()
        val arr = runCatching { JSONArray(joined) }.getOrNull()
        if (arr == null || arr.length() != chunk.size) { lastError = "응답 형식이 맞지 않음 ($model)"; return 200 to null }
        return 200 to (0 until arr.length()).map { arr.optString(it, chunk[it]) }
    }

    /** 자유 질문 (요약 등). 실패하면 null → 호출한 쪽이 Groq으로 넘어감 */
    suspend fun ask(ctx: Context, system: String, user: String): String? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!usable(prefs)) return@withContext null
        runCatching {
            for (model in candidates(prefs)) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.8))
                val (code, text) = request("POST", "$BASE/models/$model:generateContent", prefs.geminiKey, body.toString())
                if (code == 401 || code == 403) { lastError = "HTTP $code: ${errorMessage(text)}"; return@runCatching null }
                if (code != 200) { lastError = "HTTP $code ($model): ${errorMessage(text)}"; continue }
                val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val out = parts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }?.trim().orEmpty()
                if (out.isNotEmpty()) { prefs.geminiModel = model; lastError = null; return@runCatching out }
            }
            null
        }.getOrElse { lastError = it.message ?: "연결 실패"; null }
    }

    /** texts를 한국어로 번역. 실패하면 null (→ 예비 번역으로 대체) */
    suspend fun translate(ctx: Context, texts: List<String>, context: String = "", force: Boolean = false): List<String>? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (texts.isEmpty()) return@withContext emptyList()
        if (force) prefs.geminiCooldownUntil = 0
        if (!usable(prefs)) return@withContext null
        runCatching {
            val out = ArrayList<String>(texts.size)
            for (chunk in texts.chunked(CHUNK)) {
                var done: List<String>? = null
                var allQuota = true
                // 모델마다 무료 한도가 달라서, 한도가 없거나(429) 못 쓰는 모델(400/404)이면 다음 모델로
                for (model in candidates(prefs)) {
                    val (code, result) = callOnce(model, prefs.geminiKey, chunk, context)
                    if (result != null) { done = result; prefs.geminiModel = model; break }
                    if (code == 401 || code == 403) return@runCatching null // 키 문제: 다른 모델도 소용없음
                    if (code != 429) allQuota = false
                }
                if (done == null) {
                    if (allQuota && prefs.geminiModels.isNotBlank()) {
                        prefs.geminiCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                        lastError = "모든 모델의 무료 한도를 다 썼어요 · 1시간 동안 예비 번역 사용\n(${lastError})"
                    }
                    return@runCatching null
                }
                out += done
            }
            lastError = null
            out as List<String>
        }.getOrElse { lastError = if (it is java.net.SocketTimeoutException) "응답이 너무 늦어요 (30초 초과)" else (it.message ?: "연결 실패"); null }
    }
}
