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
        conn.connectTimeout = 15000; conn.readTimeout = 60000
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

    /** 키로 쓸 수 있는 모델 중 가장 최신 flash 모델 선택 (모델 이름이 바뀌어도 동작하도록) */
    fun pickModel(listJson: String): String? {
        val models = JSONObject(listJson).optJSONArray("models") ?: return null
        val names = (0 until models.length()).map { models.getJSONObject(it) }
            .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.getString(it) == "generateContent" } } == true }
            .map { it.getString("name").removePrefix("models/") }
            .filter { it.startsWith("gemini") && it.contains("flash") && !Regex("image|tts|live|audio|embedding|exp|thinking|8b|learnlm|native|robotics|computer").containsMatchIn(it) }
        fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return names.sortedWith(
            compareByDescending<String> { !it.contains("preview") }.thenByDescending { version(it) }.thenByDescending { !it.contains("lite") }.thenBy { it.length },
        ).firstOrNull()
    }

    private fun model(prefs: Prefs): String? {
        prefs.geminiModel.takeIf { it.isNotBlank() }?.let { return it }
        val (code, text) = request("GET", "$BASE/models?pageSize=200", prefs.geminiKey)
        if (code != 200) { lastError = "모델 목록 HTTP $code"; return null }
        return pickModel(text)?.also { prefs.geminiModel = it }
    }

    /** texts를 한국어로 번역. 실패하면 null (→ 내장 번역으로 대체) */
    suspend fun translate(ctx: Context, texts: List<String>, context: String = ""): List<String>? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (texts.isEmpty()) return@withContext emptyList()
        if (!usable(prefs)) return@withContext null
        runCatching {
            val model = model(prefs) ?: return@runCatching null
            val out = ArrayList<String>(texts.size)
            for (chunk in texts.chunked(CHUNK)) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM + if (context.isNotEmpty()) " 참고: $context" else ""))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", JSONArray(chunk).toString())))))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
                val (code, text) = request("POST", "$BASE/models/$model:generateContent", prefs.geminiKey, body.toString())
                when {
                    code == 429 -> { // 무료 한도 소진 → 1시간 동안 내장 번역 사용
                        prefs.geminiCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                        lastError = "무료 한도를 다 썼어요 · 1시간 동안 휴대폰 내장 번역 사용"
                        return@runCatching null
                    }
                    code == 404 -> { prefs.geminiModel = ""; lastError = "모델을 찾지 못함 ($model)"; return@runCatching null }
                    code == 400 || code == 401 || code == 403 -> { lastError = "API 키가 올바르지 않아요 (HTTP $code)"; return@runCatching null }
                    code != 200 -> { lastError = "HTTP $code"; return@runCatching null }
                }
                val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val joined = parts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }.orEmpty()
                val arr = runCatching { JSONArray(joined) }.getOrNull()
                if (arr == null || arr.length() != chunk.size) { lastError = "응답 형식이 맞지 않음"; return@runCatching null }
                for (i in 0 until arr.length()) out += arr.optString(i, chunk[i])
            }
            lastError = null
            out as List<String>
        }.getOrElse { lastError = it.message ?: "연결 실패"; null }
    }
}
