package com.communityfinder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadsKitTest {
    private val post = Post("a:1", "natepann", "이혼 직전에 부부상담 받아본 후기", "u", 26268, 120, 38, 84, 0, 0)

    @Test
    fun sourceLineFormat() {
        assertEquals("디시", ThreadsKit.sourceLine("dcinside", "디시인사이드"))
        assertEquals("네이트판", ThreadsKit.sourceLine("natepann", "네이트판"))
    }

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

    // w=100 → 한 장 최대 200px, 여러 장일 때 목표 160px
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

    @Test
    fun blankRowIgnoresThinSideBorders() {
        val white = 0xFFFFFFFF.toInt(); val gray = 0xFF888888.toInt(); val black = 0xFF000000.toInt()
        val bordered = IntArray(1080) { white }.also { it[0] = gray; it[1] = gray; it[1078] = gray; it[1079] = gray }
        assertTrue(ThreadsKit.isBlankRow(bordered))
        // 회색 바탕 + 가운데 흰 카드 (네이트판 같은 레이아웃)
        val card = IntArray(1080) { if (it in 40..1040) white else 0xFFF2F2F2.toInt() }.also { it[40] = gray; it[1040] = gray }
        assertTrue(ThreadsKit.isBlankRow(card))
        val text = IntArray(1080) { if (it in 100..400 && it % 3 == 0) black else white }
        assertTrue(!ThreadsKit.isBlankRow(text))
    }

    @Test
    fun fallsBackToFarGapInsteadOfCuttingText() {
        // 목표 지점(200) 근처엔 여백이 없고 멀리(110~120)에만 있음 → 그 여백에서 자름
        val cuts = ThreadsKit.cutPoints(rows(400, listOf(110..120)), 100)
        assertEquals(115, cuts[1])
    }

    @Test
    fun tallImageIsCutAtItsOwnGaps() {
        // 높이 600짜리 긴 사진 하나(글 캡처)가 본문 전체: 사진 안의 줄 사이 여백(195~205, 395~405)에서 자름
        val cuts = ThreadsKit.cutPoints(rows(600, listOf(195..205, 395..405)), 100, avoid = listOf(0..600))
        assertEquals(listOf(0, 200, 400, 600), cuts)
    }

    @Test
    fun collapsesLargeGapsButNotInsideImages() {
        // 0~99 글, 100~299 큰 여백, 300~399 글, 400~599 사진(안에 빈 줄 450~549), 600~699 끝 여백
        val blank = rows(700, listOf(100..299, 450..549, 600..699))
        val keep = ThreadsKit.keepRanges(blank, avoid = listOf(400..599), maxGap = 40)
        assertEquals(listOf(0..119, 280..619), keep) // 가운데 여백은 20+20만 남기고, 사진 속 빈 줄은 유지, 끝 여백은 20만 남김
    }
}
