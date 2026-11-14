package com.communityfinder.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 스캔 결과. 좌표는 모두 bitmap 픽셀 기준 */
data class ScanResult(
    val bitmap: Bitmap,
    val truncated: Boolean,
    val authorRects: List<Rect>,
    val contentRect: Rect?,
    val imageRects: List<Rect>,
    val text: String,
)

sealed interface ScanState {
    data class Loading(val progress: Int, val step: String) : ScanState
    data class Done(val result: ScanResult) : ScanState
    data class Failed(val message: String) : ScanState
}

private const val MAX_SCAN_HEIGHT_PX = 12000 // 메모리 보호: 너무 긴 글은 앞부분만

/** 페이지 분석: 작성자 닉네임 위치, 본문 영역, 본문 텍스트 (CSS px, 문서 기준) */
fun imagePostHtml(title: String, image: String): String {
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;background:#fff;color:#111;font-family:sans-serif}
.post_title{font-size:20px;font-weight:800;line-height:1.35;padding:16px 14px 12px;margin:0;word-break:keep-all}
img{display:block;width:100%;height:auto}</style></head>
<body><article class="post_content"><h1 class="post_title">${esc(title)}</h1><img src="${esc(image)}"></article></body></html>"""
}

/** 페이지의 글자 노드를 모아 window.__cfNodes에 보관하고 문자열 배열을 반환 (shadow DOM 포함) */
private const val COLLECT_TEXT_JS = """
(function(){
  var nodes=[], texts=[], skip={SCRIPT:1,STYLE:1,NOSCRIPT:1,TEXTAREA:1,INPUT:1,CODE:1,SVG:1};
  function walk(root){
    if(nodes.length>=500)return;
    var c=root.childNodes;
    for(var i=0;i<c.length&&nodes.length<500;i++){var n=c[i];
      if(n.nodeType===3){var t=n.nodeValue; if(t&&t.trim().length>1&&/[A-Za-z]{2,}|[\u3040-\u30ff\u4e00-\u9fff]/.test(t)&&t.length<1500){nodes.push(n);texts.push(t);}}
      else if(n.nodeType===1&&!skip[n.tagName.toUpperCase()]){ if(n.shadowRoot)walk(n.shadowRoot); walk(n); }
    }
  }
  walk(document.body);
  window.__cfNodes=nodes;
  return JSON.stringify(texts);
})()
"""

/** 사이트별 작성자(글쓴이·댓글 작성자) 닉네임/프로필 위치. GitHub 공개 코드로 확인한 선택자 + 추정 */
val AUTHOR_SELECTORS = mapOf(
    "fmkorea" to ".member_plate, a[class*='member_'], .side.fr .profile, img.xe_point_level_icon, .fdb_itm .meta > a",
    "dcinside" to "ul.ginfo2 > li:first-child, .gallview-tit-box .nick, .gall-writer, .comment-box .nick, .all-comment-lst .nick, a.nick, .ginfo-area .nick, .sp-nick, .nick-icon",
    "clien" to ".post_contact .contact_name, .contact_name, .nickname, .post_info .nickname, .comment_info .nickname, .nickimg",
    "ruliweb" to ".user_view .nick, .user_info .nick, .nick, a.nick, .user_inner .nick, .comment .nick, .profile_image, .member_srl",
    "natepann" to ".writer, .info .writer, .nameui, .usertxt .nameui, .cmt_best .writer, .list_comment .writer, .nick, .sub .writer, .profile",
    "bobaedream" to ".author, .data4, .util .name, .info .nickName, .nickName, .reple_id, .rename, .writer, .replylist .name, .util2 .info span:first-child, a[onclick*='submenu']",
    "theqoo" to ".author, .nick_name, .side .author",
    "reddit" to "a[href*='/user/'], a[href^='/u/'], [slot='authorName'], faceplate-hovercard, .author-name, shreddit-async-loader[bundlename*='author']",
    "lemmy" to "a[href^='/u/'], .person-listing, a.text-info[href*='/u/'], img.avatar-overlay",
)

/** 사이트별 본문 영역 (댓글·광고를 빼고 본문만 내보내기 위함). 앞에서부터 먼저 맞는 것을 사용 */
val BODY_SELECTORS = mapOf(
    "theqoo" to ".rd_body, article .xe_content, .xe_content",
    "fmkorea" to ".rd_body, article .xe_content, .xe_content",
    "dcinside" to ".thum-txtin, .thum-txt, .gallview-thum",
    "clien" to ".post_article, .post_content, .post_view",
    "ruliweb" to ".view_content, .board_main_view .view_content, article",
    "natepann" to "#contentArea, .posting, .content-view, .view-wrap .content",
    "bobaedream" to ".article-body, .bodyCont, .content02, .cont_area, #body_frame",
    "lemmy" to "#postContent, .post-listing .md-div, article",
    "jp" to ".article-body, .entry-content, .article-body-inner, #article-body, .entry-body, article",
)

/** 캡처 전에 광고와 댓글 영역을 숨김 */
private const val HIDE_ADS_JS = """
(function(){try{
  document.querySelectorAll('ins.adsbygoogle,iframe:not([src*="youtube"]):not([src*="youtu.be"]),[id^="google_ads"],[id^="div-gpt-ad"],[class*="adsbygoogle"],[class*="coupang"],[id*="coupang"],a[href*="coupang.com"],a[href*="link.coupang"]').forEach(function(e){e.style.setProperty('display','none','important');});
  // 댓글 영역 숨김 (본문만 보이게)
  document.querySelectorAll('#comment,#comments,#cmtPosition,#cmt_list,.fdb_lst_wrp,.fdb_lst_ul,#fdb_lst,.comment_wrap,.comment-wrap,.comment_box,.comment-box,.all-comment,.all-comment-lst,.view_comment,.comment_view,.post_comment,.comment_list,.comment-list,.cmt_list,.cmt_area,.reply_list,.reply-list,.reply-wrap,.replylist,.comment_container,.comments,[id^="comment_"],[class*="comment_list"],[class*="commentList"]').forEach(function(e){e.style.setProperty('display','none','important');});
}catch(x){}})()
"""

private fun analyzeJs(title: String, siteSel: String, bodySel: String = "") = """
(function(){
  var d=document.documentElement, vw=d.clientWidth||window.innerWidth, sx=window.scrollX, sy=window.scrollY;
  function R(e){var r=e.getBoundingClientRect();return [r.left+sx,r.top+sy,r.width,r.height];}
  var sel='[class*="nick"],[class*="Nick"],[class*="writer"],[class*="Writer"],[class*="author"],[class*="Author"],[class*="user_name"],[class*="userName"],[class*="username"],[class*="user-name"],[class*="profile_name"],[class*="member_name"],.name,.nic,.user,.id';
  var authors=[];
  var site=${JSONObject.quote(siteSel)};
  if(site){try{document.querySelectorAll(site).forEach(function(e){var r=e.getBoundingClientRect();
    if(r.width>0&&r.height>0&&r.height<90&&r.width<vw*0.8)authors.push(R(e));});}catch(x){}}
  document.querySelectorAll('[class*="profile"] img,[class*="avatar"],img[class*="profile"],img[class*="level"],img[class*="icon_nick"]').forEach(function(e){
    var r=e.getBoundingClientRect();if(r.width>0&&r.width<90&&r.height>0&&r.height<90)authors.push(R(e));});
  document.querySelectorAll(sel).forEach(function(e){var r=e.getBoundingClientRect(),t=(e.innerText||'').trim();
    if(r.width>0&&r.height>0&&r.height<70&&r.width<vw*0.7&&t.length>0&&t.length<40)authors.push(R(e));});
  var imgs=[];
  (function deep(root){root.querySelectorAll('img,video,canvas').forEach(function(e){var r=e.getBoundingClientRect();if(r.width>vw*0.3&&r.height>60)imgs.push(R(e));});
    root.querySelectorAll('*').forEach(function(e){if(e.shadowRoot)deep(e.shadowRoot);});})(document);
  var cands=[];
  document.querySelectorAll('article,[class*="content"],[class*="Content"],[class*="view"],[class*="View"],[class*="article"],[class*="post"],[class*="read"],[class*="body"],[class*="txt"],[id*="content"],[id*="view"]').forEach(function(e){
    var r=e.getBoundingClientRect(); if(r.width<vw*0.6||r.height<80)return;
    var s=(e.innerText||'').length+e.querySelectorAll('img,video,iframe').length*300;
    cands.push({e:e,s:s,a:r.width*r.height});});
  var max=0; cands.forEach(function(c){if(c.s>max)max=c.s;});
  var best=null; cands.forEach(function(c){if(c.s>=max*0.8&&(!best||c.a<best.a))best=c;});
  var bsel=${JSONObject.quote(bodySel)};
  if(bsel){try{var bs=document.querySelectorAll(bsel);for(var bi=0;bi<bs.length;bi++){var br=bs[bi].getBoundingClientRect();
    if(br.width>vw*0.5&&br.height>40){best={e:bs[bi]};break;}}}catch(x){}}
  var titleR=null, key=${JSONObject.quote(title.take(12))};
  if(key.length>2){var ts=document.querySelectorAll('h1,h2,h3,h4,[class*="title"],[class*="subject"],[class*="tit"]');
    for(var i=0;i<ts.length;i++){if((ts[i].innerText||'').indexOf(key)>=0){var tr=ts[i].getBoundingClientRect();if(tr.height>0&&tr.height<300){titleR=R(ts[i]);break;}}}}
  return JSON.stringify({w:Math.max(d.scrollWidth,document.body.scrollWidth),h:Math.max(d.scrollHeight,document.body.scrollHeight),
    authors:authors.slice(0,300),imgs:imgs.slice(0,200),content:best?R(best.e):null,title:titleR,text:best?(best.e.innerText||'').slice(0,2000):''});
})()
"""

/**
 * 화면에 보이지 않는 WebView로 모바일 페이지를 열어 전체를 한 장으로 캡처.
 * (WebView.enableSlowWholeDocumentDraw()는 MainActivity에서 미리 호출)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PageScanner(post: Post, attempt: Int, onState: (ScanState) -> Unit) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val widthPx = with(density) { config.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var webHeightPx by remember(attempt) { mutableIntStateOf(screenHeightPx) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember(attempt) { mutableStateOf(false) }

    DisposableEffect(attempt) { onDispose { webView?.destroy(); webView = null } }

    suspend fun eval(wv: WebView, js: String): String? = suspendCoroutine { c -> wv.evaluateJavascript(js) { c.resume(it) } }

    suspend fun capture(wv: WebView) {
        if (busy) return
        busy = true
        eval(wv, HIDE_ADS_JS)
        val lang = langOf(post.source)
        if (isGlobal(post.source)) {
            // 해외 글: 페이지의 외국어 글자를 한국어로 바꾼 뒤 캡처
            onState(ScanState.Loading(88, "한국어로 번역 중 (${Translator.engineName(ctx)})"))
            delay(800)
            val raw = eval(wv, COLLECT_TEXT_JS)
            val arr = runCatching { JSONArray(JSONArray("[$raw]").getString(0)) }.getOrNull()
            if (arr != null && arr.length() > 0) {
                val texts = (0 until arr.length()).map { arr.getString(it) }
                val ko = Translator.translateAll(ctx, texts, lang, "한 웹페이지의 글 조각들이다(제목, 본문, 댓글, 버튼).")
                eval(wv, "(function(a){var n=window.__cfNodes||[];for(var i=0;i<n.length&&i<a.length;i++){try{n[i].nodeValue=a[i];}catch(e){}}})(${JSONArray(ko)})")
            }
        }
        onState(ScanState.Loading(90, "글 전체 펼치는 중"))
        delay(600)
        fun parse(raw: String?) = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull()
        val first = parse(eval(wv, analyzeJs(post.title, AUTHOR_SELECTORS[post.source.substringBefore("-")].orEmpty(), BODY_SELECTORS[post.source.substringBefore("-")].orEmpty())))
        val cssW = first?.optDouble("w")?.takeIf { it > 0 } ?: (widthPx / density.density).toDouble()
        val fullPx = ((first?.optDouble("h") ?: 0.0) * widthPx / cssW).toInt().coerceAtLeast(screenHeightPx)
        webHeightPx = fullPx.coerceAtMost(MAX_SCAN_HEIGHT_PX)
        onState(ScanState.Loading(95, "이미지 불러오는 중"))
        delay(1800)
        // 늘어난 화면 기준으로 위치 다시 측정
        val info = parse(eval(wv, analyzeJs(post.title, AUTHOR_SELECTORS[post.source.substringBefore("-")].orEmpty(), BODY_SELECTORS[post.source.substringBefore("-")].orEmpty()))) ?: first
        onState(ScanState.Loading(99, "캡처 중"))
        try {
            val bmp = Bitmap.createBitmap(wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            wv.draw(canvas)
            val scale = bmp.width / (info?.optDouble("w")?.takeIf { it > 0 } ?: cssW)
            fun rect(a: JSONArray?): Rect? = a?.takeIf { it.length() == 4 }?.let {
                Rect((it.getDouble(0) * scale).toInt(), (it.getDouble(1) * scale).toInt(),
                    ((it.getDouble(0) + it.getDouble(2)) * scale).toInt(), ((it.getDouble(1) + it.getDouble(3)) * scale).toInt())
            }
            val authors = info?.optJSONArray("authors")?.let { arr -> (0 until arr.length()).mapNotNull { rect(arr.optJSONArray(it)) } }.orEmpty()
            val images = info?.optJSONArray("imgs")?.let { arr -> (0 until arr.length()).mapNotNull { rect(arr.optJSONArray(it)) } }.orEmpty()
            var content = rect(info?.optJSONArray("content"))
            val title = rect(info?.optJSONArray("title"))
            if (content != null && title != null && title.top < content.top) content = Rect(content.left, title.top, content.right, content.bottom)
            if (isGlobal(post.source)) {
                // 사진 속 글자도 번역해서 덮어씀 (글자 인식 → 번역 → 다시 그리기)
                onState(ScanState.Loading(99, "사진 속 글자 번역 중"))
                runCatching {
                    withContext(Dispatchers.Default) {
                        ImageTranslator.translateInPlace(ctx, bmp, images, lang) { i, n -> onState(ScanState.Loading(99, "사진 속 글자 번역 중 ($i/$n)")) }
                    }
                }
            }
            onState(ScanState.Done(ScanResult(bmp, fullPx > MAX_SCAN_HEIGHT_PX, authors, content, images, info?.optString("text").orEmpty())))
        } catch (e: OutOfMemoryError) {
            onState(ScanState.Failed("글이 너무 길어서 캡처하지 못했어요"))
        } catch (e: Exception) {
            onState(ScanState.Failed(e.message ?: "캡처 실패"))
        }
    }

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
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                            override fun onPageFinished(view: WebView, url: String) {
                                if (view.progress >= 100) scope.launch { capture(view) }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, p: Int) {
                                if (!busy) onState(ScanState.Loading(p * 85 / 100, "페이지 여는 중"))
                            }
                        }
                        val img = post.image
                        if (img != null) {
                            // 사진이 본문인 글: 사이트 화면 대신 제목 + 원본 사진만 담은 깔끔한 화면을 만들어 캡처
                            loadDataWithBaseURL(post.url, imagePostHtml(post.title, img), "text/html", "utf-8", null)
                        } else {
                            loadUrl(mobileUrl(post))
                        }
                        webView = this
                    }
                },
            )
        }
        LaunchedEffect(Unit) {
            delay(25_000) // 너무 오래 걸리면 지금까지 그려진 대로 캡처
            webView?.let { capture(it) }
        }
    }
}
