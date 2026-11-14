package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
    private val json = javaClass.classLoader!!.getResource("sample-feed.json")!!.readText()

    @Test
    fun parsesCrawlerFeed() {
        val feed = Feed.parse(json)
        assertEquals(7, feed.sources.size)
        assertEquals(3, feed.posts.size)
        assertTrue(feed.sources[0].ok)
        assertFalse(feed.sources[1].ok)
        assertEquals("HTTP 403", feed.sources[1].error)
        assertNull(feed.posts[2].views)
        assertEquals(0xFF3B4890, feed.sourceById["dcinside"]!!.color)
    }

    @Test
    fun alertRules() {
        val p = Post("a:1", "a", "로또 1등 당첨", "https://x", 1, 1, 1, score = 60, viewsPerMin = 0, firstSeenAt = 0)
        assertTrue(matchesAlert(p, 85, listOf("로또"), emptySet()))
        assertFalse(matchesAlert(p, 85, emptyList(), emptySet()))
        assertTrue(matchesAlert(p.copy(score = 90), 85, emptyList(), emptySet()))
        assertFalse(matchesAlert(p.copy(score = 90), 85, listOf("로또"), setOf("a")))
    }

    @Test
    fun numberFormat() {
        assertEquals("1,234", fmt(1234))
        assertEquals("1.2만", fmt(12345))
        assertEquals("3만", fmt(30000))
    }
}
