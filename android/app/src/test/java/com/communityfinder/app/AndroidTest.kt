package com.communityfinder.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AndroidTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun feed() = Feed.parse(javaClass.classLoader!!.getResource("demo-feed.json")!!.readText())

    @Test
    fun firstCheckPrimesThenNotifiesOnlyNewHotPosts() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val prefs = Prefs(app).apply { minScore = 85; keywordsText = "고양이"; mutedSources = setOf("ruliweb") }
        val full = feed()
        val old = full.copy(posts = full.posts.drop(10))

        assertEquals(emptyList<Post>(), HotPostWorker.checkAndNotify(app, old))
        assertEquals(0, nm.allNotifications.size)

        val sent = HotPostWorker.checkAndNotify(app, full)
        val newOnes = full.posts.take(10)
        val expected = newOnes.filter { it.source != "ruliweb" && (it.score >= 85 || "고양이" in it.title) }
        assertEquals(expected.map { it.id }, sent.map { it.id }.sortedBy { id -> newOnes.indexOfFirst { it.id == id } })
        assertTrue(nm.allNotifications.isNotEmpty())

        // 같은 피드를 다시 확인하면 중복 알림 없음
        assertEquals(emptyList<Post>(), HotPostWorker.checkAndNotify(app, full))
        assertTrue(prefs.seenIds!!.containsAll(full.posts.map { it.id }))
    }

    @Test
    fun feedScreenLight() {
        compose.setContent { AppTheme(dark = false) { HomeScreen(feed(), null, false, emptySet(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/screens/feed-light.png")
    }

    @Test
    fun feedScreenDark() {
        compose.setContent { AppTheme(dark = true) { HomeScreen(feed(), null, false, emptySet(), {}, {}, {}, {}) } }
        compose.onRoot().captureRoboImage("build/screens/feed-dark.png")
    }

    @Test
    fun settingsScreen() {
        compose.setContent { AppTheme(dark = false) { SettingsScreen(feed()) } }
        compose.onRoot().captureRoboImage("build/screens/settings.png")
    }
}
