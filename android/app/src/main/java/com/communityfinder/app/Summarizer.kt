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

    /**
     * 실제로 반응이 좋았던 글(본보기). 사용자가 보내주는 대로 여기에 추가한다.
     * 앱 설정에서 직접 추가한 예시(Prefs.hitExamples)도 함께 쓰인다.
     */
    val BUILTIN_EXAMPLES = listOf(
        // 조회수 11만 (2026-11): '결과를 먼저 던지고 이유를 풀어주는' 전말형
        "세무냥이가 결국 자수할 수밖에 없었던 수사 전말\n\n" +
            "블루투스나 스마트 안경을 숨겨봤자 소용없었어\n\n" +
            "진짜 결정타는 바로 기지국 통신기록조회였거든\n\n" +
            "해당 시간대 기지국에 데이터 전송 기록이 찍혀버렸어\n\n" +
            "법원 영장 나오자마자 꼼짝없이 잡힐 수밖에 없었던 거지",
    )

    private const val RULES = "너는 커뮤니티 게시글을 스레드(Threads)에 올릴 글로 바꿔 쓰는 작가다. " +
        "입력은 게시글 제목과 본문이며, 본문은 이미지에서 글자를 읽은 것이라 오타나 순서 뒤섞임, 메뉴·버튼 글자가 섞여 있을 수 있다.\n\n" +
        "쓰는 방법 (반응이 좋았던 글에서 뽑은 공식):\n" +
        "1) 첫 줄 = 훅. 결과나 결말만 던지고 이유는 숨긴다. 읽는 사람이 '왜?' 하고 궁금해져야 한다. " +
        "'누가 결국 ~할 수밖에 없었던 이유/전말', '~한 진짜 이유', '~했더니 벌어진 일' 같은 꼴. 답을 첫 줄에 쓰지 않는다. 25~30자.\n" +
        "2) 둘째 줄 = 뒤집기. 사람들이 흔히 생각하는 것이나 겉으로 보이는 것을 먼저 꺼내 '그게 아니었어' 하고 뒤집는다. (예: '~해봤자 소용없었어')\n" +
        "3) 셋째 줄 = 핵심 공개. '진짜 ~는 바로 ~였거든' 처럼 글의 알맹이를 한 문장으로 밝힌다.\n" +
        "4) 넷째 줄 = 디테일. 구체적인 사실 하나(숫자, 이름, 방법, 장면)로 실감 나게 받친다.\n" +
        "5) 마지막 줄 = 닫기. 첫 줄에서 던진 궁금증을 '~할 수밖에 없었던 거지' 처럼 딱 닫는다. 자연스럽게 떠오르면 뼈 있는 한마디로 끝내도 좋다.\n" +
        "글 성격에 맞게 응용한다: 사건·논란은 전말형, 유머는 상황→반전, 정보는 '몰랐던 사실' 공개. 뒤집을 통념이 없으면 2)는 상황 설명으로 바꾼다.\n\n" +
        "형식: 문장마다 한 줄, 문장 사이에 빈 줄 하나. 전체 4~6줄. 한 줄은 40자를 넘기지 않는다.\n" +
        "말투: 친구한테 썰 풀어주는 반말. 문장 끝은 '~어', '~거든', '~거지', '~더라'처럼 자연스럽게 섞는다. 설명문 말투('~했다', '~이다')와 존댓말은 쓰지 않는다. 억지 유행어·과한 감탄사 금지, ㅋㅋ는 많아야 한 번.\n" +
        "규칙: 뭉뚱그리지 말고 글에 나온 구체적인 단어를 쓴다. 사실은 바꾸지 않고, 글에 없는 내용을 지어내지 않는다. 숫자와 고유명사는 정확히. " +
        "특정 개인·집단을 비하하거나 혐오하는 표현은 쓰지 않는다. 작성자 닉네임, 조회수, 날짜, 메뉴 글자는 빼기. " +
        "해시태그, 이모지, 따옴표, 번호, 글머리 기호, 머리말 없이 본문만 출력. 공백과 줄바꿈 포함 350자 이내."

    /** 규칙 + 반응 좋았던 본보기 (기본 제공 + 사용자가 앱에서 추가한 것, 최근 5개까지) */
    fun systemPrompt(userExamples: List<String>): String {
        val examples = (BUILTIN_EXAMPLES + userExamples).map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(5)
        return RULES + "\n\n아래는 실제로 반응이 아주 좋았던 글이다. 구조·호흡·말투를 본받되, 내용이나 문장을 그대로 베끼지는 않는다.\n" +
            examples.mapIndexed { i, e -> "\n[본보기 ${i + 1}]\n$e\n" }.joinToString("")
    }

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
        s = s.lines().joinToString("\n") { it.trim().replace(Regex("^([-•·*]|\\d+[.)])\\s*"), "") }.replace(Regex("\n{3,}"), "\n\n").trim()
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
        val system = systemPrompt(Prefs(ctx).hitExamples)
        val raw = Gemini.ask(ctx, system, input) ?: Groq.ask(ctx, system, input) ?: return null
        return clean(raw).takeIf { it.isNotBlank() }
    }
}
