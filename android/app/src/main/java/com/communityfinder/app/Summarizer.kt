package com.communityfinder.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 스레드 본문용 요약: 내보내기 이미지에서 글자를 읽고(휴대폰 안에서 처리),
 * Gemini → Groq 순서로 요약을 요청. 키가 없거나 둘 다 실패하면 null.
 */
object Summarizer {
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    const val MAX_SUMMARY_CHARS = 450 // 스레드 글자 수 제한(500자) 안쪽

    private const val SYSTEM = "너는 커뮤니티 게시글을 스레드(Threads)에 올릴 글로 요약한다. 입력은 게시글 제목과 본문이며, 본문은 이미지에서 글자를 읽은 것이라 오타나 순서 뒤섞임, 메뉴·버튼 글자가 섞여 있을 수 있다. " +
        "규칙: 1) 글의 핵심 내용과 결말이 드러나게 3~5문장으로 요약. 2) 한국 커뮤니티 말투의 자연스러운 반말 구어체. 3) 글에 없는 내용을 지어내지 말 것. 4) 작성자 닉네임, 조회수, 날짜, 메뉴 글자는 빼기. " +
        "5) 해시태그, 이모지, 따옴표, 머리말 없이 요약문만 출력. 6) 공백 포함 350자 이내."

    /** 이미지들에서 글자 읽기 (위에서 아래 순서) */
    suspend fun readText(pieces: List<Bitmap>): String {
        val sb = StringBuilder()
        for (b in pieces) {
            val t = suspendCancellableCoroutine<String?> { c ->
                recognizer.process(InputImage.fromBitmap(b, 0)).addOnSuccessListener { c.resume(it.text) }.addOnFailureListener { c.resume(null) }
            }
            if (!t.isNullOrBlank()) sb.append(t).append('\n')
        }
        return sb.toString()
    }

    fun hasKey(ctx: Context) = Prefs(ctx).let { it.geminiKey.isNotBlank() || it.groqKey.isNotBlank() }

    fun buildInput(title: String, domText: String, ocrText: String): String {
        // 페이지 글자와 이미지에서 읽은 글자 중 더 긴 쪽을 본문으로 (둘이 대부분 겹침)
        val body = (if (ocrText.length >= domText.length) ocrText else domText).replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        return "제목: $title\n\n본문:\n${body.take(6000)}"
    }

    fun clean(raw: String): String {
        var s = raw.trim().removePrefix("요약:").removePrefix("요약 :").trim().trim('"', '\u201C', '\u201D').trim()
        if (s.length > MAX_SUMMARY_CHARS) {
            // 문장 끝에서 자르기
            val cut = s.take(MAX_SUMMARY_CHARS)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', 'ㅋ', '다'))
            s = if (end > MAX_SUMMARY_CHARS / 2) cut.take(end + 1) else cut
        }
        return s
    }

    suspend fun summarize(ctx: Context, title: String, domText: String, pieces: List<Bitmap>): String? {
        if (!hasKey(ctx)) return null
        val input = buildInput(title, domText, runCatching { readText(pieces) }.getOrDefault(""))
        val raw = Gemini.ask(ctx, SYSTEM, input) ?: Groq.ask(ctx, SYSTEM, input) ?: return null
        return clean(raw).takeIf { it.isNotBlank() }
    }
}
