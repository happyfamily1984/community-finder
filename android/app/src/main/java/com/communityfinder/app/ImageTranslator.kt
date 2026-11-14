package com.communityfinder.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 사진 속 영어 글자 번역: 글자 인식(OCR) → 번역 → 원래 글자 자리를 배경색으로 덮고 한국어로 다시 씀.
 * 전부 휴대폰 안에서 처리 (ML Kit, 무료 · 토큰 없음).
 */
object ImageTranslator {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private const val TILE = 2000

    /** 번역할 만한 영어인지: 4글자 이상 단어가 있거나 2글자 이상 단어가 2개 이상 */
    fun worthTranslating(text: String): Boolean {
        val words = Regex("[A-Za-z]{2,}").findAll(text).map { it.value }.toList()
        return words.any { it.length >= 4 } || words.size >= 2
    }

    /** bitmap 위에 직접 그림. regions가 비면 전체를 세로로 나눠 처리. 반환: 번역해 넣은 글 덩어리 수 */
    suspend fun translateInPlace(bmp: Bitmap, regions: List<Rect>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val full = Rect(0, 0, bmp.width, bmp.height)
        val targets = regions.mapNotNull { r -> Rect(r).takeIf { it.intersect(full) && it.width() > 80 && it.height() > 40 } }
            .flatMap { r -> tiles(r) }
            .ifEmpty { tiles(full) }
        val canvas = Canvas(bmp)
        var count = 0
        targets.forEachIndexed { i, r ->
            onProgress(i + 1, targets.size)
            val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            val text = suspendCancellableCoroutine { c ->
                recognizer.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
            }
            crop.recycle()
            text?.textBlocks?.forEach { block ->
                val bb = block.boundingBox ?: return@forEach
                val src = block.text.replace('\n', ' ').trim()
                if (bb.height() < 14 || !worthTranslating(src)) return@forEach
                val ko = Translator.translate(src)
                if (ko == src || ko.isBlank()) return@forEach
                val box = Rect(bb).apply { offset(r.left, r.top) }
                val bg = borderColor(bmp, box)
                // 줄마다 원래 글자를 배경색으로 덮음
                val fill = Paint().apply { color = bg }
                block.lines.forEach { line ->
                    line.boundingBox?.let { lb -> canvas.drawRect(Rect(lb).apply { offset(r.left, r.top); inset(-4, -3) }, fill) }
                }
                val lineH = block.lines.mapNotNull { it.boundingBox?.height() }.average().takeIf { !it.isNaN() }?.toFloat() ?: box.height().toFloat()
                val centered = Math.abs(box.centerX() - r.centerX()) < r.width() * 0.08f
                drawFitted(canvas, ko, box, lineH, if (luminance(bg) > 140) Color.BLACK else Color.WHITE, centered)
                count++
            }
        }
        return count
    }

    private fun tiles(r: Rect): List<Rect> {
        if (r.height() <= TILE) return listOf(r)
        val out = mutableListOf<Rect>()
        var y = r.top
        while (y < r.bottom) { out += Rect(r.left, y, r.right, minOf(y + TILE, r.bottom)); y += TILE }
        return out
    }

    private fun luminance(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** 글 상자 바로 바깥 테두리 픽셀들의 중간값 색 = 배경색 */
    private fun borderColor(bmp: Bitmap, box: Rect): Int {
        val r = Rect(box).apply { inset(-5, -5) }
        val xs = (r.left..r.right step maxOf(1, r.width() / 24))
        val ys = (r.top..r.bottom step maxOf(1, r.height() / 12))
        val px = mutableListOf<Int>()
        fun add(x: Int, y: Int) { if (x in 0 until bmp.width && y in 0 until bmp.height) px += bmp.getPixel(x, y) }
        xs.forEach { add(it, r.top); add(it, r.bottom) }
        ys.forEach { add(r.left, it); add(r.right, it) }
        if (px.isEmpty()) return Color.WHITE
        fun med(f: (Int) -> Int) = px.map(f).sorted()[px.size / 2]
        return Color.rgb(med(Color::red), med(Color::green), med(Color::blue))
    }

    /** 상자 안에 들어가도록 글자 크기를 줄여 가며 그림 */
    private fun drawFitted(canvas: Canvas, text: String, box: Rect, lineH: Float, color: Int, centered: Boolean) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.DEFAULT_BOLD }
        val width = maxOf(box.width(), 40)
        var size = (lineH * 0.85f).coerceIn(12f, 120f)
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f).setIncludePad(false).build()
            if (layout.height <= box.height() + lineH * 0.3f || size <= 11f) break
            size *= 0.9f
        }
        canvas.save()
        canvas.translate(box.left.toFloat(), box.top + maxOf(0f, (box.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }
}
