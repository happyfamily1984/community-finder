package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalTest {
    @Test
    fun regionById() {
        assertTrue(isGlobal("reddit-funny") && isGlobal("lemmy-memes") && isGlobal("jp-hatena"))
        assertEquals("ja", langOf("jp-hatena")); assertEquals("en", langOf("lemmy-memes"))
        assertTrue(hasForeignText("猫がかわいい") && hasForeignText("so funny") && !hasForeignText("ㅋㅋ 123"))
        assertFalse(isGlobal("dcinside") || isGlobal("fmkorea"))
    }

    @Test
    fun parsesRedditListing() {
        val json = """{"data":{"children":[
          {"data":{"id":"a1","title":"Funny cat","permalink":"/r/funny/comments/a1/funny_cat/","score":900,"num_comments":50,"over_18":false,"stickied":false,"created_utc":1700000000}},
          {"data":{"id":"a2","title":"nsfw","permalink":"/r/funny/comments/a2/x/","score":5,"num_comments":1,"over_18":true,"stickied":false}},
          {"data":{"id":"a3","title":"Dog","permalink":"/r/funny/comments/a3/dog/","score":100,"num_comments":5,"over_18":false,"stickied":false}}]}}"""
        val posts = RedditLocal.parse(json, "reddit-funny", 0)
        assertEquals(listOf("reddit-funny:a1", "reddit-funny:a3"), posts.map { it.id })
        assertEquals("https://www.reddit.com/r/funny/comments/a1/funny_cat/", posts[0].url)
        assertTrue(posts[0].score > posts[1].score)
        assertEquals(1700000000000L, posts[0].firstSeenAt)
    }
}

class ImageTextFilterTest {
    @Test
    fun filtersOcrNoise() {
        assertTrue(ImageTranslator.worthTranslating("When you finally fix the bug"))
        assertTrue(ImageTranslator.worthTranslating("Monday"))
        assertFalse(ImageTranslator.worthTranslating("o] 7"))
        assertFalse(ImageTranslator.worthTranslating("ㅋㅋ ok"))
    }
}

class ImagePostTest {
    @Test
    fun buildsCleanPageAndEscapes() {
        val html = imagePostHtml("A <b> & \"q\"", "https://i.redd.it/a.jpg?x=1&y=2")
        assertTrue(html.contains("A &lt;b&gt; &amp; &quot;q&quot;"))
        assertTrue(html.contains("src=\"https://i.redd.it/a.jpg?x=1&amp;y=2\""))
    }
}

class GeminiTest {
    @Test
    fun picksNewestStableFlash() {
        fun m(n: String, gen: Boolean = true) = """{"name":"models/$n","supportedGenerationMethods":[${if (gen) "\"generateContent\"" else "\"embedContent\""}]}"""
        val json = """{"models":[${listOf(m("gemini-2.0-flash"), m("gemini-2.5-flash-lite"), m("gemini-2.5-flash"), m("gemini-3.0-flash-preview"),
            m("gemini-2.5-pro"), m("gemini-2.5-flash-image"), m("text-embedding-004", false)).joinToString(",")}]}"""
        assertEquals("gemini-2.5-flash", Gemini.pickModel(json))
    }
}

class GroqTest {
    @Test
    fun picksLargeVersatileModel() {
        val json = """{"data":[{"id":"whisper-large-v3"},{"id":"llama-3.1-8b-instant"},{"id":"llama-3.3-70b-versatile"},{"id":"meta-llama/llama-guard-4-12b"},{"id":"gemma2-9b-it"}]}"""
        assertEquals("llama-3.3-70b-versatile", Groq.pickModel(json))
    }
}

class RedditRssTest {
    @Test
    fun parsesAtomFeed() {
        val xml = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
          <entry><title>Funny cat</title><link href="https://www.reddit.com/r/funny/comments/abc12/funny_cat/"/>
            <content type="html">&lt;a href="https://i.redd.it/xyz9.jpg"&gt;[link]&lt;/a&gt;</content></entry>
          <entry><title>Dog</title><link href="https://www.reddit.com/r/funny/comments/def34/dog/"/><content type="html">text</content></entry></feed>"""
        val posts = RedditLocal.parseRss(xml, "reddit-funny", 5)
        assertEquals(listOf("reddit-funny:abc12", "reddit-funny:def34"), posts.map { it.id })
        assertEquals("https://i.redd.it/xyz9.jpg", posts[0].image)
        assertEquals(null, posts[1].image)
        assertTrue(posts[0].score > posts[1].score)
    }
}
