package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pintu masuk deteksi dokumen untuk Bitmap Android. Logikanya ada di [ScanCore] (murni Kotlin, diuji di JVM);
 * di sini hanya memperkecil foto ke ±320 px lalu meneruskannya.
 */
object ScanAi {
    private fun small(src: Bitmap): ScanCore.Small {
        val s = ScanCore.SIDE_PX.toFloat() / max(src.width, src.height)
        val w = max(8, (src.width * min(1f, s)).toInt())
        val h = max(8, (src.height * min(1f, s)).toInt())
        // Perkecil bertahap (maks. 1/2 per langkah): bilinear sekali lompat dari foto besar menimbulkan "semut"
        // (aliasing) yang terbaca sebagai tepi palsu oleh detektor.
        var bm = src
        while (bm.width > w * 2 + 1 || bm.height > h * 2 + 1) {
            val nb = Bitmap.createScaledBitmap(bm, max(w, bm.width / 2), max(h, bm.height / 2), true)
            if (bm !== src) bm.recycle()
            bm = nb
        }
        if (bm.width != w || bm.height != h) {
            val nb = Bitmap.createScaledBitmap(bm, w, h, true)
            if (bm !== src) bm.recycle()
            bm = nb
        }
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        if (bm !== src) bm.recycle()
        return ScanCore.fromPixels(px, w, h)
    }

    /** Kotak dokumen sebagai pecahan 0..1, atau null bila tidak yakin (biarkan potong penuh). */
    fun detectCrop(src: Bitmap): RectF? = ScanCore.detectCrop(small(src))?.let { RectF(it[0], it[1], it[2], it[3]) }

    /** 4 sudut dokumen (pecahan 0..1: KiriAtas, KananAtas, KananBawah, KiriBawah), atau null. */
    fun detectQuad(src: Bitmap): FloatArray? = ScanCore.detectQuad(small(src))

    /**
     * "AI potong": 4 sudut dokumen (pecahan 0..1, KA, KaA, KaB, KiB) — bisa dokumen kecil (struk, KTP) di mana pun
     * letaknya, miring atau berperspektif. Null bila tidak ada dokumen yang meyakinkan.
     */
    fun findDocument(src: Bitmap): FloatArray? = ScanCore.findDocument(small(src))

    /** true bila deteksi terakhir memilih 1 dari 2 halaman (untuk pemberitahuan ke pengguna). */
    val lastSplit: Boolean get() = ScanCore.lastSplit

    /**
     * Sudut (derajat) yang harus dipakai pada Matrix.postRotate agar teks jadi lurus. Rentang -30..+30.
     * [area] = pecahan area dokumen (null = seluruh gambar).
     */
    fun detectSkew(src: Bitmap, area: RectF?): Float {
        val s = small(src)
        val w = s.w; val h = s.h
        val a = area ?: RectF(0f, 0f, 1f, 1f)
        val insetX = (a.right - a.left) * 0.04f; val insetY = (a.bottom - a.top) * 0.04f
        val x0 = ((a.left + insetX) * w).toInt().coerceIn(0, w - 1)
        val x1 = ((a.right - insetX) * w).toInt().coerceIn(x0 + 1, w)
        val y0 = ((a.top + insetY) * h).toInt().coerceIn(0, h - 1)
        val y1 = ((a.bottom - insetY) * h).toInt().coerceIn(y0 + 1, h)

        val n = (x1 - x0) * (y1 - y0)
        if (n < 400) return 0f
        // Piksel teks = fitur gelap tipis di atas latar lokal (black-hat): kebal terhadap bayangan, meja gelap & sudut kosong.
        val closed = ScanCore.closeGray(s.g, w, h, 3)
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) if (closed[y * w + x] - s.g[y * w + x] > 22) { xs.add(x); ys.add(y) }
        if (xs.size < 150 || xs.size > n * 0.5f) return 0f   // terlalu sedikit teks / bukan halaman teks
        val step = max(1, xs.size / 20000)

        val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
        val diag = sqrt((w * w + h * h).toFloat())
        val bins = (diag * 1.2f).toInt() + 4
        val hist = IntArray(bins)

        fun score(deg: Float): Double {
            java.util.Arrays.fill(hist, 0)
            val rad = deg * PI / 180.0
            val sn = sin(rad).toFloat(); val cs = cos(rad).toFloat()
            var i = 0
            while (i < xs.size) {
                val dx = xs[i] - cx; val dy = ys[i] - cy
                val v = (-dx * sn + dy * cs + diag * 0.6f).toInt()
                if (v in 0 until bins) hist[v]++
                i += step
            }
            var sq = 0.0
            for (c in hist) sq += c.toDouble() * c
            return sq
        }

        val base = score(0f)
        var bestDeg = 0f; var best = base
        var d = -30f
        while (d <= 30f) { val sc = score(d); if (sc > best) { best = sc; bestDeg = d }; d += 1f }
        var f = bestDeg - 1f
        val fEnd = bestDeg + 1f
        while (f <= fEnd) { val sc = score(f); if (sc > best) { best = sc; bestDeg = f }; f += 0.1f }
        if (best < base * 1.03 || abs(bestDeg) < 0.3f) return 0f
        // bestDeg = kemiringan garis teks (searah jarum jam di layar); untuk meluruskan putar berlawanan.
        return -bestDeg
    }
}
