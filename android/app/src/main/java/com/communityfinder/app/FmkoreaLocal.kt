package com.communityfinder.app

import android.content.Context
import android.webkit.WebSettings
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 에펨코리아 포텐 게시판을 휴대폰에서 직접 가져옵니다.
 *
 * 에펨코리아는 클라우드(데이터센터) 서버의 자동 접근을 HTTP 430으로 막기 때문에,
 * 클라우드 대신 사용자의 휴대폰에서 브라우저로 여는 것과 같은 방식으로 /best 한 페이지만 받아옵니다.
 * - 최소 15분 간격으로만 요청 (그 사이에는 저장해 둔 결과 사용)
 * - 429/430 응답을 받으면 Retry-After(없으면 30분) 동안 요청하지 않음
 * - 차단을 우회하는 장치(프록시, 위장 등)는 쓰지 않음
 */
object FmkoreaLocal {
    const val ID = "fmkorea"
    private const val URL_BEST = "https://www.fmkorea.com/best"
    private const val MIN_INTERVAL_MS = 15 * 60_000L
    private const val DEFAULT_COOLDOWN_MS = 30 * 60_000L

    val SOURCE_TEMPLATE = Source(ID, "에펨코리아", "포텐 터짐", 0xFF4A7BD0, ok = false, error = null)

    data class RawPost(val no: String, val title: String, val comments: Int?, val likes: Int?)

    /** /best 목록 HTML 파싱 */
    fun parse(html: String): List<RawPost> {
        val doc = Jsoup.parse(html, URL_BEST)
        val seen = HashSet<String>()
        return doc.select("li.li").mapNotNull { row ->
            val a = row.selectFirst("h3.title a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val no = Regex("document_srl=(\\d+)").find(href)?.groupValues?.get(1)
                ?: Regex("/(\\d+)(?:[?#]|$)").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null
            if (!seen.add(no)) return@mapNotNull null
            val titleEl = (a.selectFirst(".ellipsis-target") ?: a).clone()
            titleEl.select(".comment_count").remove()
            val title = titleEl.text().replace(Regex("\\s*\\[\\d+]\\s*$"), "").trim()
            if (title.isEmpty()) return@mapNotNull null
            RawPost(
                no = no,
                title = title,
                comments = countOf(row.selectFirst(".comment_count")?.text()),
                likes = countOf(row.selectFirst(".pc_voted_count .count")?.text()),
            )
        }
    }

    fun countOf(text: String?): Int? = text?.replace(",", "")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    /** 같은 커뮤니티 안 백분위(댓글·추천)로 화제도 계산 — 클라우드 수집기(src/score.js)와 같은 방식 */
    fun score(raw: List<RawPost>): List<Int> {
        fun pct(values: List<Int?>): (Int) -> Double {
            val sorted = values.filterNotNull().sorted()
            val n = sorted.size
            return { v ->
                if (n <= 1) 0.5 else {
                    val lo = sorted.count { it < v }
                    val hi = lo + sorted.count { it == v }
                    ((lo + hi - 1) / 2.0) / (n - 1)
                }
            }
        }
        val pc = pct(raw.map { it.comments })
        val pl = pct(raw.map { it.likes })
        val hasC = raw.count { it.comments != null } >= 2
        val hasL = raw.count { it.likes != null } >= 2
        return raw.mapIndexed { i, p ->
            var sum = 0.0
            var w = 0.0
            if (hasC && p.comments != null) { sum += pc(p.comments) * 1.2; w += 1.2 }
            if (hasL && p.likes != null) { sum += pl(p.likes); w += 1.0 }
            val base = if (w > 0) sum / w else 1.0 - i.toDouble() / maxOf(raw.size - 1, 1)
            Math.round(base * 85).toInt()
        }
    }

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "fmkorea.json")

    /** 네트워크 없이 마지막으로 받아둔 결과만 */
    fun cachedOnly(ctx: Context): Pair<Source, List<Post>> =
        fromState(runCatching { JSONObject(stateFile(ctx).readText()) }.getOrDefault(JSONObject()), null)

    private fun fromState(state: JSONObject, error: String?): Pair<Source, List<Post>> {
        val posts = state.optJSONArray("posts")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Post(
                    id = o.getString("id"), source = ID, title = o.getString("title"), url = o.getString("url"),
                    views = null,
                    comments = if (o.isNull("comments")) null else o.optInt("comments"),
                    likes = if (o.isNull("likes")) null else o.optInt("likes"),
                    score = o.optInt("score"), viewsPerMin = 0, firstSeenAt = o.optLong("firstSeenAt"),
                )
            }
        }.orEmpty()
        return SOURCE_TEMPLATE.copy(ok = error == null && posts.isNotEmpty(), error = error) to posts
    }

    /**
     * 캐시가 15분 이내면 캐시를, 아니면 새로 받아옴.
     * 반환: (소스 상태, 글 목록)
     */
    fun load(ctx: Context, now: Long = System.currentTimeMillis()): Pair<Source, List<Post>> {
        val file = stateFile(ctx)
        val state = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val cooldownUntil = state.optLong("cooldownUntil", 0)
        val fetchedAt = state.optLong("fetchedAt", 0)

        fun cached(error: String?) = fromState(state, error)

        if (now < cooldownUntil) {
            val mins = (cooldownUntil - now) / 60000 + 1
            return cached("에펨코리아가 접근을 제한함 · ${mins}분 뒤 다시 시도")
        }
        if (now - fetchedAt < MIN_INTERVAL_MS && state.has("posts")) return cached(null)

        val conn = URL(URL_BEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        // 이 휴대폰의 기본 브라우저 엔진(WebView)이 쓰는 그대로의 User-Agent
        runCatching { conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx)) }
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")

        try {
            val code = conn.responseCode
            if (code == 429 || code == 430) {
                val retry = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: DEFAULT_COOLDOWN_MS
                state.put("cooldownUntil", now + retry)
                file.writeText(state.toString())
                return cached("에펨코리아가 접근을 제한함 (HTTP $code) · ${retry / 60000}분 뒤 다시 시도")
            }
            if (code != 200) return cached("HTTP $code")

            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val raw = parse(html)
            if (raw.isEmpty()) return cached("글을 찾지 못했어요 (사이트 구조 변경 가능성)")

            val scores = score(raw)
            val firstSeen = state.optJSONObject("firstSeen") ?: JSONObject()
            val newFirstSeen = JSONObject()
            val posts = raw.mapIndexed { i, r ->
                val id = "$ID:https://www.fmkorea.com/${r.no}"
                val fs = firstSeen.optLong(id, now)
                newFirstSeen.put(id, fs)
                Post(id, ID, r.title, "https://www.fmkorea.com/${r.no}", null, r.comments, r.likes, scores[i], 0, fs)
            }
            val arr = org.json.JSONArray()
            posts.forEach { p ->
                arr.put(
                    JSONObject().put("id", p.id).put("title", p.title).put("url", p.url)
                        .put("comments", p.comments ?: JSONObject.NULL).put("likes", p.likes ?: JSONObject.NULL)
                        .put("score", p.score).put("firstSeenAt", p.firstSeenAt),
                )
            }
            state.put("posts", arr).put("firstSeen", newFirstSeen).put("fetchedAt", now).put("cooldownUntil", 0)
            file.writeText(state.toString())
            return SOURCE_TEMPLATE.copy(ok = true) to posts
        } catch (e: Exception) {
            return cached(e.message ?: "연결 실패")
        } finally {
            conn.disconnect()
        }
    }
}
