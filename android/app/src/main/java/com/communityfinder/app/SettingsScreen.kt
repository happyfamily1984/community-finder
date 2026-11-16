package com.communityfinder.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Lavender = Color(0xFFEDE9FF)

/** 설정 화면 (디자인 시안: 카드형 섹션 + 3D 종 일러스트) */
@Composable
fun SettingsScreen(feed: Feed?, onBack: (() -> Unit)? = null, onSetup: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.notifyEnabled && HotPostWorker.canNotify(ctx)) }
    var minScore by remember { mutableFloatStateOf(prefs.minScore.toFloat()) }
    var keywords by remember { mutableStateOf(prefs.keywordsText) }
    var muted by remember { mutableStateOf(prefs.mutedSources) }
    var interval by remember { mutableIntStateOf(prefs.intervalMin) }
    var feedUrl by remember { mutableStateOf(prefs.feedUrl) }
    var fmLocal by remember { mutableStateOf(prefs.fmkoreaLocal) }
    var redditId by remember { mutableStateOf(prefs.redditClientId) }
    var geminiKey by remember { mutableStateOf(prefs.geminiKey) }
    var geminiStatus by remember { mutableStateOf<String?>(null) }
    var threadsAccount by remember { mutableStateOf(prefs.threadsAccount) }
    var switchMsg by remember { mutableStateOf<String?>(null) }
    var switchLog by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf(prefs.hitExamples) }
    var hitDraft by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf(prefs.groqKey) }
    var groqStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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

    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(bg)) {
        // 상단 배경: 은은한 보라 그라데이션 + 큰 원
        Box(
            Modifier.fillMaxWidth().height(200.dp)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), bg))),
        )
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-70).dp).size(240.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), CircleShape),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            // 헤더
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp).height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", modifier = Modifier.size(26.dp)) }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text("알림 설정", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = 6.dp))
                Image(painterResource(R.drawable.bell_3d), null, Modifier.size(64.dp))
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 화제글 알림
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.bell_3d), null, Modifier.size(56.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("화제글 알림", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "새 글이 베스트에 오르면,\n아래 조건에 맞는 글만 알려드려요.",
                                fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PurpleSwitch(enabled) { on ->
                            if (on && Build.VERSION.SDK_INT >= 33 && !HotPostWorker.canNotify(ctx)) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                enabled = on
                                apply()
                            }
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }

                // 화제도 기준
                SettingsCard {
                    SectionTitle(Icons.Filled.BarChart, "화제도 ${minScore.toInt()}점 이상")
                    Spacer(Modifier.height(4.dp))
                    ScoreSlider(minScore, { minScore = it }, ::apply)
                }

                // 키워드 알림
                SettingsCard {
                    SectionTitle(Icons.Filled.Keyboard, "키워드 알림")
                    Spacer(Modifier.height(12.dp))
                    OutlinedPillField(keywords, { keywords = it; apply() }, "예: 아이폰, 로또, 손흥민", 56.dp, 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("쉼표로 구분 · 제목에 들어가면 점수와 상관없이 알림", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 알림 받을 커뮤니티
                SettingsCard {
                    SectionTitle(Icons.Filled.CheckCircle, "알림 받을 커뮤니티")
                    Spacer(Modifier.height(6.dp))
                    val sources = feed?.sources.orEmpty().filter { !isGlobal(it.id) }
                    if (sources.isEmpty()) {
                        Text("피드를 받은 뒤에 선택할 수 있어요", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    sources.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                muted = if (s.id in muted) muted - s.id else muted + s.id
                                apply()
                            }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PurpleCheck(s.id !in muted)
                            Spacer(Modifier.width(14.dp))
                            Text("${s.name} · ${s.board}", fontSize = 16.sp)
                        }
                    }
                }

                // 에펨코리아
                SettingsCard {
                    AccentTitle("에펨코리아 휴대폰에서 가져오기")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "에펨코리아는 클라우드 서버 접근을 막아서, 이 휴대폰에서 15분에 한 번 포텐 목록만 받아와요.",
                                fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "에펨코리아가 접근을 제한하면 알려준 시간만큼 쉬었다가 다시 시도해요.",
                                fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        PurpleSwitch(fmLocal) { fmLocal = it; prefs.fmkoreaLocal = it }
                    }
                }

                // 스레드 계정 전환
                SettingsCard {
                    AccentTitle("스레드 계정 전환")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "버튼을 누르면 스레드 앱을 열어, 지금 계정이 아래 계정이 아니면 로그아웃하고 저장된 계정 목록에서 아래 계정으로 다시 로그인해요. 이미 그 계정이면 아무것도 하지 않아요. (접근성의 '스레드 자동 입력'이 켜져 있어야 해요)",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillField(threadsAccount, { threadsAccount = it; prefs.threadsAccount = it }, "계정 이름 (예: utgingeul_archive)", 50.dp, 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(shape = CircleShape, enabled = threadsAccount.isNotBlank(), onClick = {
                        switchMsg = when {
                            !ThreadsAuto.isEnabled(ctx) -> "먼저 접근성에서 '커뮤니티 파인더 · 스레드 자동 입력'을 켜주세요. (아래 '처음 설정 다시 보기')"
                            !ThreadsAuto.startSwitch(ctx, prefs.threadsAccount) -> "스레드 앱이 설치되어 있지 않아요."
                            else -> null
                        }
                    }) { Text("이 계정으로 전환", fontWeight = FontWeight.Bold) }
                    switchMsg?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { switchLog = ThreadsAuto.logText() }) { Text("전환 기록 보기") }
                    switchLog?.let { logText ->
                        AlertDialog(
                            onDismissRequest = { switchLog = null },
                            title = { Text("전환 기록") },
                            text = { Text(logText, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                            confirmButton = { TextButton(onClick = { ThreadsKit.copy(ctx, "전환 기록", logText); switchLog = null }) { Text("복사하기") } },
                            dismissButton = { TextButton(onClick = { switchLog = null }) { Text("닫기") } },
                        )
                    }
                }

                // 요약 본보기
                SettingsCard {
                    AccentTitle("잘 터진 글 (요약 본보기)")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "스레드에서 반응이 좋았던 글을 붙여넣어 추가하면, 앞으로 요약할 때 그 글의 구조와 말투를 본받아요. 기본 본보기 ${Summarizer.BUILTIN_EXAMPLES.size}개 + 내가 추가한 ${hits.size}개 (최근 5개까지 사용)",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        hitDraft, { hitDraft = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        placeholder = { Text("잘 된 글 본문을 여기에 붙여넣기", fontSize = 14.sp) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(shape = CircleShape, enabled = hitDraft.trim().length >= 20, onClick = {
                            hits = (hits + hitDraft.trim()).takeLast(20); prefs.hitExamples = hits; hitDraft = ""
                        }) { Text("본보기로 추가", fontWeight = FontWeight.Bold) }
                        OutlinedButton(shape = CircleShape, enabled = hits.isNotEmpty(), onClick = { hits = hits.dropLast(1); prefs.hitExamples = hits }) { Text("마지막 것 삭제") }
                    }
                    hits.lastOrNull()?.let { Spacer(Modifier.height(8.dp)); Text("마지막으로 추가한 글: " + it.lineSequence().first().take(30) + "…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                // 번역 엔진
                SettingsCard {
                    AccentTitle("해외 글 번역 (Gemini 무료 키)")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "키를 넣으면 밈·속어까지 자연스럽게 번역해요. 무료 한도를 다 쓰면 자동으로 예비 번역(Groq → 휴대폰 내장)으로 바뀌고, 1시간 뒤 다시 Gemini를 써요.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillField(geminiKey, { geminiKey = it; prefs.geminiKey = it; geminiStatus = null }, "API 키 붙여넣기 (AIza…)", 50.dp, 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = CircleShape, onClick = {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey")))
                        }) { Text("키 발급 페이지", fontWeight = FontWeight.Bold) }
                        Button(shape = CircleShape, enabled = geminiKey.isNotBlank(), onClick = {
                            geminiStatus = "확인 중…"
                            scope.launch {
                                val r = Gemini.translate(ctx, listOf("This meme is so relatable lol"), force = true)
                                geminiStatus = if (r != null) "✅ 연결됨 (${prefs.geminiModel}) → ${r[0]}" else "⚠ ${Gemini.lastError ?: "실패"}"
                            }
                        }) { Text("번역 테스트", fontWeight = FontWeight.Bold) }
                    }
                    geminiStatus?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
                    if (Gemini.inCooldown(prefs)) {
                        Spacer(Modifier.height(6.dp))
                        Text("지금은 Gemini 무료 한도를 다 써서 예비 번역을 쓰고 있어요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                // 번역 엔진 2순위
                SettingsCard {
                    AccentTitle("예비 번역 (Groq 무료 키)")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Gemini 한도가 떨어지면 Groq(무료 AI 번역)으로 넘어가요. 둘 다 안 되면 휴대폰 내장 번역을 써요. 순서: Gemini → Groq → 내장 번역",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillField(groqKey, { groqKey = it; prefs.groqKey = it; groqStatus = null }, "API 키 붙여넣기 (gsk_…)", 50.dp, 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shape = CircleShape, onClick = {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.groq.com/keys")))
                        }) { Text("키 발급 페이지", fontWeight = FontWeight.Bold) }
                        Button(shape = CircleShape, enabled = groqKey.isNotBlank(), onClick = {
                            groqStatus = "확인 중…"
                            scope.launch {
                                val r = Groq.translate(ctx, listOf("This meme is so relatable lol"))
                                groqStatus = if (r != null) "✅ 연결됨 (${prefs.groqModel}) → ${r[0]}" else "⚠ ${Groq.lastError ?: "실패"}"
                            }
                        }) { Text("번역 테스트", fontWeight = FontWeight.Bold) }
                    }
                    groqStatus?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
                }

                // Reddit
                SettingsCard {
                    AccentTitle("Reddit 연결 (해외 탭)")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reddit은 로그인 없는 접근을 막아서, 무료로 앱을 등록해 받은 '클라이언트 ID'가 필요해요.\n" +
                            "① 아래 버튼으로 Reddit 앱 등록 페이지를 열고 로그인\n" +
                            "② 'create another app…' → 이름 아무거나, 종류는 installed app, redirect uri는 http://localhost\n" +
                            "③ 만들어진 앱 이름 바로 아래의 짧은 문자열(클라이언트 ID)을 복사해 여기에 붙여넣기",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedPillField(redditId, { redditId = it; prefs.redditClientId = it }, "클라이언트 ID 붙여넣기", 50.dp, 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(shape = CircleShape, onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.reddit.com/prefs/apps")))
                    }) { Text("Reddit 앱 등록 페이지", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(6.dp))
                    Text("붙여넣은 뒤 해외 탭을 아래로 당겨 새로고침하면 Reddit 글이 나와요. 실패하면 이유가 목록 위에 표시돼요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 확인 주기
                SettingsCard {
                    AccentTitle("확인 주기")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { m -> IntervalPill("${m}분", interval == m) { interval = m; apply() } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "안드로이드 배터리 정책상 최소 15분이며, 절전 모드에서는 더 늦어질 수 있어요.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        onClick = {
                            val top = feed?.posts?.maxByOrNull { it.score }
                            if (!HotPostWorker.canNotify(ctx)) {
                                message = "먼저 화제글 알림을 켜주세요."
                            } else if (feed != null && top != null) {
                                HotPostWorker.notify(ctx, feed, listOf(top))
                            }
                        },
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("테스트 알림 보내기", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }

                if (onSetup != null) {
                    SettingsCard {
                        AccentTitle("처음 설정 다시 보기")
                        Spacer(Modifier.height(8.dp))
                        Text("알림 · 배터리 · 스레드 자동 입력(접근성) 설정을 순서대로 안내해요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onSetup, shape = CircleShape) { Text("설정 안내 열기", fontWeight = FontWeight.Bold) }
                    }
                }

                // 고급
                SettingsCard {
                    AccentTitle("고급: 클라우드 피드 주소")
                    Spacer(Modifier.height(12.dp))
                    OutlinedPillField(feedUrl, { feedUrl = it; prefs.feedUrl = it }, "", 50.dp, 13.sp)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x225B3DF5), spotColor = Color(0x225B3DF5))
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun AccentTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PurpleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
private fun PurpleCheck(checked: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
            .background(if (checked) primary else Color.Transparent)
            .border(2.dp, if (checked) primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun IntervalPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.width(84.dp).height(44.dp)
            .then(
                if (selected) Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF7B61FF), primary)), CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun OutlinedPillField(value: String, onChange: (String) -> Unit, placeholder: String, height: androidx.compose.ui.unit.Dp, size: androidx.compose.ui.unit.TextUnit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.fillMaxWidth().height(height)
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = muted.copy(alpha = 0.8f), fontSize = size)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontSize = size, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 0~100점 슬라이더 + 아래 눈금 라벨 (현재 값이 손잡이 아래에 보라색으로 표시) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreSlider(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column {
        Slider(
            value = value, onValueChange = { onChange(Math.round(it).toFloat()) }, onValueChangeFinished = onDone,
            valueRange = 0f..100f, steps = 19, // 5점 단위
            modifier = Modifier.onSizeChanged { widthPx = it.width },
            colors = SliderDefaults.colors(
                thumbColor = primary, activeTrackColor = primary, inactiveTrackColor = primary.copy(alpha = 0.15f),
                activeTickColor = Color.White, inactiveTickColor = primary.copy(alpha = 0.5f),
            ),
        )
        Box(Modifier.fillMaxWidth().height(20.dp)) {
            Text("0점", fontSize = 13.sp, color = muted, modifier = Modifier.align(Alignment.CenterStart))
            Text("100점", fontSize = 13.sp, color = muted, modifier = Modifier.align(Alignment.CenterEnd))
            if (value in 8f..92f && widthPx > 0) {
                val x = with(density) { (widthPx * value / 100f).toDp() } - 18.dp
                Text("${value.toInt()}점", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = primary, modifier = Modifier.offset(x = x))
            }
        }
    }
}
