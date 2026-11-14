package com.communityfinder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// 디자인 시안 색상
val Purple = Color(0xFF5B3DF5)
private val HotRed = Color(0xFFF0304F)
private val HotOrange = Color(0xFFFF7A1A)
private val CalmPurple = Color(0xFF8E5CF7)
private val SortBg = Color(0xFFFFEFE6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 글 스캔(전체 페이지 캡처)용: 첫 WebView가 만들어지기 전에 호출해야 화면 밖 부분까지 그려짐
        android.webkit.WebView.enableSlowWholeDocumentDraw()
        enableEdgeToEdge()
        HotPostWorker.schedule(this)
        setContent { AppTheme { App() } }
    }
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8C78FF), background = Color(0xFF111118), surface = Color(0xFF111118),
            surfaceContainer = Color(0xFF1C1B26), outlineVariant = Color(0xFF2E2C3C),
        )
    } else {
        lightColorScheme(
            primary = Purple, background = Color(0xFFF5F4FB), surface = Color(0xFFF5F4FB),
            surfaceContainer = Color.White, onSurfaceVariant = Color(0xFF8A889C), outlineVariant = Color(0xFFE6E3F3),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    HOME("홈", Icons.Filled.Home, Icons.Outlined.Home),
    EXPORT("내보내기", Icons.Filled.IosShare, Icons.Outlined.IosShare),
    RECENT("최근 본 글", Icons.Outlined.History, Icons.Outlined.History),
    SETTINGS("설정", Icons.Filled.Settings, Icons.Outlined.Settings),
}

enum class SortBy(val label: String) { SCORE("화제도"), SURGE("급상승"), VIEWS("조회수"), COMMENTS("댓글"), LIKES("추천"), LATEST("최신") }

/** 글 열기: 브라우저로 열고 최근 본 글에 기록 */
fun openPost(ctx: Context, p: Post) {
    PostStore.recent(ctx).add(p)
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf(FeedRepository.cached(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // 즐겨찾기/최근 목록이 바뀌면 화면 갱신
    var storeVersion by remember { mutableIntStateOf(0) }
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
    if (tab != Tab.HOME) BackHandler { tab = Tab.HOME }

    val favIds = emptySet<String>()
    // 내보내기(스레드용)로 보낼 글. 글을 길게 누르거나 미리보기에서 '내보내기'
    var exportPost by remember { mutableStateOf<Post?>(null) }
    val toExport: (Post) -> Unit = { p -> exportPost = p; tab = Tab.EXPORT }
    // 글 누르면 앱 안에서 모바일 화면 스캔 이미지로 보기
    var preview by remember { mutableStateOf<Post?>(null) }
    val open: (Post) -> Unit = { PostStore.recent(ctx).add(it); storeVersion++; preview = it }

    if (preview != null) BackHandler { preview = null }


    var onboarding by remember { mutableStateOf(!Prefs(ctx).onboardingDone) }
    if (onboarding) {
        OnboardingScreen { onboarding = false }
        return
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(tab) { tab = it } },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(feed, error, loading, favIds, ::refresh, open, toExport, onBell = { tab = Tab.SETTINGS })
                Tab.EXPORT -> ExportTab(
                    feed = feed, recent = remember(storeVersion) { PostStore.recent(ctx).all() },
                    selected = exportPost, onSelect = { exportPost = it }, onBack = { exportPost = null },
                )
                Tab.RECENT -> SavedScreen(
                    title = "최근 본 글",
                    posts = remember(storeVersion) { PostStore.recent(ctx).all() },
                    empty = "최근에 본 글이 없어요.",
                    feed = feed, favIds = favIds, onOpen = open, onLongPress = toExport,
                    action = "기록 지우기" to { PostStore.recent(ctx).clear(); storeVersion++ },
                )
                Tab.SETTINGS -> SettingsScreen(feed, onBack = { tab = Tab.HOME }, onSetup = { onboarding = true })
            }
        }
    }
    // 글 미리보기(스캔 이미지)는 홈 위에 겹쳐서 띄움 → 돌아오면 목록 위치 그대로
    preview?.let { p -> key(p.id) {
        PreviewScreen(
            post = p,
            source = feed?.sourceById?.get(p.source) ?: knownSource(p.source),
            onExport = { preview = null; toExport(p) },
            onOpenSite = { openPost(ctx, p) },
            onBack = { preview = null },
        )
    } }
    }
}

@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Tab.entries.forEach { t ->
            val sel = t == current
            NavigationBarItem(
                selected = sel,
                onClick = { onSelect(t) },
                icon = { Icon(if (sel) t.on else t.off, null) },
                label = { Text(t.label, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun Header(showDot: Boolean, onBell: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(10.dp))
        Text("커뮤니티 파인더", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        IconButton(onClick = onBell) {
            Box {
                Icon(Icons.Filled.Notifications, "알림 설정", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                if (showDot) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(HotRed, CircleShape))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    feed: Feed?, error: String?, loading: Boolean, favIds: Set<String>,
    onRefresh: () -> Unit, onOpen: (Post) -> Unit, onLongPress: (Post) -> Unit, onBell: () -> Unit,
) {
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
    val hasNew = remember(feed) { feed?.posts?.any { System.currentTimeMillis() - it.firstSeenAt < 15 * 60_000 } == true }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(hasNew, onBell)
        PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { SourceChip("전체", null, source == "", ok = true) { source = "" } }
                        items(feed?.sources.orEmpty()) { s ->
                            SourceChip(s.name, Color(s.color), source == s.id, s.ok) { source = s.id }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SearchPill(query, { query = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Box {
                            Surface(onClick = { sortMenu = true }, shape = CircleShape, color = SortBg, modifier = Modifier.height(44.dp)) {
                                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🔥", fontSize = 16.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(sort.label, color = HotOrange, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
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
                        EmptyText(
                            when {
                                feed == null && error == null -> "불러오는 중…"
                                feed == null -> "데이터를 받지 못했어요. 아래로 당겨 다시 시도하세요."
                                else -> "표시할 글이 없어요"
                            },
                        )
                    }
                }
                items(posts, key = { it.id }) { p ->
                    PostCard(p, feed?.sourceById?.get(p.source), p.id in favIds, { onOpen(p) }, { onLongPress(p) })
                }
            }
        }
    }
}

@Composable
fun SavedScreen(
    title: String, posts: List<Post>, empty: String, feed: Feed?, favIds: Set<String>,
    onOpen: (Post) -> Unit, onLongPress: (Post) -> Unit, action: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (action != null && posts.isNotEmpty()) TextButton(onClick = action.second) { Text(action.first) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            if (posts.isEmpty()) item { EmptyText(empty) }
            items(posts, key = { it.id }) { p ->
                PostCard(p, feed?.sourceById?.get(p.source) ?: knownSource(p.source), p.id in favIds, { onOpen(p) }, { onLongPress(p) })
            }
        }
    }
}

fun knownSource(id: String): Source? = if (id == FmkoreaLocal.ID) FmkoreaLocal.SOURCE_TEMPLATE else null

@Composable
fun EmptyText(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp,
    )
}

@Composable
fun SourceChip(label: String, dot: Color?, selected: Boolean, ok: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .height(38.dp)
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).background(if (selected) Color.White else dot, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                if (ok) label else "$label ⚠",
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = if (ok) 0.85f else 0.45f),
            )
        }
    }
}

@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("제목 검색", color = muted, fontSize = 15.sp)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
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
    if (parts.isEmpty()) return
    val color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.History, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(parts.joinToString(" · "), fontSize = 12.sp, color = color, maxLines = 2)
    }
}

fun scoreColor(score: Int) = when {
    score >= 85 -> HotRed
    score >= 75 -> HotOrange
    else -> CalmPurple
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostCard(p: Post, src: Source?, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = scoreColor(p.score)
    val srcColor = src?.let { Color(it.color) } ?: Color.Gray
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽 색 막대
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.width(54.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${p.score}", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = accent, lineHeight = 24.sp)
            Text("화제도", fontSize = 10.sp, color = muted, lineHeight = 12.sp)
        }
        Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            // 제목: 줄간격을 좁혀 한 화면에 더 많은 글이 보이도록
            Text(
                p.title, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    src?.name ?: p.source, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = srcColor,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(srcColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                if (System.currentTimeMillis() - p.firstSeenAt < 15 * 60_000) {
                    Text(" NEW", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = HotRed)
                }
                p.views?.let { Stat(Icons.Outlined.Visibility, fmt(it)) }
                p.comments?.let { Stat(Icons.Outlined.ChatBubbleOutline, fmt(it)) }
                p.likes?.let { Spacer(Modifier.width(8.dp)); Text("👍 ${fmt(it)}", fontSize = 12.sp, color = muted, maxLines = 1) }
                if (p.viewsPerMin >= 50) {
                    Text("  🚀${fmt(p.viewsPerMin)}/분", fontSize = 12.sp, color = HotOrange, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text("  " + ago(p.firstSeenAt), fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        Column(Modifier.padding(end = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (favorite) Icon(Icons.Filled.Star, "즐겨찾기", tint = Color(0xFFFFB800), modifier = Modifier.size(14.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, text: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Spacer(Modifier.width(8.dp))
    Icon(icon, null, tint = muted, modifier = Modifier.size(13.dp))
    Text(" $text", fontSize = 12.sp, color = muted, maxLines = 1)
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
