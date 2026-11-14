package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsKitTest {
    private val post = Post("a:1", "natepann", "이혼 직전에 부부상담 받아본 후기", "u", 26268, 120, 38, 84, 0, 0)

    @Test
    fun sourceLineFormat() = assertEquals("출처 : 네이트판", ThreadsKit.sourceLine("네이트판"))

    @Test
    fun moodFromKeywords() {
        assertEquals(ThreadsKit.Mood.INFO, ThreadsKit.moodOf(post.title, ""))
        assertEquals(ThreadsKit.Mood.FUNNY, ThreadsKit.moodOf("고양이 짤 ㅋㅋㅋ", ""))
        assertEquals(ThreadsKit.Mood.ISSUE, ThreadsKit.moodOf("배우 음주 사고 논란", ""))
        assertEquals(ThreadsKit.Mood.DEFAULT, ThreadsKit.moodOf("오늘 점심", ""))
    }

    @Test
    fun reviewContainsTitleAndVaries() {
        val a = ThreadsKit.review(post, "네이트판", "", 0)
        assertTrue(a.contains(post.title) && a.contains("조회 2.6만"))
        assertNotEquals(a, ThreadsKit.review(post, "네이트판", "", 1))
    }
}
