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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(feed: Feed?, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.notifyEnabled && HotPostWorker.canNotify(ctx)) }
    var minScore by remember { mutableFloatStateOf(prefs.minScore.toFloat()) }
    var keywords by remember { mutableStateOf(prefs.keywordsText) }
    var muted by remember { mutableStateOf(prefs.mutedSources) }
    var interval by remember { mutableIntStateOf(prefs.intervalMin) }
    var feedUrl by remember { mutableStateOf(prefs.feedUrl) }
    var fmLocal by remember { mutableStateOf(prefs.fmkoreaLocal) }
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
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("에펨코리아 휴대폰에서 가져오기", fontWeight = FontWeight.SemiBold)
                    Text(
                        "에펨코리아는 클라우드 서버 접근을 막아서, 이 휴대폰에서 15분에 한 번 포텐 목록만 받아와요. " +
                            "에펨코리아가 접근을 제한하면 알려준 시간만큼 쉬었다가 다시 시도해요.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = fmLocal, onCheckedChange = { fmLocal = it; prefs.fmkoreaLocal = it })
            }

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

