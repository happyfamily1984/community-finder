package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalTest {
    @Test
    fun regionById() {
        assertTrue(isGlobal("reddit-funny") && isGlobal("lemmy-memes"))
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
