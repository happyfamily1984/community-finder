package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmkoreaTest {
    private val html = javaClass.classLoader!!.getResource("fmkorea-best.html")!!.readText()

    @Test
    fun parsesBestList() {
        val posts = FmkoreaLocal.parse(html)
        assertEquals(listOf("8839201234", "8839205555", "8839207777"), posts.map { it.no })
        assertEquals("손흥민 오늘 경기 해트트릭", posts[0].title)
        assertEquals(312, posts[0].comments)
        assertEquals(1024, posts[0].likes)
        assertEquals("편의점 신상 후기", posts[1].title)
        assertEquals(null, posts[2].comments)
    }

    @Test
    fun scoresRelativeWithinSource() {
        val scores = FmkoreaLocal.score(FmkoreaLocal.parse(html))
        assertEquals(85, scores[0])
        assertTrue(scores[0] > scores[1] && scores[1] > scores[2])
    }

    @Test
    fun mergeReplacesSameSource() {
        val feed = Feed(1, listOf(FmkoreaLocal.SOURCE_TEMPLATE), listOf(Post("fmkorea:old", "fmkorea", "old", "u", null, null, null, 1, 0, 0)))
        val merged = FeedRepository.merge(feed, FmkoreaLocal.SOURCE_TEMPLATE.copy(ok = true), emptyList())
        assertEquals(1, merged.sources.size)
        assertTrue(merged.sources[0].ok)
        assertTrue(merged.posts.isEmpty())
    }
}
