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
            // 닉네임 왼쪽에 붙은 작은 프로필 사진까지 가리도록 왼쪽으로 조금 넓힘
            val r = Rect(r0.left - minOf(r0.height(), 60), r0.top - 4, r0.right + 6, r0.bottom + 4)
            if (!r.intersect(0, 0, out.width, out.height) || r.width() < 2 || r.height() < 2) return@forEach
            val part = Bitmap.createBitmap(out, r.left, r.top, r.width(), r.height())
            val small = Bitmap.createScaledBitmap(part, maxOf(1, r.width() / block), maxOf(1, r.height() / block), true)
            canvas.drawBitmap(small, null, r, paint)
            part.recycle(); small.recycle()
        }
        return out
    }

    /**
     * 스레드용 세로 분할 위치 계산 (픽셀 없이 테스트 가능하도록 분리).
     * - 전체가 9:16보다 짧으면 한 장
     * - 여러 장이면 길이를 고르게 나눔 (끝에 자투리 조각이 생기지 않게)
     * - 빈 줄(여백)에서만 자르고, 사진/영상 영역(avoid) 안에서는 자르지 않음
     * 반환: [시작, 자르는 위치들..., 끝]
     */
    fun cutPoints(blank: BooleanArray, w: Int, avoid: List<IntRange> = emptyList()): List<Int> {
        var top = 0
        var bottom = blank.size
        while (top < bottom && blank[top]) top++
        while (bottom > top && blank[bottom - 1]) bottom--
        top = maxOf(0, top - 8); bottom = minOf(blank.size, bottom + 8) // 위아래 여백은 조금만 남김
        val h = bottom - top
        val maxH = (w * 16f / 9f).toInt()
        if (h <= maxH) return listOf(top, bottom)

        val n = Math.ceil(h / (w * 1.35)).toInt().coerceIn(2, MAX_PIECES)
        val ideal = h.toFloat() / n
        fun inAvoid(y: Int) = avoid.firstOrNull { y > it.first && y < it.last }

        // 자를 수 있는 후보: 6px 이상 이어진 빈 줄의 가운데
        val cands = mutableListOf<Pair<Int, Int>>() // (위치, 여백 길이)
        var y = top
        while (y < bottom) {
            if (blank[y]) {
                val s = y
                while (y < bottom && blank[y]) y++
                val mid = (s + y) / 2
                if (y - s >= 6 && inAvoid(mid) == null) cands += mid to (y - s)
            } else y++
        }

        val cuts = mutableListOf(top)
        for (i in 1 until n) {
            val target = (top + ideal * i).toInt()
            val prev = cuts.last()
            val best = cands
                .filter { Math.abs(it.first - target) <= ideal * 0.4f && it.first - prev >= ideal * 0.45f && bottom - it.first >= ideal * 0.45f }
                .minByOrNull { Math.abs(it.first - target) - minOf(it.second, 80) * 2 }
            var cut = best?.first
            if (cut == null) {
                // 여백이 없으면: 사진 안이면 사진 경계로 옮기고, 아니면 목표 지점에서 자름
                val r = inAvoid(target)
                cut = if (r != null && r.last - r.first < maxH) {
                    listOf(r.first, r.last).filter { it - prev >= ideal * 0.3f && bottom - it >= ideal * 0.3f }
                        .minByOrNull { Math.abs(it - target) }
                } else target
            }
            if (cut != null && cut > prev) cuts += cut
        }
        cuts += bottom
        return cuts
    }

    fun split(src: Bitmap, avoid: List<IntRange> = emptyList()): List<Bitmap> {
        val w = src.width
        val row = IntArray(w)
        val blank = BooleanArray(src.height) { y ->
            src.getPixels(row, 0, w, 0, y, w, 1)
            val base = row[w / 2]
            var ok = true
            var x = 0
            while (x < w) {
                val p = row[x]
                val d = Math.abs(((p shr 16) and 255) - ((base shr 16) and 255)) +
                    Math.abs(((p shr 8) and 255) - ((base shr 8) and 255)) + Math.abs((p and 255) - (base and 255))
                if (d > 30) { ok = false; break }
                x += 3
            }
            ok
        }
        val cuts = cutPoints(blank, w, avoid)
        return cuts.zipWithNext { a, b -> Bitmap.createBitmap(src, 0, a, w, b - a) }
    }

    /** 본문 영역(좌우는 전체 폭 유지). 없으면 전체 */
    fun cropRect(src: Bitmap, r: Rect?): Rect {
        val full = Rect(0, 0, src.width, src.height)
        if (r == null) return full
        val c = Rect(0, r.top - 12, src.width, r.bottom + 12)
        return if (c.intersect(full) && c.height() >= 100) c else full
    }

    fun sourceLine(sourceName: String) = "출처 : $sourceName"

    // ---------- 감상평 예시 (휴대폰 안에서 규칙으로 생성 · AI/토큰 사용 안 함) ----------

    enum class Mood(val keywords: List<String>, val lines: List<String>) {
        FUNNY(
            listOf("ㅋㅋ", "웃긴", "웃김", "유머", "개웃", "레전드", "짤", "드립", "빵터", "킹받"),
            listOf("이거 보고 혼자 빵 터졌다 ㅋㅋㅋ", "아니 이건 진짜 웃겨서 저장함 ㅋㅋ", "오늘 본 것 중에 제일 웃김 ㅋㅋㅋ"),
        ),
        ISSUE(
            listOf("논란", "사고", "고소", "폭로", "충격", "분노", "사기", "음주", "갑질", "피해", "경찰", "구속", "의혹"),
            listOf("이건 좀 선 넘은 것 같은데…", "보면서 진짜 어이가 없었다. 이게 맞나?", "커뮤에서 난리 난 이유가 있네."),
        ),
        TOUCHING(
            listOf("감동", "눈물", "울컥", "사연", "고마", "부모", "엄마", "아빠", "할머니", "힐링", "따뜻"),
            listOf("읽다가 울컥했다. 오늘 하루 이 글로 버틴다.", "이런 글 보면 아직 세상 따뜻하다 싶다.", "마음이 몽글몽글해지는 글이다."),
        ),
        INFO(
            listOf("꿀팁", "정리", "방법", "후기", "추천", "리뷰", "비교", "가이드", "총정리", "알아두"),
            listOf("이거 몰랐던 사람 많을 듯. 저장각이다.", "정리 잘 돼 있어서 가져왔다. 필요한 사람 참고해!", "알아두면 진짜 쓸모 있는 내용이네."),
        ),
        MONEY(
            listOf("주식", "코인", "부동산", "월급", "적금", "금리", "연봉", "집값", "환율", "투자", "물가"),
            listOf("요즘 다들 이거 때문에 고민 많지…", "현실적인 얘기라 더 와닿는다.", "돈 얘기는 언제나 뜨겁네."),
        ),
        SPORTS(
            listOf("경기", "골", "홈런", "축구", "야구", "농구", "손흥민", "이강인", "류현진", "우승", "감독", "선수"),
            listOf("이 장면은 진짜 역대급이었다.", "보는 내내 소름 돋았다.", "팬이라면 무조건 봐야 하는 장면!"),
        ),
        DEFAULT(
            emptyList(),
            listOf("요즘 커뮤에서 제일 핫한 글 가져왔다.", "이거 봤어? 반응 엄청 뜨겁더라.", "생각할 거리가 있는 글이라 가져왔다."),
        ),
    }

    fun moodOf(title: String, body: String): Mood {
        val text = "$title $title $body".lowercase() // 제목 가중치 2배
        return Mood.entries.filter { it != Mood.DEFAULT }
            .map { m -> m to m.keywords.sumOf { k -> Regex(Regex.escape(k)).findAll(text).count() } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first ?: Mood.DEFAULT
    }

    /** 감상평 예시 (반말, 첫 줄 + 마지막 줄). variant를 바꾸면 다른 문장 */
    fun review(post: Post, body: String, variant: Int = 0): String {
        val mood = moodOf(post.title, body)
        val line = mood.lines[Math.floorMod(variant, mood.lines.size)]
        val ask = listOf("다들 어떻게 생각해? 👇", "댓글로 의견 남겨줘!", "공감되면 ❤️ 눌러줘").let { it[Math.floorMod(variant, it.size)] }
        return "$line\n\n$ask"
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
