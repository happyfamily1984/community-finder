package com.communityfinder.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupTest {
    @Test
    fun backupRoundTrip() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val p = Prefs(app)
        p.geminiKey = "g-key"; p.groqKey = "q-key"; p.hitExamples = listOf("첫 줄\n\n둘째 줄", "다른 글"); p.threadsAccount = "acc"; p.minScore = 70
        val text = "메모: " + p.exportBackup() + " 끝"
        p.geminiKey = ""; p.groqKey = ""; p.hitExamples = emptyList(); p.threadsAccount = "x"; p.minScore = 90
        assertTrue(p.importBackup(text))
        assertEquals("g-key", p.geminiKey); assertEquals("q-key", p.groqKey)
        assertEquals(listOf("첫 줄\n\n둘째 줄", "다른 글"), p.hitExamples)
        assertEquals("acc", p.threadsAccount); assertEquals(70, p.minScore)
        assertFalse(p.importBackup("{\"app\":\"other\"}")); assertFalse(p.importBackup("깨진 글자"))
    }
}
