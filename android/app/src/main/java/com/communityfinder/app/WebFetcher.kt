package com.communityfinder.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * 휴대폰의 브라우저 엔진(WebView)으로 페이지를 열어 HTML을 읽음.
 * 일반 요청은 막고 브라우저만 통과시키는 사이트용 (글 미리보기가 쓰는 것과 같은 엔진). 화면에는 보이지 않음.
 */
object WebFetcher {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(ctx: Context, url: String, timeoutMs: Long = 25_000): String? = withContext(Dispatchers.Main) {
        var wv: WebView? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                val view = WebView(ctx.applicationContext).also { wv = it }
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.blockNetworkImage = true // 목록만 필요하므로 이미지는 받지 않음 (데이터 절약)
                suspendCancellableCoroutine<Unit> { c ->
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, u: String) { if (c.isActive) c.resume(Unit) }
                    }
                    view.loadUrl(url)
                }
                suspend fun html(): String = suspendCancellableCoroutine { c ->
                    view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        c.resume(runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault(""))
                    }
                }
                // 사이트가 '브라우저 확인 중' 화면을 잠깐 띄우는 경우가 있어, 본문이 나올 때까지 조금 기다림
                var out = ""
                repeat(8) {
                    delay(1200)
                    out = html()
                    val waiting = out.length < 3000 || out.contains("Just a moment") || out.contains("challenge-platform")
                    if (!waiting) return@withTimeoutOrNull out
                }
                out
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { wv?.stopLoading(); wv?.destroy() }
        }
    }
}
