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
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-3.0-flash-preview"), Gemini.rankModels(json))
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

class SummarizerTest {
    @Test
    fun cleansAndLimitsLength() {
        assertEquals("주식에 전재산 넣었다가 망했다.", Summarizer.clean("요약: \"주식에 전재산 넣었다가 망했다.\""))
        assertEquals("첫 줄이다.\n\n둘째 줄.\n\n셋째 줄.", Summarizer.clean("첫 줄이다.\n\n\n- 둘째 줄.\n\n2. 셋째 줄."))
        val long = "가나다라마바사. ".repeat(100)
        val c = Summarizer.clean(long)
        assertTrue(c.length <= Summarizer.MAX_SUMMARY_CHARS && c.endsWith("."))
    }

    @Test
    fun usesLongerBodyText() {
        val input = Summarizer.buildInput("제목", "짧음", "이미지에서 읽은 긴 본문 내용")
        assertTrue(input.startsWith("제목: 제목") && input.contains("이미지에서 읽은 긴 본문 내용") && !input.contains("짧음"))
    }
}
