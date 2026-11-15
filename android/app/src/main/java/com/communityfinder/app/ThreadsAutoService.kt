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

    /** 계정 전환 기록 (문제 파악용). 설정 화면에서 볼 수 있음 */
    private val logBuf = StringBuilder()
    @Synchronized fun log(line: String) {
        if (logBuf.length > 12000) logBuf.delete(0, 4000)
        logBuf.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append(' ').append(line).append('\n')
    }
    @Synchronized fun logText(): String = logBuf.toString().ifEmpty { "기록이 없어요. '이 계정으로 전환'을 먼저 눌러주세요." }
    @Synchronized fun clearLog() { logBuf.setLength(0) }

    @Volatile var pending: Job? = null
    @Volatile var switching: SwitchJob? = null

    fun start(text: String) { pending = Job(text) }

    /** 계정 전환 시작: 스레드 앱을 열고, 접근성 서비스가 프로필 → 설정 → 로그아웃 → 계정 선택을 대신 누름 */
    fun startSwitch(ctx: Context, account: String): Boolean {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ThreadsKit.THREADS_PACKAGE) ?: return false
        switching = SwitchJob(account.trim().removePrefix("@"))
        clearLog(); log("전환 시작 → $account (v1.7.3)")
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
        val root = rootInActiveWindow ?: run { ThreadsAuto.log("화면 정보를 못 읽음(root 없음)"); job.actions++; return }
        if (root.packageName != ThreadsKit.THREADS_PACKAGE) { ThreadsAuto.log("스레드가 아닌 화면: ${root.packageName}"); job.actions++; job.lastActionAt = now; return }
        fun acted() { job.lastActionAt = System.currentTimeMillis(); job.actions++ }
        fun has(text: String) = root.findAccessibilityNodeInfosByText(text).orEmpty().isNotEmpty()
        fun exact(text: String) = root.findAccessibilityNodeInfosByText(text).orEmpty().firstOrNull { label(it).trim().equals(text, true) }
        val screen = Rect().also { root.getBoundsInScreen(it) }

        // 1) 로그인 화면(저장된 계정 목록): 원하는 계정을 누르면 끝
        if (has("로그인 또는 가입") || has("다른 프로필 사용") || has("Log in or sign up")) {
            val target = exact(job.account) ?: root.findAccessibilityNodeInfosByText(job.account).orEmpty().firstOrNull()
            ThreadsAuto.log("로그인 화면 · 계정 ${if (target != null) "찾음" else "못 찾음"}")
            if (target != null && click(target)) endSwitch("${job.account} 계정으로 로그인했어요.")
            else if (target == null && job.actions > 3) endSwitch("로그인 화면에 ${job.account} 계정이 없어요. 스레드에 그 계정으로 한 번 로그인해 두세요.")
            else job.actions++
            return
        }
        // 2) 로그아웃 확인 창
        if (has("로그아웃하시겠어요") || has("Log out of")) {
            val btn = root.findAccessibilityNodeInfosByText("로그아웃").orEmpty().firstOrNull { label(it).trim() == "로그아웃" }
                ?: root.findAccessibilityNodeInfosByText("Log out").orEmpty().firstOrNull { label(it).trim().equals("Log out", true) }
            ThreadsAuto.log("로그아웃 확인 창 · 버튼 ${if (btn != null) "찾음" else "못 찾음"}")
            if (btn != null && click(btn)) acted() else job.actions++
            return
        }
        // 3) 설정 화면: 맨 아래 '로그아웃'까지 내려가서 누름
        if ((has("Meta 계정") || has("개인정보 보호") || has("설정 더 보기")) && (exact("설정") != null || has("Meta 계정"))) {
            val logout = exact("로그아웃") ?: exact("Log out")
            ThreadsAuto.log("설정 화면 · 로그아웃 ${if (logout != null) "찾음" else "안 보임 → 스크롤"}")
            if (logout != null && click(logout)) { acted(); return }
            val scroller = find(root) { it.isScrollable }
            if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true || swipeUp(screen)) { job.scrolls++; acted() }
            return
        }
        // 4) 프로필 화면: 이미 원하는 계정이면 끝, 아니면 오른쪽 위 설정(톱니바퀴)
        if (has("프로필 편집") || has("Edit profile")) {
            if (exact(job.account) != null || exact("@${job.account}") != null) { endSwitch("이미 ${job.account} 계정이에요."); return }
            val gear = byLabel(root, listOf("설정", "Settings", "메뉴", "Menu", "더 보기", "More")) { r -> r.centerY() < screen.top + screen.height() * 0.2f && r.centerX() > screen.width() / 2 }
                ?: byId(root, listOf("setting", "menu"))
                ?: topRightClickable(root, screen)
            ThreadsAuto.log("프로필 화면 · 설정 버튼 ${describe(gear)}")
            if (gear != null && click(gear)) acted() else { job.actions++; if (job.actions % 4 == 1) dump(root, screen, top = true) }
            return
        }
        // 5) 그 밖의 화면(홈 등): 아래쪽 맨 오른쪽 프로필 탭
        // 이름표("프로필") → id에 profile이 들어간 탭 → 탭 바 줄의 맨 오른쪽 순으로 찾음. 찾은 버튼의 위치만 누름
        val bottom = { r: Rect -> r.centerY() > screen.top + screen.height() * 0.7f }
        val profileTab = byLabel(root, listOf("프로필", "Profile"), bottom)
            ?: byId(root, listOf("profile"), bottom)
            ?: bottomRightClickable(root, screen)
        ThreadsAuto.log("그 밖의 화면 · 프로필 탭 ${describe(profileTab)}")
        if (profileTab != null && click(profileTab)) acted() else { job.actions++; if (job.actions % 4 == 1) dump(root, screen, top = false) }
    }

    private fun describe(n: AccessibilityNodeInfo?): String {
        n ?: return "못 찾음"
        val r = Rect().also { n.getBoundsInScreen(it) }
        return "찾음 [${n.className?.toString()?.substringAfterLast('.')} '${label(n).take(20)}' ${n.viewIdResourceName?.substringAfterLast('/') ?: ""} $r]"
    }

    /** 문제 파악용: 화면 위쪽/아래쪽 띠에 있는 요소들을 기록 */
    private fun dump(root: AccessibilityNodeInfo, screen: Rect, top: Boolean) {
        var count = 0
        ThreadsAuto.log("── 화면 요소(${if (top) "위" else "아래"}쪽) · 화면 $screen ──")
        walk(root) { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            val inBand = if (top) r.centerY() < screen.top + screen.height() * 0.2f else r.centerY() > screen.top + screen.height() * 0.7f
            if (inBand && !r.isEmpty && r.width() < screen.width() * 0.6f && count++ < 40) {
                ThreadsAuto.log("  ${n.className?.toString()?.substringAfterLast('.')} '${label(n).take(16)}' id=${n.viewIdResourceName?.substringAfterLast('/') ?: "-"} click=${n.isClickable} $r")
            }
        }
    }

    private fun byLabel(root: AccessibilityNodeInfo, labels: List<String>, where: (Rect) -> Boolean = { true }): AccessibilityNodeInfo? =
        find(root) { n -> label(n).trim().let { l -> labels.any { l.equals(it, true) } } && where(Rect().also { n.getBoundsInScreen(it) }) }

    private fun byId(root: AccessibilityNodeInfo, parts: List<String>, where: (Rect) -> Boolean = { true }): AccessibilityNodeInfo? =
        find(root) { n -> n.viewIdResourceName?.substringAfterLast('/')?.lowercase()?.let { id -> parts.any { id.contains(it) } } == true && where(Rect().also { n.getBoundsInScreen(it) }) }

    private fun endSwitch(message: String) {
        ThreadsAuto.switching = null
        ThreadsAuto.log("끝: $message")
        handler.removeCallbacks(tick)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** 화면의 한 가로줄에 나란히 놓인 작은 버튼들 (탭 바, 상단 아이콘 줄) 찾기 */
    private fun buttonRows(root: AccessibilityNodeInfo, screen: Rect, inBand: (Rect) -> Boolean): Map<Int, List<Pair<AccessibilityNodeInfo, Rect>>> {
        val list = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        walk(root) { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            // 누를 수 있다고 표시되지 않은 아이콘도 포함 (스레드 탭은 그렇게 표시되지 않을 수 있음)
            val iconLike = n.isClickable || n.childCount == 0 || n.className?.toString()?.contains("Image") == true || n.className?.toString()?.contains("Button") == true
            if (iconLike && n.isVisibleToUser && !r.isEmpty && r.width() in 24..(screen.width() / 3) && r.height() in 24..(screen.height() / 8) && inBand(r)) list += n to r
        }
        return list.groupBy { it.second.centerY() / 48 }
    }

    /** 스레드 상단 오른쪽 끝 아이콘(설정): 화면 위쪽 20% 안에서 가장 위 줄의 맨 오른쪽 버튼 */
    private fun topRightClickable(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        val rows = buttonRows(root, screen) { it.centerY() < screen.top + screen.height() * 0.2f }
        return rows.minByOrNull { it.key }?.value?.maxByOrNull { it.second.right }?.first
    }

    /** 스레드 아래 탭 바의 맨 오른쪽(프로필): 화면 아래 30% 안에서 버튼이 4개 이상 나란한 줄 중 가장 아래 줄의 맨 오른쪽 */
    private fun bottomRightClickable(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        val rows = buttonRows(root, screen) { it.centerY() > screen.top + screen.height() * 0.7f }
        return rows.filter { it.value.size >= 4 }.maxByOrNull { it.key }?.value?.maxByOrNull { it.second.right }?.first
    }

    // ---------------- 공통 도구 ----------------

    private fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString().orEmpty()

    private var clickAttempt = 0

    /**
     * 요소 누르기. 요소 자체가 '누를 수 있음'이면 그대로 클릭.
     * 아니면 (1) 그 위치를 실제 터치처럼 누르기 (2) 누를 수 있는 부모에 클릭 보내기 를 번갈아 시도
     * — 스레드 탭은 부모에 클릭을 보내도 반응이 없는 경우가 있음.
     */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { ThreadsAuto.log("  → 직접 클릭"); return true }
        val useTouch = clickAttempt++ % 2 == 0
        if (useTouch && !r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())) { ThreadsAuto.log("  → 터치 (${r.centerX()}, ${r.centerY()})"); return true }
        var t: AccessibilityNodeInfo? = node.parent
        while (t != null && !t.isClickable) t = t.parent
        if (t?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            ThreadsAuto.log("  → 부모 클릭 [${t.className?.toString()?.substringAfterLast('.')} ${Rect().also { b -> t.getBoundsInScreen(b) }}]"); return true
        }
        if (!useTouch && !r.isEmpty && tap(r.exactCenterX(), r.exactCenterY())) { ThreadsAuto.log("  → 터치 (${r.centerX()}, ${r.centerY()})"); return true }
        ThreadsAuto.log("  → 누르기 실패")
        return false
    }

    /** 휴대폰 맨 아래 내비게이션 바(홈/뒤로/최근 앱)가 시작되는 y 좌표. 그 아래는 절대 누르지 않음 */
    private fun navBarTop(): Int {
        val wm = getSystemService(android.view.WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            m.bounds.bottom - m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
        } else {
            (resources.displayMetrics.heightPixels * 0.94f).toInt()
        }
    }

    private fun tap(x: Float, y: Float): Boolean = runCatching {
        if (y >= navBarTop() - 4) { ThreadsAuto.log("  누르려는 위치가 휴대폰 내비게이션 바라서 취소 (y=$y, 바 시작=${navBarTop()})"); return false }
        val path = Path().apply { moveTo(x, y) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
        if (!ok) ThreadsAuto.log("  터치 권한이 없음 → 접근성에서 '스레드 자동 입력'을 껐다 켜주세요")
        ok
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
