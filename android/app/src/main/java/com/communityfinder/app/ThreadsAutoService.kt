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
    class SwitchJob(
        val account: String, val startedAt: Long = System.currentTimeMillis(), var lastActionAt: Long = 0, var actions: Int = 0, var scrolls: Int = 0,
        var step: String = "", var stepSince: Long = 0, var stepTries: Int = 0,
    )

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
        clearLog(); log("전환 시작 → $account (v1.7.4)")
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
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (ThreadsAuto.switching != null) { runCatching { stepSwitch() }; handler.postDelayed(this, 350) } else ticking = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (ThreadsAuto.switching != null) {
            // 화면이 계속 바뀌어도 밀리지 않도록, 반복 실행은 한 번만 시작해 두고 건드리지 않음
            if (!ticking) { ticking = true; handler.postDelayed(tick, 200) }
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

    /**
     * 계정 전환 한 걸음. 0.35초마다 불림.
     * 같은 단계에서 1초 넘게 진행이 없으면(두 가지 방법으로 눌러도 화면이 안 바뀌면) 바로 실패로 알림.
     */
    private fun stepSwitch() {
        val job = ThreadsAuto.switching ?: return
        val now = System.currentTimeMillis()
        if (now - job.startedAt > 25_000) { endSwitch("계정 전환 실패: 시간이 너무 오래 걸려요 (${job.step})"); return }
        val root = rootInActiveWindow
        if (root == null || root.packageName != ThreadsKit.THREADS_PACKAGE) {
            // 스레드 앱이 뜨는 동안은 최대 4초 기다림
            if (now - job.startedAt > 4000 && now - job.lastActionAt > 1500) endSwitch("계정 전환 실패: 스레드 화면이 아니에요 (${root?.packageName ?: "화면 정보 없음"})")
            return
        }
        if (now - job.lastActionAt < 600) return // 누른 뒤 화면이 바뀔 시간

        fun has(text: String) = root.findAccessibilityNodeInfosByText(text).orEmpty().isNotEmpty()
        fun exact(text: String) = root.findAccessibilityNodeInfosByText(text).orEmpty().firstOrNull { label(it).trim().equals(text, true) }
        val screen = Rect().also { root.getBoundsInScreen(it) }

        // 지금 어느 화면인지
        val step = when {
            has("로그인 또는 가입") || has("다른 프로필 사용") || has("Log in or sign up") -> "로그인 화면"
            has("로그아웃하시겠어요") || has("Log out of") -> "로그아웃 확인"
            has("Meta 계정") || has("설정 더 보기") || (exact("설정") != null && has("개인정보 보호")) -> "설정"
            has("프로필 편집") || has("Edit profile") -> "프로필"
            else -> "홈"
        }
        val stepKey = if (step == "설정") "설정#${job.scrolls}" else step
        if (stepKey != job.step) { job.step = stepKey; job.stepSince = now; job.stepTries = 0; ThreadsAuto.log("[$step]") }
        // 같은 화면에서 두 번 눌러도 안 바뀌고 1초가 지났으면 실패
        if (job.stepTries >= 2 && now - job.stepSince > 1000) { endSwitch("계정 전환 실패: '$step' 단계에서 다음으로 넘어가지 않아요"); return }

        fun press(what: String, node: AccessibilityNodeInfo?) {
            // 화면이 아직 그려지는 중일 수 있으니, 버튼이 안 보여도 1.5초까지는 기다림
            if (node == null && now - job.stepSince < 1500) return
            ThreadsAuto.log("  $what ${describe(node)}")
            job.stepTries++
            if (node != null && click(node)) { job.lastActionAt = System.currentTimeMillis(); job.actions++ }
        }

        when (step) {
            "로그인 화면" -> {
                val target = exact(job.account) ?: root.findAccessibilityNodeInfosByText(job.account).orEmpty().firstOrNull()
                ThreadsAuto.log("  계정 ${describe(target)}")
                job.stepTries++
                if (target != null && click(target)) endSwitch("${job.account} 계정으로 로그인했어요.")
                else if (target == null && job.stepTries >= 2) endSwitch("계정 전환 실패: 로그인 화면에 ${job.account} 계정이 없어요. 스레드에 그 계정으로 한 번 로그인해 두세요.")
            }
            "로그아웃 확인" -> press("확인 버튼",
                root.findAccessibilityNodeInfosByText("로그아웃").orEmpty().firstOrNull { label(it).trim() == "로그아웃" }
                    ?: root.findAccessibilityNodeInfosByText("Log out").orEmpty().firstOrNull { label(it).trim().equals("Log out", true) })
            "설정" -> {
                val logout = exact("로그아웃") ?: exact("Log out")
                if (logout != null) press("로그아웃", logout)
                else if (job.scrolls >= 8) endSwitch("계정 전환 실패: 설정에서 로그아웃 메뉴를 찾지 못했어요")
                else {
                    val scroller = find(root) { it.isScrollable }
                    val ok = scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true || swipeUp(screen)
                    ThreadsAuto.log("  로그아웃이 안 보여서 아래로 스크롤 ${if (ok) "" else "(실패)"}")
                    job.scrolls++; job.lastActionAt = System.currentTimeMillis()
                }
            }
            "프로필" -> {
                if (exact(job.account) != null || exact("@${job.account}") != null) { endSwitch("이미 ${job.account} 계정이에요."); return }
                val gear = byLabel(root, listOf("설정", "Settings", "메뉴", "Menu", "더 보기", "More")) { r -> r.centerY() < screen.top + screen.height() * 0.2f && r.centerX() > screen.width() / 2 }
                    ?: byId(root, listOf("setting", "menu"))
                    ?: topRightClickable(root, screen)
                if (gear == null && job.stepTries == 0) dump(root, screen, top = true)
                press("설정 버튼", gear)
            }
            else -> {
                // 이름표("프로필") → id에 profile이 들어간 탭 → 탭 바 줄의 맨 오른쪽 순으로 찾음. 찾은 버튼의 위치만 누름
                val bottom = { r: Rect -> r.centerY() > screen.top + screen.height() * 0.7f }
                val tab = byLabel(root, listOf("프로필", "Profile"), bottom) ?: byId(root, listOf("profile"), bottom) ?: bottomRightClickable(root, screen)
                if (tab == null && job.stepTries == 0) dump(root, screen, top = false)
                press("프로필 탭", tab)
            }
        }
    }

    private fun endSwitch(message: String) {
        ThreadsAuto.switching = null
        ThreadsAuto.log("끝: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
