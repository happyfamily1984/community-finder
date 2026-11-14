package com.communityfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reddit 유머 게시판을 휴대폰에서 직접 가져옴 (Reddit이 클라우드 서버 접근을 HTTP 403으로 막아서).
 * 공개 JSON을 게시판당 30분에 한 번만 요청하고, 429/403이면 1시간 쉼. 성인글·고정글 제외.
 */
object RedditLocal {
    private const val MIN_INTERVAL_MS = 30 * 60_000L
    private const val COOLDOWN_MS = 60 * 60_000L
    private const val UA = "android:com.communityfinder.app:v1 (personal feed reader)"

    val SOURCES = listOf(
        Triple("reddit-funny", "funny", 0xFFFF4500),
        Triple("reddit-memes", "memes", 0xFFFF6A33),
        Triple("reddit-unexpected", "Unexpected", 0xFFD93A00),
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "reddit2.json")

    fun source(id: String, sub: String, color: Long, ok: Boolean, error: String?) = Source(id, "Reddit r/$sub", "오늘의 인기", color, ok, error)

    fun parse(json: String, sourceId: String, now: Long): List<Post> {
        val children = JSONObject(json).getJSONObject("data").getJSONArray("children")
        val raw = (0 until children.length()).map { children.getJSONObject(it).getJSONObject("data") }
            .filter { !it.optBoolean("over_18") && !it.optBoolean("stickied") }
        val scores = FmkoreaLocal.score(raw.map { FmkoreaLocal.RawPost(it.getString("id"), it.getString("title"), it.optInt("num_comments"), it.optInt("score")) })
        return raw.mapIndexed { i, d ->
            Post(
                id = "$sourceId:${d.getString("id")}", source = sourceId, title = d.getString("title"),
                url = "https://www.reddit.com" + d.getString("permalink"), views = null,
                comments = d.optInt("num_comments"), likes = d.optInt("score"), score = scores[i], viewsPerMin = 0,
                firstSeenAt = (d.optDouble("created_utc", now / 1000.0) * 1000).toLong(),
                image = d.optString("url").takeIf { d.optString("post_hint") == "image" && it.startsWith("https://") },
            )
        }
    }

    /** 공식 RSS(Atom) 피드 파싱. 점수가 없어서 순서(인기순)로 화제도를 매김 */
    fun parseRss(xml: String, sourceId: String, now: Long): List<Post> {
        val doc = org.jsoup.Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
        val entries = doc.select("entry").filter { !it.select("content").text().contains("nsfw", ignoreCase = true) }
        return entries.mapIndexedNotNull { i, e ->
            val title = e.selectFirst("title")?.text()?.trim().orEmpty()
            val link = e.selectFirst("link")?.attr("href").orEmpty()
            val pid = Regex("/comments/([a-z0-9]+)").find(link)?.groupValues?.get(1)
            if (title.isEmpty() || pid == null) return@mapIndexedNotNull null
            val image = Regex("https://i\\.redd\\.it/[\\w.-]+\\.(?:jpe?g|png|webp|gif)").find(e.selectFirst("content")?.text().orEmpty())?.value
            Post(
                id = "$sourceId:$pid", source = sourceId, title = title, url = link, views = null, comments = null, likes = null,
                score = Math.round(85f * (1f - i.toFloat() / maxOf(entries.size - 1, 1))), viewsPerMin = 0, firstSeenAt = now, image = image,
            )
        }
    }

    /** network=false면 저장해 둔 결과만 */
    fun load(ctx: Context, network: Boolean, now: Long = System.currentTimeMillis()): List<Pair<Source, List<Post>>> {
        val state = runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())
        var cooldownUntil = state.optLong("cooldownUntil", 0)
        val result = SOURCES.map { (id, sub, color) ->
            val entry = state.optJSONObject(id) ?: JSONObject()
            fun cached(error: String?): Pair<Source, List<Post>> {
                val posts = entry.optJSONArray("posts")?.let { a -> (0 until a.length()).map { PostStore.fromJson(a.getJSONObject(it)) } }.orEmpty()
                return source(id, sub, color, error == null && posts.isNotEmpty(), error) to posts
            }
            if (!network) return@map cached(null)
            if (now < cooldownUntil) return@map cached("Reddit이 접근을 제한함 · ${(cooldownUntil - now) / 60000 + 1}분 뒤 다시 시도")
            if (now - entry.optLong("fetchedAt", 0) < MIN_INTERVAL_MS && entry.has("posts")) return@map cached(null)

            fun get(url: String, accept: String): Pair<Int, String> {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", accept)
                return try {
                    val code = conn.responseCode
                    code to (if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else "")
                } finally {
                    conn.disconnect()
                }
            }
            try {
                // 1) 공개 JSON  2) 막히면 공식 RSS 피드 (추천·댓글 수는 없어서 순위로 화제도 계산)
                val (jc, jt) = get("https://www.reddit.com/r/$sub/top.json?t=day&limit=30&raw_json=1", "application/json")
                var posts = if (jc == 200) runCatching { parse(jt, id, now) }.getOrDefault(emptyList()) else emptyList()
                var note = "JSON $jc"
                if (posts.isEmpty()) {
                    val (rc, rt) = get("https://www.reddit.com/r/$sub/top/.rss?t=day&limit=30", "application/atom+xml")
                    note += ", RSS $rc"
                    if (rc == 200) posts = runCatching { parseRss(rt, id, now) }.getOrDefault(emptyList())
                    if (posts.isEmpty() && (rc == 429 || rc == 403)) {
                        cooldownUntil = now + COOLDOWN_MS
                        state.put("cooldownUntil", cooldownUntil)
                        return@map cached("Reddit이 접근을 막음 ($note) · 1시간 뒤 다시 시도")
                    }
                }
                if (posts.isEmpty()) return@map cached("글을 받지 못함 ($note)")
                state.put(id, JSONObject().put("fetchedAt", now).put("posts", JSONArray().also { a -> posts.forEach { a.put(PostStore.toJson(it)) } }))
                source(id, sub, color, true, null) to posts
            } catch (e: Exception) {
                cached(e.message ?: "연결 실패")
            }
        }
        if (network) runCatching { file(ctx).writeText(state.toString()) }
        return result
    }
}
