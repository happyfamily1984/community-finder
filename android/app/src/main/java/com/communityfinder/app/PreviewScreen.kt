package com.communityfinder.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 글 주소를 각 커뮤니티의 모바일 버전 주소로 변환 */
fun mobileUrl(post: Post): String {
    val u = post.url
    fun m(re: String) = Regex(re).find(u)?.groupValues
    return when (post.source) {
        "dcinside" -> m("[?&]id=([^&]+).*[?&]no=(\\d+)")?.let { "https://m.dcinside.com/board/${it[1]}/${it[2]}" }
        "ruliweb" -> m("bbs\\.ruliweb\\.com(/.*)")?.let { "https://m.ruliweb.com${it[1]}" }
        "clien" -> m("www\\.clien\\.net(/.*)")?.let { "https://m.clien.net${it[1]}" }
        "natepann" -> m("pann\\.nate\\.com/talk/(\\d+)")?.let { "https://m.pann.nate.com/talk/${it[1]}" }
        "bobaedream" -> m("[?&]code=([^&]+).*[?&]No=(\\d+)")?.let { "https://m.bobaedream.co.kr/board/bbs_view/${it[1]}/${it[2]}" }
        "fmkorea" -> m("fmkorea\\.com/(\\d+)")?.let { "https://m.fmkorea.com/${it[1]}" }
        else -> null // 더쿠 등 반응형 사이트는 같은 주소가 모바일 화면
    } ?: u
}

private sealed interface ScanState {
    data class Loading(val progress: Int, val step: String) : ScanState
    data class Done(val bitmap: Bitmap, val truncated: Boolean) : ScanState
    data class Failed(val message: String) : ScanState
}

private const val MAX_SCAN_HEIGHT_PX = 12000 // 메모리 보호: 너무 긴 글은 앞부분만

/**
 * 글 미리보기: 휴대폰에서 모바일 페이지를 보이지 않게 열어 전체를 한 장의 이미지로 캡처해 보여줌.
 * 아래 "해당 글로 이동" 버튼으로 원본 사이트를 엶.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(post: Post, source: Source?, favorite: Boolean, onToggleFavorite: () -> Unit, onOpenSite: () -> Unit, onBack: () -> Unit) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.roundToPx() }
    val scope = rememberCoroutineScope()

    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(attempt) { mutableStateOf<ScanState>(ScanState.Loading(0, "페이지 여는 중")) }
    var webHeightPx by remember(attempt) { mutableIntStateOf(screenHeightPx) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            (state as? ScanState.Done)?.bitmap?.recycle()
        }
    }

    // 페이지 로딩 → 전체 높이로 늘림 → 이미지 로딩 대기 → 캡처
    suspend fun capture(wv: WebView) {
        state = ScanState.Loading(90, "글 전체 펼치는 중")
        delay(600)
        // 페이지가 축소 표시될 수 있으므로 높이 = 화면폭 × (scrollHeight / scrollWidth)
        val size = suspendCoroutine { cont ->
            wv.evaluateJavascript(
                "(function(){var d=document.documentElement,b=document.body||d;" +
                    "return Math.max(d.scrollWidth,b.scrollWidth)+','+Math.max(d.scrollHeight,b.scrollHeight);})()",
            ) { cont.resume(it?.trim('"')?.split(',')?.mapNotNull { v -> v.toDoubleOrNull() }) }
        }
        val fullPx = if (size != null && size.size == 2 && size.all { it > 0 }) {
            (widthPx * size[1] / size[0]).toInt()
        } else {
            screenHeightPx
        }.coerceAtLeast(screenHeightPx)
        webHeightPx = fullPx.coerceAtMost(MAX_SCAN_HEIGHT_PX)
        // 화면이 길어지면 지연 로딩 이미지들이 한꺼번에 불러와짐
        state = ScanState.Loading(95, "이미지 불러오는 중")
        delay(1800)
        state = ScanState.Loading(99, "캡처 중")
        delay(100)
        try {
            val h = wv.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(wv.width.coerceAtLeast(1), h, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE) // 배경이 투명한 페이지 대비
            wv.draw(canvas)
            state = ScanState.Done(bmp, truncated = fullPx > MAX_SCAN_HEIGHT_PX)
        } catch (e: OutOfMemoryError) {
            state = ScanState.Failed("글이 너무 길어서 캡처하지 못했어요")
        } catch (e: Exception) {
            state = ScanState.Failed(e.message ?: "캡처 실패")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                Column(Modifier.weight(1f)) {
                    Text(
                        source?.name ?: post.source, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = source?.let { Color(it.color) } ?: Color.Gray,
                    )
                    Text(post.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onToggleFavorite) {
                    if (favorite) Icon(Icons.Filled.Star, "즐겨찾기 해제", tint = Color(0xFFFFB800))
                    else Icon(Icons.Outlined.StarOutline, "즐겨찾기")
                }
            }
        },
        bottomBar = {
            Box(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Button(
                    onClick = onOpenSite,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF7B61FF), Purple)), CircleShape),
                ) {
                    Text("해당 글로 이동", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // 캡처용 WebView: 1px 영역에 잘라 넣어 화면에는 보이지 않음 (크기는 실제 화면 폭 그대로)
            key(attempt) {
                Box(Modifier.size(1.dp).clipToBounds()) {
                    AndroidView(
                        modifier = Modifier.requiredSize(with(density) { widthPx.toDp() }, with(density) { webHeightPx.toDp() }),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                var captured = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                                    override fun onPageFinished(view: WebView, url: String) {
                                        if (captured || view.progress < 100) return
                                        captured = true
                                        scope.launch { capture(view) }
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        if (state is ScanState.Loading) state = ScanState.Loading(newProgress * 85 / 100, "페이지 여는 중")
                                    }
                                }
                                loadUrl(mobileUrl(post))
                                webView = this
                            }
                        },
                    )
                }
                // 너무 오래 걸리면 지금까지 그려진 대로 캡처
                LaunchedEffect(Unit) {
                    delay(25_000)
                    val wv = webView
                    if (state is ScanState.Loading && wv != null) capture(wv)
                }
            }

            when (val s = state) {
                is ScanState.Loading -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("모바일 화면 스캔 중… ${s.progress}%", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(s.step, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ScanState.Failed -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { webView?.destroy(); webView = null; attempt++ }) {
                        Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("다시 스캔")
                    }
                }
                is ScanState.Done -> ScanImage(s.bitmap, s.truncated)
            }
        }
    }
}

/** 세로 스크롤 + 두 손가락 확대 */
@Composable
private fun ScanImage(bitmap: Bitmap, truncated: Boolean) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 3f)
                    val maxX = size.width * (scale - 1) / 2
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    if (scale == 1f) offsetX = 0f
                }
            }
            .verticalScroll(rememberScrollState()),
    ) {
        Image(
            image, "글 스캔 이미지",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
        )
        if (truncated) {
            Text(
                "글이 길어서 앞부분만 스캔했어요. 전체는 아래 \"해당 글로 이동\"에서 보세요.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
