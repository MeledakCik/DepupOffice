package cloud.depup.office

/**
 * Pangkas sisi kotak dokumen yang ternyata BUKAN kertas (benda gelap, meja, tangan, ponsel di pinggir foto).
 * Murni Kotlin, bekerja di gambar abu-abu kecil yang sudah "closed" (goresan teks hilang), jadi tulisan tangan
 * tidak ikut menurunkan rata-rata terang. Dipanggil dari ScanAi.detectCrop.
 *
 * Ide: baris/kolom di tepi kotak yang rata-rata terangnya jauh di bawah kertas (median) dibuang sampai ketemu kertas.
 * Bila harus membuang terlalu banyak (>35% satu sisi) berarti dokumennya memang gelap -> kotak dibiarkan.
 */
object CropTrim {
    private const val DARK = 0.78f      // < 78% terang kertas = bukan kertas
    private const val MAX_SIDE = 0.35f  // batas pangkas per sisi
    private const val MIN_KEEP = 10

    /** [box] = [l, t, r, b] inklusif (piksel gambar kecil). Mengembalikan kotak baru (atau [box] bila tak perlu). */
    fun trimDark(g: IntArray, w: Int, h: Int, box: IntArray): IntArray {
        var l = box[0]; var t = box[1]; var r = box[2]; var b = box[3]
        if (r - l < 20 || b - t < 20 || g.size < w * h) return box

        // --- baris (atas / bawah) ---
        val bw = r - l + 1
        val rows = FloatArray(h)
        for (y in t..b) { var s = 0L; for (x in l..r) s += g[y * w + x]; rows[y] = s.toFloat() / bw }
        val thrR = median(rows, t, b) * DARK
        val maxR = ((b - t + 1) * MAX_SIDE).toInt()
        var n = 0
        while (t < b - MIN_KEEP && rows[t] < thrR) { t++; if (++n > maxR) return box }
        n = 0
        while (b > t + MIN_KEEP && rows[b] < thrR) { b--; if (++n > maxR) return box }

        // --- kolom (kiri / kanan), dihitung ulang di baris yang tersisa ---
        val bh = b - t + 1
        val cols = FloatArray(w)
        for (x in l..r) { var s = 0L; for (y in t..b) s += g[y * w + x]; cols[x] = s.toFloat() / bh }
        val thrC = median(cols, l, r) * DARK
        val maxC = ((r - l + 1) * MAX_SIDE).toInt()
        n = 0
        while (l < r - MIN_KEEP && cols[l] < thrC) { l++; if (++n > maxC) return box }
        n = 0
        while (r > l + MIN_KEEP && cols[r] < thrC) { r--; if (++n > maxC) return box }

        return intArrayOf(l, t, r, b)
    }

    private fun median(a: FloatArray, from: Int, to: Int): Float {
        val s = a.copyOfRange(from, to + 1)
        s.sort()
        return s[s.size / 2]
    }
}
