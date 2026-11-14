package com.communityfinder.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Accent = Color(0xFFFF5A36)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HotPostWorker.schedule(this)
        setContent { AppTheme { App() } }
    }
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(primary = Accent, background = Color(0xFF111114), surface = Color(0xFF111114), surfaceContainer = Color(0xFF1B1B20))
    } else {
        lightColorScheme(primary = Accent, background = Color(0xFFF6F6F8), surface = Color(0xFFF6F6F8), surfaceContainer = Color.White)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

enum class SortBy(val label: String) { SCORE("🔥 화제도"), SURGE("🚀 급상승"), VIEWS("👀 조회"), COMMENTS("💬 댓글"), LIKES("👍 추천"), LATEST("🕒 최신") }

@Composable
fun App() {
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf(FeedRepository.cached(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            try {
                feed = FeedRepository.fetch(ctx)
                error = null
            } catch (e: Exception) {
                error = e.message ?: "불러오기 실패"
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(feed, onBack = { showSettings = false })
    } else {
        FeedScreen(feed, error, loading, onRefresh = ::refresh, onSettings = { showSettings = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(feed: Feed?, error: String?, loading: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var source by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.SCORE) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }

    val posts = remember(feed, source, sort, query) {
        val list = feed?.posts.orEmpty()
            .filter { source.isEmpty() || it.source == source }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        when (sort) {
            SortBy.SCORE -> list.sortedByDescending { it.score }
            SortBy.SURGE -> list.sortedByDescending { it.viewsPerMin }
            SortBy.VIEWS -> list.sortedByDescending { it.views ?: -1 }
            SortBy.COMMENTS -> list.sortedByDescending { it.comments ?: -1 }
            SortBy.LIKES -> list.sortedByDescending { it.likes ?: -1 }
            SortBy.LATEST -> list.sortedByDescending { it.firstSeenAt }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("커뮤니티 파인더", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Notifications, "알림 설정") }
                },
            )
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item { FilterChip(selected = source == "", onClick = { source = "" }, label = { Text("전체") }) }
                        items(feed?.sources.orEmpty()) { s ->
                            FilterChip(
                                selected = source == s.id,
                                onClick = { source = s.id },
                                enabled = true,
                                label = { Text(if (s.ok) s.name else "${s.name} ⚠") },
                                leadingIcon = { Box(Modifier.size(8.dp).background(Color(s.color), CircleShape)) },
                            )
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("제목 검색") },
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            OutlinedButton(onClick = { sortMenu = true }) { Text(sort.label) }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortBy.entries.forEach {
                                    DropdownMenuItem(text = { Text(it.label) }, onClick = { sort = it; sortMenu = false })
                                }
                            }
                        }
                    }
                }
                item { StatusLine(feed, error) }

                if (posts.isEmpty()) {
                    item {
                        Text(
                            when {
                                feed == null && error == null -> "불러오는 중…"
                                feed == null -> "데이터를 받지 못했어요. 아래로 당겨 다시 시도하세요."
                                else -> "표시할 글이 없어요"
                            },
                            Modifier.fillMaxWidth().padding(40.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(posts, key = { it.id }) { p ->
                    PostCard(p, feed?.sourceById?.get(p.source)) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                    }
                }
            }
        }
    }
}

@Composable
fun StatusLine(feed: Feed?, error: String?) {
    val failed = feed?.sources?.filter { !it.ok }?.map { it.name }.orEmpty()
    val parts = buildList {
        if (feed != null) add("클라우드 수집 ${ago(feed.updatedAt)}")
        if (failed.isNotEmpty()) add("수집 실패: ${failed.joinToString()}")
        if (error != null) add("⚠ $error")
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            fontSize = 12.sp,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun PostCard(p: Post, src: Source?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${p.score}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (p.score >= 80) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface,
                )
                Text("화제도", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(4.dp))
                val meta = buildList {
                    p.views?.let { add("👀 ${fmt(it)}") }
                    p.comments?.let { add("💬 ${fmt(it)}") }
                    p.likes?.let { add("👍 ${fmt(it)}") }
                    if (p.viewsPerMin >= 50) add("🚀 분당 ${fmt(p.viewsPerMin)}")
                    add(ago(p.firstSeenAt))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        src?.name ?: p.source,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = src?.let { Color(it.color) } ?: Color.Gray,
                    )
                    if (System.currentTimeMillis() - p.firstSeenAt < 15 * 60_000) {
                        Text("  NEW", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                    Text(
                        "  " + meta.joinToString("  "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(feed: Feed?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.notifyEnabled && HotPostWorker.canNotify(ctx)) }
    var minScore by remember { mutableFloatStateOf(prefs.minScore.toFloat()) }
    var keywords by remember { mutableStateOf(prefs.keywordsText) }
    var muted by remember { mutableStateOf(prefs.mutedSources) }
    var interval by remember { mutableIntStateOf(prefs.intervalMin) }
    var feedUrl by remember { mutableStateOf(prefs.feedUrl) }
    var message by remember { mutableStateOf<String?>(null) }

    fun apply() {
        prefs.notifyEnabled = enabled
        prefs.minScore = minScore.toInt()
        prefs.keywordsText = keywords
        prefs.mutedSources = muted
        prefs.intervalMin = interval
        HotPostWorker.schedule(ctx)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted
        message = if (granted) null else "알림 권한이 거부됐어요. 휴대폰 설정 > 앱 > 커뮤니티 파인더에서 알림을 허용해주세요."
        apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("알림 설정") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("화제글 알림", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "새 글이 베스트에 오르면, 아래 조건에 맞는 글만 알려드려요.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !HotPostWorker.canNotify(ctx)) {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        enabled = on
                        apply()
                    }
                })
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            HorizontalDivider()
            Text("화제도 ${minScore.toInt()}점 이상", fontWeight = FontWeight.SemiBold)
            Slider(
                value = minScore, onValueChange = { minScore = it }, onValueChangeFinished = ::apply,
                valueRange = 50f..100f, steps = 9,
            )

            Text("키워드 알림", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it; apply() },
                placeholder = { Text("예: 아이폰, 로또, 손흥민") },
                supportingText = { Text("쉼표로 구분 · 제목에 들어가면 점수와 상관없이 알림") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("알림 받을 커뮤니티", fontWeight = FontWeight.SemiBold)
            feed?.sources?.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        muted = if (s.id in muted) muted - s.id else muted + s.id
                        apply()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = s.id !in muted, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("${s.name} · ${s.board}")
                }
            } ?: Text("피드를 받은 뒤에 선택할 수 있어요", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("확인 주기", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { m ->
                    FilterChip(selected = interval == m, onClick = { interval = m; apply() }, label = { Text("${m}분") })
                }
            }
            Text(
                "안드로이드 배터리 정책상 최소 15분이며, 절전 모드에서는 더 늦어질 수 있어요.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    val top = feed?.posts?.maxByOrNull { it.score }
                    if (!HotPostWorker.canNotify(ctx)) {
                        message = "먼저 알림을 켜주세요."
                    } else if (feed != null && top != null) {
                        HotPostWorker.notify(ctx, feed, listOf(top))
                    }
                },
            ) { Text("테스트 알림 보내기") }

            HorizontalDivider()
            Text("고급: 클라우드 피드 주소", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it; prefs.feedUrl = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun fmt(n: Int): String = when {
    n >= 10000 -> String.format("%.1f만", n / 10000.0).replace(".0만", "만")
    else -> "%,d".format(n)
}

fun ago(ts: Long): String {
    val m = ((System.currentTimeMillis() - ts) / 60000).toInt()
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 1440 -> "${m / 60}시간 전"
        else -> "${m / 1440}일 전"
    }
}
