package com.communityfinder.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", false)
        set(v) = sp.edit().putBoolean("notify", v).apply()

    var minScore: Int
        get() = sp.getInt("minScore", 85)
        set(v) = sp.edit().putInt("minScore", v).apply()

    var keywordsText: String
        get() = sp.getString("keywords", "") ?: ""
        set(v) = sp.edit().putString("keywords", v).apply()

    val keywords: List<String>
        get() = keywordsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 알림을 받지 않을 커뮤니티 (기본: 전부 받음) */
    var mutedSources: Set<String>
        get() = sp.getStringSet("muted", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("muted", v).apply()

    var intervalMin: Int
        get() = sp.getInt("interval", 15)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** 에펨코리아를 휴대폰에서 직접 가져오기 (클라우드 수집이 막혀 있어서) */
    var fmkoreaLocal: Boolean
        get() = sp.getBoolean("fmkoreaLocal", true)
        set(v) = sp.edit().putBoolean("fmkoreaLocal", v).apply()

    var feedUrl: String
        get() = sp.getString("feedUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FEED_URL
        set(v) = sp.edit().putString("feedUrl", v.trim()).apply()

    /** 이미 확인한 글 id. null이면 아직 기준점을 잡지 않은 상태 */
    var seenIds: Set<String>?
        get() = sp.getStringSet("seen", null)
        set(v) = sp.edit().putStringSet("seen", v).apply()
}
