package com.communityfinder.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 내보내기 탭: 글 선택 → 스레드용 자료 만들기 */
@Composable
fun ExportTab(feed: Feed?, recent: List<Post>, selected: Post?, onSelect: (Post) -> Unit, onBack: () -> Unit) {
    if (selected != null) {
        BackHandler(onBack = onBack)
        key(selected.id) {
            ExportDetail(selected, feed?.sourceById?.get(selected.source) ?: knownSource(selected.source), onBack)
        }
        return
    }
    val hot = remember(feed) { feed?.posts.orEmpty().sortedByDescending { it.score }.take(50) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("내보내기", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp))
        Text(
            "스레드에 올릴 글을 고르세요. 글 내용을 한 화면 크기로 나눈 이미지와 제목·출처·감상평을 만들어 드려요.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            if (recent.isNotEmpty()) {
                item { SectionLabel("최근 본 글") }
                items(recent.take(5), key = { "r" + it.id }) { p -> PostCard(p, feed?.sourceById?.get(p.source) ?: knownSource(p.source), false, { onSelect(p) }, { onSelect(p) }) }
            }
            item { SectionLabel("지금 화제글") }
            if (hot.isEmpty()) item { EmptyText("글 목록을 불러오는 중이에요") }
            items(hot, key = { "h" + it.id }) { p -> PostCard(p, feed?.sourceById?.get(p.source), false, { onSelect(p) }, { onSelect(p) }) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}

private data class ExportResult(val pieces: List<Bitmap>, val files: List<File>, val text: String)

@Composable
private fun ExportDetail(post: Post, source: Source?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sourceName = source?.name ?: post.source
    var attempt by remember { mutableIntStateOf(0) }
    var scan by remember(attempt) { mutableStateOf<ScanState>(ScanState.Loading(0, "페이지 여는 중")) }
    var mosaicOn by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var variant by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf("") }
    var showAutoHelp by remember { mutableStateOf(false) }

    if (showAutoHelp) {
        val send = { showAutoHelp = false; result?.files?.let { ThreadsKit.shareToThreads(ctx, it, review + "\n\n" + ThreadsKit.sourceLine(sourceName)) }; Unit }
        AlertDialog(
            onDismissRequest = { showAutoHelp = false },
            title = { Text("자동 입력 켜기") },
            text = {
                Text(
                    "접근성에서 '커뮤니티 파인더 · 스레드 자동 입력'을 켜면 스레드 새 글 화면에 본문이 자동으로 입력돼요.\n\n" +
                        "켜지지 않고 '제한된 설정'이라고 나오면: 휴대폰 설정 > 애플리케이션 > 커뮤니티 파인더 > 오른쪽 위 ⋮ > '제한된 설정 허용'을 먼저 눌러주세요.\n\n" +
                        "켜지 않아도 본문은 복사돼 있어서 붙여넣기 하면 돼요.",
                    fontSize = 14.sp, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAutoHelp = false
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("접근성 설정 열기") }
            },
            dismissButton = { TextButton(onClick = send) { Text("그냥 올리기") } },
        )
    }

    // 스캔 완료 → 모자이크 → 본문 영역 자르기 → 3:4 분할 → 파일 저장
    val done = (scan as? ScanState.Done)?.result
    LaunchedEffect(done, mosaicOn) {
        if (done == null) return@LaunchedEffect
        result = withContext(Dispatchers.Default) {
            val masked = if (mosaicOn) ThreadsKit.mosaic(done.bitmap, done.authorRects) else done.bitmap
            val c = ThreadsKit.cropRect(masked, done.contentRect)
            val body = Bitmap.createBitmap(masked, c.left, c.top, c.width(), c.height())
            // 사진·영상 영역 안에서는 자르지 않도록 위치 전달
            val pieces = ThreadsKit.split(body, done.imageRects.map { (it.top - c.top)..(it.bottom - c.top) })
            ExportResult(pieces, ThreadsKit.writeCache(ctx, post.id, pieces), done.text)
        }
        if (review.isEmpty()) review = ThreadsKit.review(post, done.text, variant)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Text("스레드용 내보내기", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        }
        Box(Modifier.weight(1f)) {
            PageScanner(post, attempt) { scan = it }
            ScanStatus(scan, onRetry = { attempt++ }) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CopyCard("제목", post.title) { ThreadsKit.copy(ctx, "제목", post.title) }
                    CopyCard("출처", ThreadsKit.sourceLine(sourceName)) { ThreadsKit.copy(ctx, "출처", ThreadsKit.sourceLine(sourceName)) }

                    // 분할 이미지
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(vertical = 14.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("이미지 ${result?.pieces?.size ?: 0}장", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                Text("작성자 모자이크 (${done?.authorRects?.size ?: 0}곳)", fontSize = 13.sp)
                                Spacer(Modifier.width(6.dp))
                                Switch(checked = mosaicOn, onCheckedChange = { mosaicOn = it }, modifier = Modifier.height(28.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            val w = (LocalConfiguration.current.screenWidthDp * 0.62f).dp
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(result?.pieces.orEmpty()) { i, b ->
                                    Box {
                                        val img = remember(b) { b.asImageBitmap() }
                                        Image(img, "이미지 ${i + 1}", contentScale = ContentScale.FillWidth,
                                            modifier = Modifier.width(w).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                                        Text("${i + 1}/${result?.pieces?.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(6.dp).background(Color(0x99000000), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            if (result == null) Text("이미지 만드는 중…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 감상평 예시
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("감상평 예시 (스레드 본문용)", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(review, { review = it }, Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, lineHeight = 20.sp))
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { variant++; review = ThreadsKit.review(post, done?.text.orEmpty(), variant) }) {
                                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("다른 예시")
                                }
                                Button(onClick = { ThreadsKit.copy(ctx, "감상평", review) }) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("복사하기")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        // 하단: 저장 / 스레드에 올리기
        val files = result?.files
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = files != null, shape = CircleShape, modifier = Modifier.height(52.dp),
                onClick = {
                    val n = ThreadsKit.saveToGallery(ctx, files!!, "cf_${post.source}_${System.currentTimeMillis() / 1000}")
                    Toast.makeText(ctx, if (n > 0) "사진 앱에 ${n}장 저장했어요 (CommunityFinder 앨범)" else "안드로이드 10 이상에서 저장할 수 있어요", Toast.LENGTH_SHORT).show()
                },
            ) { Text("갤러리 저장") }
            GradientButton("스레드에 올리기", null, onClick = {
                if (files != null) {
                    val body = review + "\n\n" + ThreadsKit.sourceLine(sourceName)
                    ThreadsKit.copy(ctx, "본문", body) // 자동 입력이 안 되면 붙여넣기만 하면 되도록
                    if (ThreadsAuto.isEnabled(ctx)) ThreadsAuto.start(body) else showAutoHelp = true
                    if (!showAutoHelp) ThreadsKit.shareToThreads(ctx, files, body)
                }
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CopyCard(label: String, text: String, onCopy: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            }
            FilledTonalButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("복사하기", fontSize = 13.sp)
            }
        }
    }
}
