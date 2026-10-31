package cloud.depup.office

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Pembersih gambar KHUSUS tulisan tangan di kertas bergaris, dipakai sebagai percobaan ke-2 OCR
 * ketika keyakinan percobaan pertama rendah. Murni Kotlin, offline.
 *
 *  1. Peta terang kertas per sel (persentil 85) -> kertas rata walau ada bayangan.
 *  2. Piksel tinta = jauh lebih gelap dari kertas sekitarnya.
 *  3. Garis buku = deretan tinta horizontal panjang (toleran miring ±1 baris). Dibuang, KECUALI tempat goresan
 *     huruf memotong garis (ada tinta di atas DAN di bawah pita garis) supaya huruf tidak putus.
 *  4. Tinta dipekatkan, kertas diputihkan.
 *
 * Ukuran keluaran = ukuran masukan, jadi koordinat kotak OCR tetap cocok dengan gambar asli.
 */
object OcrPre {
    private const val INK_RATIO = 0.84f      // tinta bila < 84% terang kertas lokal
    private const val MIN_LINE_FRAC = 1f / 14f
    private const val BAND_LOOK = 5          // jarak cek tinta di atas/bawah pita garis

    fun handwriting(src: Bitmap): Bitmap? = try {
        val w = src.width; val h = src.height
        val lum = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                lum[y * w + x] = ((Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000).toByte()
            }
        }
        val out = clean(lum, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) { val v = out[y * w + x].toInt() and 255; row[x] = Color.rgb(v, v, v) }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        bmp
    } catch (_: Throwable) { null }   // termasuk OutOfMemoryError: percobaan ke-2 dilewati, hasil pertama dipakai

    /** Murni array (bisa diuji di JVM). [lum] 0..255 sebagai byte tak bertanda. */
    fun clean(lum: ByteArray, w: Int, h: Int): ByteArray {
        fun L(i: Int) = lum[i].toInt() and 255

        // 1) peta kertas
        val cell = maxOf(12, w / 60)
        val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
        val raw = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            java.util.Arrays.fill(hist, 0)
            var n = 0
            for (y in cy * cell until minOf(h, (cy + 1) * cell)) for (x in cx * cell until minOf(w, (cx + 1) * cell)) { hist[L(y * w + x)]++; n++ }
            var acc = 0; var p = 255
            val target = (n * 0.15f).toInt()
            for (v in 255 downTo 0) { acc += hist[v]; if (acc >= target) { p = v; break } }
            raw[cy * gw + cx] = p.toFloat()
        }
        val paper = FloatArray(gw * gh)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            var s = 0f; var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = cx + dx; val yy = cy + dy
                if (xx in 0 until gw && yy in 0 until gh) { s += raw[yy * gw + xx]; c++ }
            }
            paper[cy * gw + cx] = maxOf(60f, s / c)
        }

        // 2) tinta
        val ink = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val pp = paper[(y / cell) * gw + (x / cell)]
            ink[y * w + x] = L(y * w + x) < pp * INK_RATIO
        }

        // 3) garis buku: run horizontal panjang pada tinta yang di-OR 3 baris (toleran miring)
        val minRun = maxOf(40, (w * MIN_LINE_FRAC).toInt())
        val line = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            var x = 0
            while (x < w) {
                if (!(ink[y * w + x] || ink[(y - 1) * w + x] || ink[(y + 1) * w + x])) { x++; continue }
                val s = x
                var gap = 0
                var e = x
                while (e < w) {
                    val on = ink[y * w + e] || ink[(y - 1) * w + e] || ink[(y + 1) * w + e]
                    if (on) gap = 0 else { gap++; if (gap > 2) break }
                    e++
                }
                val end = e - gap
                if (end - s >= minRun) for (xx in s until end) if (ink[y * w + xx]) line[y * w + xx] = true
                x = e + 1
            }
        }

        // 4) keluaran
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var isInk = ink[i]
            if (isInk && line[i]) {
                // goresan huruf yang memotong garis: ada tinta non-garis di atas DAN di bawah pita
                var up = false; var dn = false
                var yy = y - 1
                while (yy >= 0 && line[yy * w + x]) yy--
                for (k in 0 until BAND_LOOK) { val q = yy - k; if (q >= 0 && ink[q * w + x] && !line[q * w + x]) { up = true; break } }
                yy = y + 1
                while (yy < h && line[yy * w + x]) yy++
                for (k in 0 until BAND_LOOK) { val q = yy + k; if (q < h && ink[q * w + x] && !line[q * w + x]) { dn = true; break } }
                if (!(up && dn)) isInk = false
            }
            out[i] = if (isInk) minOf(L(i), 80).toByte() else 255.toByte()
        }
        return out
    }
}
