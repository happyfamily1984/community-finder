package com.communityfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq 번역 (무료 API 키, Llama 계열 AI). Gemini 한도가 떨어졌을 때 두 번째로 사용.
 * 실패하면 null → 휴대폰 내장 번역으로 넘어감.
 */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    private const val COOLDOWN_MS = 10 * 60_000L
    @Volatile var lastError: String? = null

    fun inCooldown(prefs: Prefs) = System.currentTimeMillis() < prefs.groqCooldownUntil
    fun usable(prefs: Prefs) = prefs.groqKey.isNotBlank() && !inCooldown(prefs)

    private fun request(method: String, url: String, key: String, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            return code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    /** 쓸 수 있는 모델 중 번역에 맞는 큰 범용 모델 선택 (이름이 바뀌어도 동작하도록) */
    fun pickModel(listJson: String): String? {
        val data = JSONObject(listJson).optJSONArray("data") ?: return null
        val ids = (0 until data.length()).map { data.getJSONObject(it).getString("id") }
            .filter { !Regex("whisper|guard|tts|speech|vision-preview|embed|compound|allam|orpheus", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        fun size(n: String) = Regex("(\\d+)b", RegexOption.IGNORE_CASE).find(n)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return ids.sortedWith(
            compareByDescending<String> { it.contains("versatile") }.thenByDescending { it.contains("llama") }.thenByDescending { size(it) },
        ).firstOrNull()
    }

    private fun model(prefs: Prefs): String? {
        prefs.groqModel.takeIf { it.isNotBlank() }?.let { return it }
        val (code, text) = request("GET", "$BASE/models", prefs.groqKey)
        if (code != 200) { lastError = if (code == 401) "API 키가 올바르지 않아요" else "모델 목록 HTTP $code"; return null }
        return pickModel(text)?.also { prefs.groqModel = it }
    }

    suspend fun translate(ctx: Context, texts: List<String>, context: String = ""): List<String>? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (texts.isEmpty()) return@withContext emptyList()
        if (!usable(prefs)) return@withContext null
        runCatching {
            val model = model(prefs) ?: return@runCatching null
            val out = ArrayList<String>(texts.size)
            for (chunk in texts.chunked(Gemini.CHUNK)) {
                val system = Gemini.SYSTEM + " 출력 형식은 {\"t\": [번역1, 번역2, ...]} 인 JSON 객체다." + if (context.isNotEmpty()) " 참고: $context" else ""
                val body = JSONObject().put("model", model).put("temperature", 0.3)
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", JSONArray(chunk).toString())))
                val (code, text) = request("POST", "$BASE/chat/completions", prefs.groqKey, body.toString())
                when {
                    code == 429 -> {
                        prefs.groqCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                        lastError = "무료 한도를 다 썼어요 · 10분 동안 쉬어요"; return@runCatching null
                    }
                    code == 404 || (code == 400 && text.contains("model")) -> { prefs.groqModel = ""; lastError = "모델을 찾지 못함 ($model)"; return@runCatching null }
                    code == 401 || code == 403 -> { lastError = "API 키가 올바르지 않아요 (HTTP $code)"; return@runCatching null }
                    code != 200 -> { lastError = "HTTP $code"; return@runCatching null }
                }
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                val arr = runCatching { JSONObject(content).getJSONArray("t") }.getOrNull()
                if (arr == null || arr.length() != chunk.size) { lastError = "응답 형식이 맞지 않음"; return@runCatching null }
                for (i in 0 until arr.length()) out += arr.optString(i, chunk[i])
            }
            lastError = null
            out as List<String>
        }.getOrElse { lastError = it.message ?: "연결 실패"; null }
    }
}
