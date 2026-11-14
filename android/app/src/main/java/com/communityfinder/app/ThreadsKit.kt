package com.communityfinder.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** 스레드(Threads) 업로드용 도구: 모자이크, 분할, 감상평 예시, 복사/저장/공유 */
object ThreadsKit {
    const val THREADS_PACKAGE = "com.instagram.barcelona"
    private const val MAX_PIECES = 20

    /** 작성자 닉네임 영역을 모자이크 (원본은 건드리지 않고 새 비트맵 반환) */
    fun mosaic(src: Bitmap, rects: List<Rect>, block: Int = 14): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false }
        rects.forEach { r0 ->
            val r = Rect(r0).apply { inset(-4, -4) }
            if (!r.intersect(0, 0, out.width, out.height) || r.width() < 2 || r.height() < 2) return@forEach
            val part = Bitmap.createBitmap(out, r.left, r.top, r.width(), r.height())
            val small = Bitmap.createScaledBitmap(part, maxOf(1, r.width() / block), maxOf(1, r.height() / block), true)
            canvas.drawBitmap(small, null, r, paint)
            part.recycle(); small.recycle()
        }
        return out
    }

    /**
     * 휴대폰 한 화면에 보기 좋은 3:4 비율로 세로 분할.
     * 글자 줄이 잘리지 않도록 목표 지점 위쪽 20% 범위에서 '빈 줄'(색이 거의 같은 행)을 찾아 자름.
     */
    fun split(src: Bitmap, ratio: Float = 4f / 3f): List<Bitmap> {
        val w = src.width
        val pieceH = (w * ratio).toInt().coerceAtLeast(1)
        val pieces = mutableListOf<Bitmap>()
        val row = IntArray(w)
        fun isBlank(y: Int): Boolean {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val base = row[0]
            for (x in 0 until w step 3) {
                val p = row[x]
                val d = Math.abs(((p shr 16) and 255) - ((base shr 16) and 255)) +
                    Math.abs(((p shr 8) and 255) - ((base shr 8) and 255)) + Math.abs((p and 255) - (base and 255))
                if (d > 30) return false
            }
            return true
        }
        var y = 0
        while (y < src.height && pieces.size < MAX_PIECES) {
            var end = y + pieceH
            if (end >= src.height) {
                end = src.height
            } else {
                val floor = end - pieceH / 5
                var c = end
                while (c > floor && !isBlank(c)) c--
                if (c > floor) end = c
            }
            if (end - y < pieceH / 6 && pieces.isNotEmpty()) break // 끝에 남은 아주 짧은 조각은 버림
            pieces += Bitmap.createBitmap(src, 0, y, w, end - y)
            y = end
        }
        return pieces
    }

    fun crop(src: Bitmap, r: Rect?): Bitmap {
        if (r == null) return src
        val c = Rect(0, r.top - 12, src.width, r.bottom + 12) // 좌우는 전체 폭 유지 (모바일 화면 그대로)
        if (!c.intersect(0, 0, src.width, src.height) || c.height() < 100) return src
        return Bitmap.createBitmap(src, c.left, c.top, c.width(), c.height())
    }

    fun sourceLine(sourceName: String) = "출처 : $sourceName"

    // ---------- 감상평 예시 (휴대폰 안에서 규칙으로 생성 · AI/토큰 사용 안 함) ----------

    enum class Mood(val keywords: List<String>, val lines: List<String>) {
        FUNNY(
            listOf("ㅋㅋ", "웃긴", "웃김", "유머", "개웃", "레전드", "짤", "드립", "빵터", "킹받"),
            listOf("이거 보고 혼자 빵 터졌어요 ㅋㅋㅋ", "아니 이건 진짜 웃겨서 저장했어요 ㅋㅋ", "오늘 본 것 중에 제일 웃김 ㅋㅋㅋ"),
        ),
        ISSUE(
            listOf("논란", "사고", "고소", "폭로", "충격", "분노", "사기", "음주", "갑질", "피해", "경찰", "구속", "의혹"),
            listOf("이건 좀 선 넘은 것 같은데… 다들 어떻게 생각하세요?", "보면서 진짜 어이가 없었어요. 이게 맞나요?", "커뮤에서 난리 난 이유가 있네요."),
        ),
        TOUCHING(
            listOf("감동", "눈물", "울컥", "사연", "고마", "부모", "엄마", "아빠", "할머니", "힐링", "따뜻"),
            listOf("읽다가 울컥했어요. 오늘 하루 이 글로 버팁니다.", "이런 글 보면 아직 세상 따뜻하다고 느껴요.", "마음이 몽글몽글해지는 글이에요."),
        ),
        INFO(
            listOf("꿀팁", "정리", "방법", "후기", "추천", "리뷰", "비교", "가이드", "총정리", "알아두"),
            listOf("이거 몰랐던 사람 많을 듯. 저장각이에요.", "정리 잘 돼 있어서 공유해요. 필요한 분들 참고하세요!", "알아두면 진짜 쓸모 있는 내용이네요."),
        ),
        MONEY(
            listOf("주식", "코인", "부동산", "월급", "적금", "금리", "연봉", "집값", "환율", "투자", "물가"),
            listOf("요즘 다들 이거 때문에 고민 많으시죠…", "현실적인 얘기라 더 와닿네요. 여러분은 어떠세요?", "돈 얘기는 언제나 뜨겁네요."),
        ),
        SPORTS(
            listOf("경기", "골", "홈런", "축구", "야구", "농구", "손흥민", "이강인", "류현진", "우승", "감독", "선수"),
            listOf("이 장면은 진짜 역대급이었어요.", "보는 내내 소름 돋았어요. 다들 보셨나요?", "팬이라면 무조건 봐야 하는 장면!"),
        ),
        DEFAULT(
            emptyList(),
            listOf("요즘 커뮤에서 제일 핫한 글 가져왔어요.", "이거 보셨어요? 반응이 엄청 뜨겁더라고요.", "생각할 거리가 있는 글이라 공유해요."),
        ),
    }

    fun moodOf(title: String, body: String): Mood {
        val text = "$title $title $body".lowercase() // 제목 가중치 2배
        return Mood.entries.filter { it != Mood.DEFAULT }
            .map { m -> m to m.keywords.sumOf { k -> Regex(Regex.escape(k)).findAll(text).count() } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first ?: Mood.DEFAULT
    }

    /** 감상평 예시. variant를 바꾸면 다른 문장 */
    fun review(post: Post, sourceName: String, body: String, variant: Int = 0): String {
        val mood = moodOf(post.title, body)
        val line = mood.lines[Math.floorMod(variant, mood.lines.size)]
        val stat = buildList {
            post.views?.let { add("조회 ${fmt(it)}") }
            post.comments?.let { add("댓글 ${fmt(it)}") }
        }.joinToString(", ")
        val ask = listOf("여러분 생각은 어떠세요? 👇", "댓글로 의견 남겨주세요!", "공감되면 ❤️ 눌러주세요").let { it[Math.floorMod(variant, it.size)] }
        return buildString {
            append(line)
            append("\n\n")
            append("📌 ${post.title}")
            if (stat.isNotEmpty()) append("\n($sourceName $stat)")
            append("\n\n")
            append(ask)
        }
    }

    // ---------- 복사 · 저장 · 공유 ----------

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "$label 복사했어요", Toast.LENGTH_SHORT).show()
    }

    /** 분할 이미지를 캐시에 JPEG로 저장 (공유용) */
    fun writeCache(ctx: Context, id: String, pieces: List<Bitmap>): List<File> {
        val dir = File(ctx.cacheDir, "export/${id.hashCode().toUInt()}").apply { deleteRecursively(); mkdirs() }
        return pieces.mapIndexed { i, b ->
            File(dir, "${i + 1}.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        }
    }

    /** 갤러리(사진/CommunityFinder)에 저장. 저장한 개수 반환 */
    fun saveToGallery(ctx: Context, files: List<File>, baseName: String): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var n = 0
        files.forEachIndexed { i, f ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${baseName}_${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CommunityFinder")
            }
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                n++
            }
        }
        return n
    }

    fun isThreadsInstalled(ctx: Context) =
        runCatching { ctx.packageManager.getPackageInfo(THREADS_PACKAGE, 0); true }.getOrDefault(false)

    /** 스레드 앱으로 사진 여러 장 + 본문 보내기 (스레드가 없으면 공유 목록) */
    fun shareToThreads(ctx: Context, files: List<File>, text: String) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (isThreadsInstalled(ctx)) {
            intent.setPackage(THREADS_PACKAGE)
            runCatching { ctx.startActivity(intent) }.onFailure {
                ctx.startActivity(Intent.createChooser(intent.setPackage(null), "공유할 앱 선택"))
            }
        } else {
            ctx.startActivity(Intent.createChooser(intent, "공유할 앱 선택"))
        }
    }
}
