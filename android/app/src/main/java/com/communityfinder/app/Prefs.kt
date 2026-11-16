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

    /** Reddit을 휴대폰에서 직접 가져오기 (클라우드 수집이 막혀 있어서) */
    var redditLocal: Boolean
        get() = sp.getBoolean("redditLocal", false)
        set(v) = sp.edit().putBoolean("redditLocal", v).apply()

    /** Gemini 번역용 API 키 (사용자가 구글 AI Studio에서 무료 발급). 비어 있으면 휴대폰 내장 번역 */
    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) { sp.edit().putString("geminiKey", v.trim()).putString("geminiModel", "").putString("geminiModels", "").putLong("geminiCooldown", 0).apply() }

    /** 시도할 Gemini 모델 후보 (쉼표 구분) */
    var geminiModels: String
        get() = sp.getString("geminiModels", "") ?: ""
        set(v) = sp.edit().putString("geminiModels", v).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", "") ?: ""
        set(v) = sp.edit().putString("geminiModel", v).apply()

    var geminiCooldownUntil: Long
        get() = sp.getLong("geminiCooldown", 0)
        set(v) = sp.edit().putLong("geminiCooldown", v).apply()

    /** Groq 번역용 API 키 (무료 발급). Gemini 다음 순서로 사용 */
    var groqKey: String
        get() = sp.getString("groqKey", "") ?: ""
        set(v) { sp.edit().putString("groqKey", v.trim()).putString("groqModel", "").putLong("groqCooldown", 0).apply() }

    var groqModel: String
        get() = sp.getString("groqModel", "") ?: ""
        set(v) = sp.edit().putString("groqModel", v).apply()

    var groqCooldownUntil: Long
        get() = sp.getLong("groqCooldown", 0)
        set(v) = sp.edit().putLong("groqCooldown", v).apply()

    /** Reddit 공식 API용 클라이언트 ID (reddit.com/prefs/apps 에서 무료 등록한 installed app) */
    var redditClientId: String
        get() = sp.getString("redditClientId", "") ?: ""
        set(v) = sp.edit().putString("redditClientId", v.trim()).apply()

    /** 계정 전환으로 로그인할 스레드 계정 이름 */
    var threadsAccount: String
        get() = sp.getString("threadsAccount", "utgingeul_archive") ?: "utgingeul_archive"
        set(v) = sp.edit().putString("threadsAccount", v.trim().removePrefix("@")).apply()

    /** 반응이 좋았던 스레드 글 (요약 본보기로 사용). 사용자가 설정에서 추가 */
    var hitExamples: List<String>
        get() = runCatching { org.json.JSONArray(sp.getString("hitExamples", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("hitExamples", org.json.JSONArray(v).toString()).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()

    var feedUrl: String
        get() = sp.getString("feedUrl", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FEED_URL
        set(v) = sp.edit().putString("feedUrl", v.trim()).apply()

    /** 이미 확인한 글 id. null이면 아직 기준점을 잡지 않은 상태 */
    var seenIds: Set<String>?
        get() = sp.getStringSet("seen", null)
        set(v) = sp.edit().putStringSet("seen", v).apply()
}
