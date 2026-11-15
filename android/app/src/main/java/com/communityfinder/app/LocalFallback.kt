package com.communityfinder.app

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 목록 파싱 규칙 (클라우드 수집기의 sources.js와 같은 형식, 피드에 실려 옴) */
data class LocalSpec(
    val url: String, val row: String, val title: String, val link: String?, val views: String?, val comments: String?,
    val likes: String?, val exclude: String?, val keepParams: List<String>, val titleStrip: String,
) {
    companion object {
        fun parse(o: JSONObject?): LocalSpec? {
            o ?: return null
            val sel = o.optJSONObject("selectors") ?: return null
            fun s(k: String) = sel.optString(k).takeIf { it.isNotBlank() }
            val keep = o.optJSONArray("keepParams")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            return LocalSpec(o.optString("url"), s("row") ?: return null, s("title") ?: return null, s("link"), s("views"), s("comments"), s("likes"), s("exclude"), keep, o.optString("titleStrip"))
        }
    }
}

/**
 * 클라우드 수집이 막힌 커뮤니티(HTTP 403 등)를 휴대폰에서 대신 받아옴.
 * 브라우저로 목록 한 페이지를 여는 것과 같고, 15분에 한 번만 요청. 429/403/430이면 30분 쉼.
 */
object LocalFallback {
    private const val MIN_INTERVAL_MS = 15 * 60_000L
    private const val COOLDOWN_MS = 30 * 60_000L

    /** "1,234" "1.2만" "[12]" → 숫자 */
    fun parseCount(text: String?): Int? {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(만|천|k|K|m|M)?").find(text?.replace(",", "") ?: return null) ?: return null
        val mult = when (m.groupValues[2]) { "만" -> 10000.0; "천", "k", "K" -> 1000.0; "m", "M" -> 1e6; else -> 1.0 }
        return Math.round(m.groupValues[1].toDouble() * mult).toInt()
    }

    /** 같은 커뮤니티 안 백분위(조회·댓글·추천) 평균 × 85 — 클라우드 score.js와 같은 방식 */
    fun scores(metrics: List<List<Int?>>): List<Int> {
        val cols = metrics.firstOrNull()?.size ?: return emptyList()
        val weights = listOf(1.0, 1.2, 1.0)
        val sorted = (0 until cols).map { c -> metrics.mapNotNull { it[c] }.sorted() }
        return metrics.mapIndexed { i, row ->
            var sum = 0.0; var w = 0.0
            for (c in 0 until cols) {
                val v = row[c] ?: continue
                val col = sorted[c]
                if (col.size < 2) continue
                val lo = col.count { it < v }; val hi = lo + col.count { it == v }
                sum += ((lo + hi - 1) / 2.0) / (col.size - 1) * weights.getOrElse(c) { 1.0 }; w += weights.getOrElse(c) { 1.0 }
            }
            val base = if (w > 0) sum / w else 1.0 - i.toDouble() / maxOf(metrics.size - 1, 1)
            Math.round(base * 85).toInt()
        }
    }

    data class Item(val id: String, val title: String, val url: String, val views: Int?, val comments: Int?, val likes: Int?)

    fun parseList(html: String, sourceId: String, spec: LocalSpec): List<Item> {
        val doc = Jsoup.parse(html, spec.url)
        val seen = HashSet<String>()
        return doc.select(spec.row).mapNotNull { row ->
            if (spec.exclude != null && row.`is`(spec.exclude)) return@mapNotNull null
            val titleEl = row.selectFirst(spec.title) ?: return@mapNotNull null
            val clone = titleEl.clone()
            spec.comments?.substringAfterLast(' ')?.let { runCatching { clone.select(it).remove() } }
            var title = clone.text().replace(Regex("\\s+"), " ").replace(Regex("\\s*[\\[(]\\d+[\\])]\\s*$"), "").trim()
            if (spec.titleStrip.isNotEmpty()) title = title.replace(Regex(spec.titleStrip), "").trim()
            if (title.isEmpty()) return@mapNotNull null
            val linkEl = (if (spec.link != null) row.selectFirst(spec.link) else if (titleEl.tagName() == "a") titleEl else titleEl.closest("a")) ?: titleEl.selectFirst("a")
            val abs = linkEl?.absUrl("href").orEmpty()
            if (!abs.startsWith("http")) return@mapNotNull null
            val u = Uri.parse(abs)
            val b = u.buildUpon().clearQuery().fragment(null)
            spec.keepParams.forEach { k -> u.getQueryParameter(k)?.let { b.appendQueryParameter(k, it) } }
            val url = b.build().toString()
            if (!seen.add(url)) return@mapNotNull null
            fun num(sel: String?) = sel?.let { parseCount(row.selectFirst(it)?.text()) }
            Item("$sourceId:$url", title, url, num(spec.views), num(spec.comments), num(spec.likes))
        }
    }

    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "local-$id.json")

    /** network=false면 저장해 둔 결과만. 반환: (상태가 반영된 소스, 글 목록) 또는 저장된 게 없으면 null */
    fun load(ctx: Context, source: Source, network: Boolean, now: Long = System.currentTimeMillis()): Pair<Source, List<Post>>? {
        val spec = source.local ?: return null
        val f = file(ctx, source.id)
        val state = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        fun cached(error: String?): Pair<Source, List<Post>>? {
            val posts = state.optJSONArray("posts")?.let { a -> (0 until a.length()).map { PostStore.fromJson(a.getJSONObject(it)) } }.orEmpty()
            if (posts.isEmpty() && error == null) return null
            return source.copy(ok = error == null && posts.isNotEmpty(), error = error ?: source.error) to posts
        }
        if (!network) return cached(null)
        val cooldown = state.optLong("cooldownUntil", 0)
        if (now < cooldown) return cached("휴대폰 접근도 제한됨 · ${(cooldown - now) / 60000 + 1}분 뒤 다시 시도")
        if (now - state.optLong("fetchedAt", 0) < MIN_INTERVAL_MS && state.has("posts")) return cached(null)

        val conn = URL(spec.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        // 파싱 규칙이 PC 화면 기준이라, 브라우저의 '데스크톱 사이트' 보기처럼 PC 화면을 요청
        runCatching { conn.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(ctx).replace(Regex("; wv\\)"), ")").replace(Regex(" Mobile"), "")) }
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        return try {
            val code = conn.responseCode
            if (code == 429 || code == 403 || code == 430) {
                state.put("cooldownUntil", now + COOLDOWN_MS); f.writeText(state.toString())
                return cached("휴대폰 접근도 제한됨 (HTTP $code) · 30분 뒤 다시 시도")
            }
            if (code != 200) return cached("휴대폰 수집 HTTP $code")
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(conn.contentType.orEmpty())?.groupValues?.get(1) ?: "UTF-8"
            val html = conn.inputStream.use { String(it.readBytes(), runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8)) }
            val items = parseList(html, source.id, spec)
            if (items.isEmpty()) return cached("휴대폰 수집: 글을 찾지 못함")
            val sc = scores(items.map { listOf(it.views, it.comments, it.likes) })
            val firstSeen = state.optJSONObject("firstSeen") ?: JSONObject()
            val newSeen = JSONObject()
            val posts = items.mapIndexed { i, it ->
                val fs = firstSeen.optLong(it.id, now); newSeen.put(it.id, fs)
                Post(it.id, source.id, it.title, it.url, it.views, it.comments, it.likes, sc[i], 0, fs)
            }
            state.put("posts", JSONArray().also { a -> posts.forEach { p -> a.put(PostStore.toJson(p)) } }).put("firstSeen", newSeen).put("fetchedAt", now).put("cooldownUntil", 0)
            f.writeText(state.toString())
            source.copy(ok = true, error = null) to posts
        } catch (e: Exception) {
            cached("휴대폰 수집 실패: ${e.message ?: "연결 오류"}")
        } finally {
            conn.disconnect()
        }
    }
}
