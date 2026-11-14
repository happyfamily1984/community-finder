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
    fun reviewIsTwoCasualLines() {
        val a = ThreadsKit.review(post, "", 0)
        assertEquals(2, a.split("\n\n").size)
        assertTrue(!a.contains(post.title))
        assertNotEquals(a, ThreadsKit.review(post, "", 1))
    }

    // w=100 → 한 장 최대 177px, 여러 장일 때 목표 135px
    private fun rows(h: Int, blankAt: List<IntRange>) = BooleanArray(h) { y -> blankAt.any { y in it } }

    @Test
    fun shortPostStaysSinglePiece() {
        assertEquals(listOf(0, 170), ThreadsKit.cutPoints(rows(170, listOf(80..90)), 100))
    }

    @Test
    fun cutsAtGapsNotInsideImages() {
        // 높이 400: 여백이 130~140, 200~210(사진 안의 검은 띠), 270~280
        val blank = rows(400, listOf(130..140, 200..210, 270..280))
        val cuts = ThreadsKit.cutPoints(blank, 100, avoid = listOf(150..260))
        assertEquals(listOf(0, 135, 275, 400), cuts)
    }

    @Test
    fun noTinyLastPiece() {
        val cuts = ThreadsKit.cutPoints(rows(300, emptyList()), 100) // 여백이 전혀 없어도 고르게
        val sizes = cuts.zipWithNext { a, b -> b - a }
        assertTrue(sizes.all { it >= 90 })
    }
}
