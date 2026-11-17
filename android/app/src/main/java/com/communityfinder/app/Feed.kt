package com.communityfinder.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Source(
    val id: String,
    val name: String,
    val board: String,
    val color: Long,
    val ok: Boolean,
    val error: String?,
    /** 클라우드 수집이 막혔을 때 휴대폰에서 대신 받아오기 위한 파싱 규칙 */
    val local: LocalSpec? = null,
)

data class Post(
    val id: String,
    val source: String,
    val title: String,
    val url: String,
    val views: Int?,
    val comments: Int?,
    val likes: Int?,
    val score: Int,
    val viewsPerMin: Int,
    val firstSeenAt: Long,
    /** 사진이 본문인 글의 원본 사진 주소 (해외 밈 등) */
    val image: String? = null,
    /** 스레드로 내보낼 때 예상 이미지 장수 (클라우드가 글을 열어 추정. 모르면 null) */
    val pages: Int? = null,
)

data class Feed(val updatedAt: Long, val sources: List<Source>, val posts: List<Post>) {
    val sourceById: Map<String, Source> by lazy { sources.associateBy { it.id } }

    companion object {
        fun parse(json: String): Feed {
            val o = JSONObject(json)
            val sources = o.getJSONArray("sources").let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val st = s.optJSONObject("status")
                    Source(
                        id = s.getString("id"),
                        name = s.getString("name"),
                        board = s.optString("board"),
                        color = parseColor(s.optString("color")),
                        ok = st?.optBoolean("ok", false) ?: false,
                        error = st?.optString("error")?.takeIf { it.isNotEmpty() },
                        local = LocalSpec.parse(s.optJSONObject("local")),
                    )
                }
            }
            val posts = o.getJSONArray("posts").let { arr ->
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    Post(
                        id = p.getString("id"),
                        source = p.getString("source"),
                        title = p.getString("title"),
                        url = p.getString("url"),
                        views = p.optIntOrNull("views"),
                        comments = p.optIntOrNull("comments"),
                        likes = p.optIntOrNull("likes"),
                        score = p.optInt("score", 0),
                        viewsPerMin = p.optInt("viewsPerMin", 0),
                        firstSeenAt = p.optLong("firstSeenAt", 0),
                        image = p.optString("image").takeIf { it.startsWith("https://") },
                        pages = p.optIntOrNull("pages"),
                    )
                }
            }
            return Feed(o.optLong("updatedAt", 0), sources, posts)
        }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) null else optInt(key)

        private fun parseColor(hex: String): Long =
            hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }
                ?: 0xFF888888
    }
}

object FeedRepository {
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "posts.json")

    /** 클라우드에서 최신 피드를 받아오고, 성공하면 캐시에 저장 */
    suspend fun fetch(ctx: Context): Feed = withContext(Dispatchers.IO) {
        val url = Prefs(ctx).feedUrl
        // raw.githubusercontent.com 캐시를 피하려고 쿼리 추가
        val conn = URL("$url?t=${System.currentTimeMillis() / 60000}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) {
                throw Exception(
                    if (conn.responseCode == 404) "아직 클라우드 데이터가 없어요 (수집기가 한 번도 실행되지 않음)"
                    else "서버 응답 ${conn.responseCode}",
                )
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val feed = Feed.parse(text)
            cacheFile(ctx).writeText(text)
            withLocalSources(ctx, feed)
        } finally {
            conn.disconnect()
        }
    }

    fun cached(ctx: Context): Feed? =
        runCatching { Feed.parse(cacheFile(ctx).readText()) }.getOrNull()?.let { withLocalSources(ctx, it, network = false) }

    /** 클라우드에서 막힌 커뮤니티(에펨코리아)를 휴대폰에서 직접 가져와 합침 */
    private fun withLocalSources(ctx: Context, feed: Feed, network: Boolean = true): Feed {
        var f = feed
        val prefs = Prefs(ctx)
        // 화면을 처음 열 때(network=false)는 저장해 둔 결과만 즉시 표시
        if (prefs.fmkoreaLocal) {
            val (src, posts) = if (network) FmkoreaLocal.load(ctx) else FmkoreaLocal.cachedOnly(ctx)
            f = merge(f, src, posts)
        }
        // 클라우드 수집이 실패한 국내 커뮤니티는 휴대폰에서 대신 받아옴 (성공하면 오래된 글을 새 목록으로 교체)
        feed.sources.filter { !it.ok && it.local != null && !isGlobal(it.id) }.forEach { src ->
            LocalFallback.load(ctx, src, network)?.let { (s2, posts) -> if (posts.isNotEmpty()) f = merge(f, s2, posts) else f = f.copy(sources = f.sources.map { if (it.id == s2.id) s2 else it }) }
        }
        if (prefs.redditLocal || prefs.redditClientId.isNotBlank()) RedditLocal.load(ctx, network).forEach { (src, posts) -> f = merge(f, src, posts) }
        return f
    }

    fun merge(feed: Feed, src: Source, posts: List<Post>): Feed = feed.copy(
        sources = feed.sources.filter { it.id != src.id } + src,
        posts = feed.posts.filter { it.source != src.id } + posts,
    )
}

/** 알림 조건: 선택한 커뮤니티의 새 글 중 (화제도 >= 기준) 또는 (제목에 키워드 포함) */
fun matchesAlert(post: Post, minScore: Int, keywords: List<String>, mutedSources: Set<String>): Boolean {
    if (post.source in mutedSources) return false
    val title = post.title.lowercase()
    if (keywords.any { it.isNotBlank() && title.contains(it.trim().lowercase()) }) return true
    return post.score >= minScore
}
