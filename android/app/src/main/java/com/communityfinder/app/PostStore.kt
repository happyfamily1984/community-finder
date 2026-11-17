package com.communityfinder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 즐겨찾기 · 최근 본 글 (글 정보를 그대로 저장해서 베스트에서 빠져도 볼 수 있음) */
class PostStore(ctx: Context, name: String, private val max: Int) {
    private val file = File(ctx.applicationContext.filesDir, "$name.json")

    fun all(): List<Post> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun contains(id: String) = all().any { it.id == id }

    /** 맨 앞에 추가 (이미 있으면 앞으로 이동) */
    fun add(post: Post) = save(listOf(post) + all().filter { it.id != post.id })

    fun remove(id: String) = save(all().filter { it.id != id })

    fun clear() = save(emptyList())

    private fun save(list: List<Post>) {
        val arr = JSONArray()
        list.take(max).forEach { arr.put(toJson(it)) }
        file.writeText(arr.toString())
    }

    companion object {
        fun favorites(ctx: Context) = PostStore(ctx, "favorites", 500)
        fun recent(ctx: Context) = PostStore(ctx, "recent", 100)

        private fun JSONObject.intOrNull(k: String) = if (!has(k) || isNull(k)) null else optInt(k)

        fun toJson(p: Post): JSONObject = JSONObject()
            .put("id", p.id).put("source", p.source).put("title", p.title).put("url", p.url)
            .put("views", p.views ?: JSONObject.NULL).put("comments", p.comments ?: JSONObject.NULL)
            .put("likes", p.likes ?: JSONObject.NULL).put("score", p.score)
            .put("viewsPerMin", p.viewsPerMin).put("firstSeenAt", p.firstSeenAt).put("image", p.image ?: JSONObject.NULL).put("pages", p.pages ?: JSONObject.NULL)

        fun fromJson(o: JSONObject) = Post(
            id = o.getString("id"), source = o.getString("source"), title = o.getString("title"),
            url = o.getString("url"), views = o.intOrNull("views"), comments = o.intOrNull("comments"),
            likes = o.intOrNull("likes"), score = o.optInt("score"), viewsPerMin = o.optInt("viewsPerMin"),
            firstSeenAt = o.optLong("firstSeenAt"),
            image = o.optString("image").takeIf { it.startsWith("https://") },
            pages = o.intOrNull("pages"),
        )
    }
}
