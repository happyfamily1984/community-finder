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
     * 앱 설정에서 직접 추가한 본보기(Prefs.hitExamples)도 함께 쓰인다.
     */
    val BUILTIN_EXAMPLES = listOf(
        // 조회수 11만 (2026-11): 결과를 먼저 던지고(훅) → 흔한 생각 뒤집기 → 진짜 핵심 공개 → 구체적 디테일 → 첫 줄의 궁금증 닫기
        "세무냥이가 결국 자수할 수밖에 없었던 수사 전말\n\n" +
            "블루투스나 스마트 안경을 숨겨봤자 소용없었어\n\n" +
            "진짜 결정타는 바로 기지국 통신기록조회였거든\n\n" +
            "해당 시간대 기지국에 데이터 전송 기록이 찍혀버렸어\n\n" +
            "법원 영장 나오자마자 꼼짝없이 잡힐 수밖에 없었던 거지",
    )

    /** 요약에 쓸 본보기 목록: 내가 추가한 것이 먼저, 기본 본보기가 뒤 */
    fun pool(userExamples: List<String>): List<String> =
        (userExamples + BUILTIN_EXAMPLES).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 어떤 방식이든 지켜야 하는 공통 규칙 */
    private const val COMMON = "규칙: 모든 줄에 이 글에서 나온 구체적인 사실이 들어가야 한다. '화제다', '난리다', '이런 일까지 벌어질 줄이야' 같은 내용 없는 채움 문장은 쓰지 않는다. " +
        "사실은 바꾸지 않고, 글에 없는 내용을 지어내지 않는다. 숫자와 고유명사는 정확히. " +
        "특정 개인·집단을 비하하거나 혐오하는 표현은 쓰지 않는다. 작성자 닉네임, 기자 이름, 매체명, 조회수, 날짜, 메뉴·버튼 글자는 빼기. " +
        "해시태그, 따옴표, 머리말 없이 본문만 출력. 한 줄은 40자를 넘기지 않는다. 공백과 줄바꿈 포함 430자 이내."

    private const val INTRO = "너는 커뮤니티 게시글을 스레드(Threads)에 올릴 글로 바꿔 쓰는 작가다. " +
        "입력은 게시글 제목과 본문이며, 본문은 이미지에서 글자를 읽은 것이라 오타나 순서 뒤섞임, 메뉴·버튼 글자가 섞여 있을 수 있다.\n\n"

    private const val HOW_TO_USE = "아래 본보기는 스레드에서 실제로 반응이 좋았던 글의 '틀'이다. 이 틀에 이 글의 내용을 넣어서 쓴다.\n" +
        "- 본보기의 줄 수, 줄 순서, 각 줄이 하는 역할, 말투, 줄 사이 빈 줄, (있다면) 이모지·번호·기호를 그대로 따른다.\n" +
        "- [대괄호] 안의 말은 '여기에 무엇을 넣을지' 알려주는 자리 표시다. 이 글의 내용으로 바꿔 넣고, 대괄호 자체는 절대 출력하지 않는다.\n" +
        "- 본보기에 들어 있는 실제 내용이나 문장은 베끼지 않는다. 틀만 가져온다.\n" +
        "- 틀의 어떤 줄에 넣을 내용이 이 글에 없으면 그 줄은 지어내지 말고 뺀다.\n"

    /**
     * 요약 지시문.
     * @param forced 꼭 따를 본보기 번호(pool의 인덱스). null이면 글 성격에 맞는 것을 AI가 고름
     */
    fun systemPrompt(userExamples: List<String>, useExamples: Boolean = true, forced: Int? = null): String {
        if (!useExamples) return FREE_RULES
        val pool = pool(userExamples)
        val sb = StringBuilder(INTRO).append(HOW_TO_USE)
        if (forced != null && pool.isNotEmpty()) {
            sb.append("\n이번에는 반드시 아래 본보기의 틀로 쓴다.\n\n[본보기]\n").append(pool[Math.floorMod(forced, pool.size)]).append("\n")
        } else {
            sb.append("- 본보기가 여러 개면, 이 글의 성격(사건·논란 / 유머 / 정보 / 사연 등)에 가장 잘 맞는 하나만 골라 그 틀로 쓴다. 여러 본보기를 섞지 않는다.\n")
            pool.take(8).forEachIndexed { i, e -> sb.append("\n[본보기 ${i + 1}]\n").append(e).append("\n") }
        }
        return sb.append("\n").append(COMMON).toString()
    }

    /** 본보기를 끈 경우: 공식 없이 글 성격에 맞춰 자유롭게 */
    private const val FREE_RULES = "너는 커뮤니티 게시글을 스레드(Threads)에 올릴 글로 바꿔 쓰는 작가다. " +
        "입력은 게시글 제목과 본문이며, 본문은 이미지에서 글자를 읽은 것이라 오타나 순서 뒤섞임, 메뉴·버튼 글자가 섞여 있을 수 있다.\n\n" +
        "정해진 틀 없이, 이 글에 가장 잘 맞는 방식으로 쓴다. 먼저 글의 성격을 판단한다:\n" +
        "- 웃긴 글: 상황을 짧게 깔고 웃긴 지점에서 툭 끊는다. 설명하지 않는다.\n" +
        "- 사연·논란: 무슨 일인지 → 어디가 문제인지 → 한 줄 평.\n" +
        "- 정보·꿀팁: 핵심 사실부터 말하고 필요한 숫자·방법만 덧붙인다.\n" +
        "- 감동·훈훈: 담담하게 있었던 일만 전한다.\n" +
        "- 사진·짤 위주: 사진을 보고 싶게 만드는 한두 줄이면 충분하다.\n" +
        "첫 줄은 스크롤을 멈추게 해야 하지만 방식은 매번 달라야 한다(질문, 대사 인용, 숫자, 상황 묘사, 단정 등). '결국', '진짜 ~는 바로', '~할 수밖에 없었던' 같은 틀에 박힌 표현에 기대지 않는다.\n\n" +
        "형식: 문장마다 한 줄, 문장 사이에 빈 줄 하나. 길이는 글에 맞게 2~6줄. 한 줄은 40자를 넘기지 않는다.\n" +
        "말투: 한국 커뮤니티·스레드의 자연스러운 반말 구어체. 문장 끝을 한 가지로 통일하지 말고 섞는다. 억지 유행어·과한 감탄사 금지, ㅋㅋ는 많아야 한 번.\n" +
        "이모지, 번호, 글머리 기호는 쓰지 않는다. " + COMMON

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

    /** keepMarkers: 본보기 틀에 번호·기호가 있을 수 있으므로 그대로 둠 */
    fun clean(raw: String, keepMarkers: Boolean = false): String {
        var s = raw.trim().removePrefix("요약:").removePrefix("요약 :").trim().trim('"', '\u201C', '\u201D').trim()
        s = s.lines().joinToString("\n") { l -> l.trim().let { if (keepMarkers) it else it.replace(Regex("^([-•·*]|\\d+[.)])\\s*"), "") } }
            .replace(Regex("\n{3,}"), "\n\n").replace(Regex("\\[([^\\]\n]{1,30})]"), "$1").trim() // 남은 대괄호 자리 표시는 괄호만 제거
        if (s.length > MAX_SUMMARY_CHARS) {
            val cut = s.take(MAX_SUMMARY_CHARS)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
            s = if (end > MAX_SUMMARY_CHARS / 2) cut.take(end + 1).trim() else cut
        }
        return s
    }

    /** 요약 결과와, 어떤 본보기를 썼는지 */
    data class Result(val text: String, val usedLabel: String)

    /**
     * @param run 0이면 글 성격에 맞는 본보기를 AI가 고름. 1 이상('다시 요약'을 누른 횟수)이면 본보기를 차례대로 바꿔 가며 씀
     */
    suspend fun summarize(ctx: Context, title: String, domText: String, pieces: List<Bitmap>, run: Int = 0): Result? {
        if (!hasKey(ctx)) return null
        val input = buildInput(title, domText, runCatching { readText(pieces) }.getOrDefault(""))
        val prefs = Prefs(ctx)
        val use = prefs.useHitStyle
        val pool = pool(prefs.hitExamples)
        val forced = if (use && run > 0 && pool.isNotEmpty()) (run - 1) % pool.size else null
        val system = systemPrompt(prefs.hitExamples, use, forced)
        val raw = Gemini.ask(ctx, system, input) ?: Groq.ask(ctx, system, input) ?: return null
        val text = clean(raw, keepMarkers = use).takeIf { it.isNotBlank() } ?: return null
        val label = when {
            !use -> "본보기 끔 · 자유 요약"
            forced != null -> "본보기 ${forced + 1}/${pool.size}: ${pool[forced].lineSequence().first().take(22)}"
            else -> "본보기 자동 선택 (${pool.size}개 중) · '다시 요약'을 누르면 하나씩 바꿔 써요"
        }
        return Result(text, label)
    }
}
