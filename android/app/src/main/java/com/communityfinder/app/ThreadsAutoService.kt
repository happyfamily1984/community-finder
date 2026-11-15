package com.communityfinder.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** 스레드 앱 자동화 작업: (1) 새 글 본문 자동 입력 (2) 계정 전환 */
object ThreadsAuto {
    class Job(val text: String, val startedAt: Long = System.currentTimeMillis(), var composeClicked: Boolean = false)
    class SwitchJob(val account: String, val startedAt: Long = System.currentTimeMillis(), var lastActionAt: Long = 0, var actions: Int = 0, var scrolls: Int = 0)

    @Volatile var pending: Job? = null
    @Volatile var switching: SwitchJob? = null

    fun start(text: String) { pending = Job(text) }

    /** 계정 전환 시작: 스레드 앱을 열고, 접근성 서비스가 프로필 → 설정 → 로그아웃 → 계정 선택을 대신 누름 */
    fun startSwitch(ctx: Context, account: String): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ThreadsKit.THREADS_PACKAGE) ?: return false
        switching = SwitchJob(account.trim().removePrefix("@"))
        ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun isEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, ThreadsAutoService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}

/**
 * 스레드 앱 화면에서만 동작하는 접근성 서비스.
 * - 새 글: 글쓰기 화면을 열고 본문을 입력 (게시 버튼은 누르지 않음)
 * - 계정 전환: 이미 원하는 계정이면 아무것도 하지 않고, 아니면 로그아웃 후 저장된 계정 목록에서 원하는 계정을 누름
 *   (비밀번호는 다루지 않음. 스레드 앱에 저장된 로그인만 사용)
 */
class ThreadsAutoService : AccessibilityService() {
    private val composeLabels = listOf("새 스레드", "새로운 스레드", "만들기", "작성", "New thread", "Create", "Compose")
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (ThreadsAuto.switching != null) { runCatching { stepSwitch() }; handler.postDelayed(this, 900) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (ThreadsAuto.switching != null) {
            handler.removeCallbacks(tick); handler.postDelayed(tick, 400)
            return
        }
        val job = ThreadsAuto.pending ?: return
        val elapsed = System.currentTimeMillis() - job.startedAt
        if (elapsed > 90_000) { ThreadsAuto.pending = null; return }
        val root = rootInActiveWindow ?: return
        if (root.packageName != ThreadsKit.THREADS_PACKAGE) return

        val input = find(root) { it.isEditable }
        if (input != null) {
            val current = if (input.isShowingHintText) "" else input.text?.toString().orEmpty()
            if (current.contains(job.text.take(15))) { finish("본문을 입력했어요. 확인 후 게시를 눌러주세요."); return }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (current.isBlank()) job.text else current + "\n" + job.text)
            }
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) finish("본문을 입력했어요. 확인 후 게시를 눌러주세요.")
            return
        }
        // 글쓰기 화면이 아직 아니면 (공유로 바로 안 열렸을 때) 새 글 버튼을 한 번 누름
        if (!job.composeClicked && elapsed > 2500) {
            val btn = find(root) { n -> label(n).let { l -> l.isNotEmpty() && composeLabels.any { l.equals(it, true) || l.startsWith(it, true) } } }
            if (btn != null && click(btn)) job.composeClicked = true
        }
    }

    // ---------------- 계정 전환 ----------------

    private fun stepSwitch() {
        val job = ThreadsAuto.switching ?: return
        val now = System.currentTimeMillis()
        if (now - job.startedAt > 60_000 || job.actions > 25) { endSwitch("계정 전환을 끝내지 못했어요. 스레드에서 직접 전환해 주세요."); return }
        if (now - job.lastActionAt < 1100) return // 화면이 바뀔 시간을 줌
        val root = rootInActiveWindow ?: return
        if (root.packageName != ThreadsKit.THREADS_PACKAGE) return
        fun acted() { job.lastActionAt = System.currentTimeMillis(); job.actions++ }
        fun has(text: String) = root.findAccessibilityNodeInfosByText(text).orEmpty().isNotEmpty()
        fun exact(text: String) = root.findAccessibilityNodeInfosByText(text).orEmpty().firstOrNull { label(it).trim().equals(text, true) }
        val screen = Rect().also { root.getBoundsInScreen(it) }

        // 1) 로그인 화면(저장된 계정 목록): 원하는 계정을 누르면 끝
        if (has("로그인 또는 가입") || has("다른 프로필 사용") || has("Log in or sign up")) {
            val target = exact(job.account) ?: root.findAccessibilityNodeInfosByText(job.account).orEmpty().firstOrNull()
            if (target != null && click(target)) endSwitch("${job.account} 계정으로 로그인했어요.")
            else if (target == null && job.actions > 3) endSwitch("로그인 화면에 ${job.account} 계정이 없어요. 스레드에 그 계정으로 한 번 로그인해 두세요.")
            else job.actions++
            return
        }
        // 2) 로그아웃 확인 창
        if (has("로그아웃하시겠어요") || has("Log out of")) {
            val btn = root.findAccessibilityNodeInfosByText("로그아웃").orEmpty().firstOrNull { label(it).trim() == "로그아웃" }
                ?: root.findAccessibilityNodeInfosByText("Log out").orEmpty().firstOrNull { label(it).trim().equals("Log out", true) }
            if (btn != null && click(btn)) acted()
            return
        }
        // 3) 설정 화면: 맨 아래 '로그아웃'까지 내려가서 누름
        if ((has("Meta 계정") || has("개인정보 보호") || has("설정 더 보기")) && (exact("설정") != null || has("Meta 계정"))) {
            val logout = exact("로그아웃") ?: exact("Log out")
            if (logout != null && click(logout)) { acted(); return }
            val scroller = find(root) { it.isScrollable }
            if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true || swipeUp(screen)) { job.scrolls++; acted() }
            return
        }
        // 4) 프로필 화면: 이미 원하는 계정이면 끝, 아니면 오른쪽 위 설정(톱니바퀴)
        if (has("프로필 편집") || has("Edit profile")) {
            if (exact(job.account) != null || exact("@${job.account}") != null) { endSwitch("이미 ${job.account} 계정이에요."); return }
            val gear = find(root) { n -> label(n).let { it == "설정" || it.equals("Settings", true) || it == "메뉴" || it == "더 보기" } }
                ?: topRightClickable(root, screen)
            if ((gear != null && click(gear)) || tap(screen.width() * 0.927f, screen.top + screen.height() * 0.036f)) acted()
            return
        }
        // 5) 그 밖의 화면(홈 등): 아래쪽 맨 오른쪽 프로필 탭
        val profileTab = find(root) { n -> label(n).let { it == "프로필" || it.equals("Profile", true) } && n.isClickable }
            ?: bottomRightClickable(root, screen)
        if ((profileTab != null && click(profileTab)) || tap(screen.width() * 0.866f, screen.top + screen.height() * 0.962f)) acted()
    }

    private fun endSwitch(message: String) {
        ThreadsAuto.switching = null
        handler.removeCallbacks(tick)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun topRightClickable(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null; var bestRight = 0
        walk(root) { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (n.isClickable && r.height() in 1..(screen.height() / 10) && r.centerY() < screen.top + screen.height() * 0.08f && r.right > bestRight) { best = n; bestRight = r.right }
        }
        return best
    }

    private fun bottomRightClickable(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null; var bestRight = 0
        walk(root) { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (n.isClickable && r.width() < screen.width() / 3 && r.centerY() > screen.top + screen.height() * 0.92f && r.right > bestRight) { best = n; bestRight = r.right }
        }
        return best
    }

    // ---------------- 공통 도구 ----------------

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString().orEmpty()

    /** 누를 수 있는 부모를 찾아 클릭, 안 되면 그 위치를 터치 */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var t: AccessibilityNodeInfo? = node
        while (t != null && !t.isClickable) t = t.parent
        if (t?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val r = Rect().also { node.getBoundsInScreen(it) }
        return !r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun tap(x: Float, y: Float): Boolean = runCatching {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }.getOrDefault(false)

    private fun swipeUp(screen: Rect): Boolean = runCatching {
        val x = screen.exactCenterX()
        val path = Path().apply { moveTo(x, screen.top + screen.height() * 0.8f); lineTo(x, screen.top + screen.height() * 0.25f) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
    }.getOrDefault(false)

    private fun finish(message: String) {
        ThreadsAuto.pending = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 3000) {
            val n = queue.removeFirst()
            visit(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }

    private fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        walk(root) { if (hit == null && match(it)) hit = it }
        return hit
    }

    override fun onInterrupt() {}
}
