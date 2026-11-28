package cloud.depup.office

import java.util.TreeMap
import kotlin.math.floor

/**
 * Model lembar kerja editor — murni (tanpa Android), diuji JVM di SheetModelTest.
 *
 * - [CellStyle]: format sel gaya Excel (tebal/miring/garis bawah/coret, warna teks & isi, perataan, ukuran huruf,
 *   format angka). Objek gaya dipakai bersama ([CellStyle.intern]) — sejuta sel bergaya sama = satu objek.
 * - [SheetData]: satu lembar: baris (panjang tiap baris mengikuti isinya), jumlah kolom, ukuran kolom/baris khusus,
 *   sel gabungan.
 * - [Axis]: posisi & ukuran baris/kolom bervariasi tanpa batas jumlah (hanya ukuran yang diubah yang disimpan).
 * - [FormulaRefs]: menggeser rujukan rumus saat disalin/diurutkan dan saat baris/kolom disisipkan atau dihapus —
 *   seperti Excel ("=B2*C2" yang disalin satu baris ke bawah menjadi "=B3*C3").
 */

/** Format angka sel. */
object CellNumFmt {
    const val GENERAL = 0
    const val NUMBER = 1      // 1.234,56
    const val THOUSANDS = 2   // 1.235
    const val CURRENCY = 3    // Rp 1.235
    const val PERCENT = 4     // 12,5%
    const val DATE = 5        // 31/12/2024 (nomor seri Excel)
    val LABELS = listOf("Umum", "Angka (1.234,56)", "Ribuan (1.235)", "Mata uang", "Persen (%)", "Tanggal")
}

/** Perataan mendatar. AUTO = seperti Excel: angka rata kanan, teks rata kiri. */
object HAlign { const val AUTO = 0; const val LEFT = 1; const val CENTER = 2; const val RIGHT = 3 }

data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Warna teks ARGB; 0 = otomatis (kontras dengan latar). */
    val color: Int = 0,
    /** Warna isi ARGB; 0 = tanpa isi. */
    val fill: Int = 0,
    val align: Int = HAlign.AUTO,
    /** Ukuran huruf (pt); 0 = bawaan (11 pt). */
    val size: Float = 0f,
    val numFmt: Int = CellNumFmt.GENERAL,
    /** Teks dibungkus ke beberapa baris di dalam sel. */
    val wrap: Boolean = false
) {
    val isDefault get() = this == DEFAULT

    companion object {
        val DEFAULT = CellStyle()
        const val BASE_SIZE = 11f
        private val pool = HashMap<CellStyle, CellStyle>()

        /** Satu objek untuk setiap gaya yang sama (hemat memori untuk lembar besar). */
        @Synchronized fun intern(s: CellStyle): CellStyle = if (s == DEFAULT) DEFAULT else pool.getOrPut(s) { s }
    }
}

class SheetCell(var valStr: String = "", style: CellStyle = CellStyle.DEFAULT) {
    var style: CellStyle = CellStyle.intern(style)
        set(v) { field = CellStyle.intern(v) }

    /** Kompatibel dengan kode lama (tebal / warna isi). */
    constructor(valStr: String, isBold: Boolean, bgColor: Int = 0) : this(valStr, CellStyle(bold = isBold, fill = bgColor))

    val isBold get() = style.bold
    val bgColor get() = style.fill
    fun copy() = SheetCell(valStr, style)
    val isBlank get() = valStr.isEmpty() && style.isDefault
}

/** Rentang sel (inklusif). */
data class CellRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    val top get() = minOf(r1, r2)
    val bottom get() = maxOf(r1, r2)
    val left get() = minOf(c1, c2)
    val right get() = maxOf(c1, c2)
    val rows get() = bottom - top + 1
    val cols get() = right - left + 1
    val isSingle get() = rows == 1 && cols == 1
    fun contains(r: Int, c: Int) = r in top..bottom && c in left..right
    fun intersects(o: CellRange) = top <= o.bottom && o.top <= bottom && left <= o.right && o.left <= right
    fun norm() = CellRange(top, left, bottom, right)
    fun ref(): String = if (isSingle) a1(top, left) else "${a1(top, left)}:${a1(bottom, right)}"

    companion object {
        fun a1(r: Int, c: Int) = colName(c) + (r + 1)
        fun colName(i: Int): String {
            var n = i + 1
            val sb = StringBuilder()
            while (n > 0) { val k = (n - 1) % 26; sb.append('A' + k); n = (n - 1) / 26 }
            return sb.reverse().toString()
        }
    }
}

/**
 * Ukuran baris/kolom: semua [def] kecuali yang tercatat di [sizes] (0 = disembunyikan). Posisi dihitung dari
 * jumlah selisih ukuran khusus sebelum indeks itu — O(log n) dengan cache, berapa pun jumlah baris/kolomnya.
 */
class Axis(var def: Float) {
    val sizes = TreeMap<Int, Float>()
    private var keys = IntArray(0)
    private var cum = DoubleArray(0)   // cum[j] = jumlah (ukuran - def) untuk keys[0 until j]
    private var dirty = true

    fun size(i: Int): Float = sizes[i] ?: def
    fun set(i: Int, v: Float?) { if (v == null || v == def) sizes.remove(i) else sizes[i] = v; dirty = true }
    fun clear() { sizes.clear(); dirty = true }
    fun touch() { dirty = true }

    private fun rebuild() {
        if (!dirty) return
        keys = sizes.keys.toIntArray()
        cum = DoubleArray(keys.size + 1)
        var s = 0.0
        for (j in keys.indices) { cum[j] = s; s += (sizes[keys[j]]!! - def) }
        cum[keys.size] = s
        dirty = false
    }

    /** Jumlah key < i. */
    private fun countBefore(i: Int): Int {
        var lo = 0; var hi = keys.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (keys[m] < i) lo = m + 1 else hi = m }
        return lo
    }

    /** Posisi awal indeks [i]. */
    fun pos(i: Int): Double { rebuild(); return i.toDouble() * def + cum[countBefore(i)] }

    /** Indeks pada posisi [p] (p ≥ 0). */
    fun indexAt(p: Double): Int {
        rebuild()
        if (p <= 0) return 0
        // ukuran khusus terakhir yang dimulai ≤ p
        var lo = 0; var hi = keys.size - 1; var best = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (keys[m].toDouble() * def + cum[m] <= p) { best = m; lo = m + 1 } else hi = m - 1
        }
        if (best < 0) return floor(p / def).toInt()
        val k = keys[best]
        val start = k.toDouble() * def + cum[best]
        val sz = sizes[k]!!
        if (p < start + sz) return k
        return k + 1 + floor((p - start - sz) / def).toInt()
    }

    /** Geser ukuran khusus saat [n] indeks disisipkan di [at] (n < 0 = dihapus). */
    fun shift(at: Int, n: Int) {
        if (n == 0) return
        val moved = TreeMap<Int, Float>()
        for ((k, v) in sizes) {
            when {
                k < at -> moved[k] = v
                n < 0 && k < at - n -> {}             // ikut terhapus
                else -> moved[k + n] = v
            }
        }
        sizes.clear(); sizes.putAll(moved); dirty = true
    }

    fun copyFrom(o: Axis) { def = o.def; sizes.clear(); sizes.putAll(o.sizes); dirty = true }
}

/** Satu lembar kerja. */
class SheetData(
    var name: String,
    val rows: MutableList<MutableList<SheetCell>> = mutableListOf(),
    var cols: Int = 4
) {
    /** Lebar kolom & tinggi baris dalam dp. */
    val colW = Axis(DEFAULT_COL_W)
    val rowH = Axis(DEFAULT_ROW_H)
    val merges = ArrayList<CellRange>()

    fun cell(r: Int, c: Int): SheetCell? = rows.getOrNull(r)?.getOrNull(c)

    /** Sel (dibuat bila belum ada; baris harus sudah ada). */
    fun ensure(r: Int, c: Int): SheetCell {
        while (rows.size <= r) rows.add(mutableListOf())
        val row = rows[r]
        while (row.size <= c) row.add(SheetCell(""))
        return row[c]
    }

    fun mergeAt(r: Int, c: Int): CellRange? {
        for (m in merges) if (m.contains(r, c)) return m
        return null
    }

    companion object {
        const val DEFAULT_COL_W = 96f
        const val DEFAULT_ROW_H = 40f
        /** dp ↔ satuan Excel (lebar = karakter, tinggi = pt) — 96 dp ≈ 8,43 karakter, 40 dp ≈ 15 pt. */
        const val DP_PER_CHAR = 11.4f
        const val DP_PER_PT = 40f / 15f
    }
}

/** Riwayat Urungkan/Ulangi. */
class UndoStack(private val limit: Int = 200) {
    class Edit(val label: String, val undo: () -> Unit, val redo: () -> Unit)
    private val done = ArrayDeque<Edit>()
    private val undone = ArrayDeque<Edit>()
    val canUndo get() = done.isNotEmpty()
    val canRedo get() = undone.isNotEmpty()

    fun push(e: Edit) {
        done.addLast(e); undone.clear()
        while (done.size > limit) done.removeFirst()
    }
    fun undo(): Edit? = done.removeLastOrNull()?.also { it.undo(); undone.addLast(it) }
    fun redo(): Edit? = undone.removeLastOrNull()?.also { it.redo(); done.addLast(it) }
    fun clear() { done.clear(); undone.clear() }
}

/**
 * Rujukan sel di dalam rumus. Teks di antara tanda kutip tidak disentuh; nama fungsi (huruf+angka diikuti "(",
 * mis. LOG10) bukan rujukan. Rujukan yang hilang menjadi #REF! — sama seperti Excel.
 */
object FormulaRefs {
    private val REF = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})")

    private class Ref(var colAbs: Boolean, var col: Int, var rowAbs: Boolean, var row: Int) {
        override fun toString() = (if (colAbs) "$" else "") + CellRange.colName(col) + (if (rowAbs) "$" else "") + (row + 1)
    }

    private fun colIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    /**
     * Tulis ulang setiap rujukan / rentang dalam [f] lewat [map]. [map] menerima (awal, akhir) — akhir null untuk
     * rujukan tunggal — dan mengembalikan teks pengganti.
     */
    private fun rewrite(f: String, map: (Ref, Ref?) -> String): String {
        if (!f.startsWith("=")) return f
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        while (i < f.length) {
            val ch = f[i]
            if (ch == '"') { inStr = !inStr; sb.append(ch); i++; continue }
            if (inStr) { sb.append(ch); i++; continue }
            val prev = if (i > 0) f[i - 1] else ' '
            if ((ch == '$' || ch.isLetter()) && !(prev.isLetterOrDigit() || prev == '_' || prev == '.')) {
                val m1 = REF.matchAt(f, i)
                if (m1 != null && !followedByNameChar(f, m1.range.last + 1)) {
                    val a = toRef(m1)
                    var end = m1.range.last + 1
                    var b: Ref? = null
                    if (end < f.length && f[end] == ':') {
                        val m2 = REF.matchAt(f, end + 1)
                        if (m2 != null && !followedByNameChar(f, m2.range.last + 1)) { b = toRef(m2); end = m2.range.last + 1 }
                    }
                    if (a != null) { sb.append(map(a, b)); i = end; continue }
                }
                // nama fungsi / teks lain: salin utuh sampai bukan huruf/angka
                var j = i
                while (j < f.length && (f[j].isLetterOrDigit() || f[j] == '_' || f[j] == '.' || f[j] == '$')) j++
                sb.append(f, i, j); i = j; continue
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }

    private fun followedByNameChar(f: String, at: Int): Boolean =
        at < f.length && (f[at] == '(' || f[at].isLetterOrDigit() || f[at] == '_' || f[at] == '!')

    private fun toRef(m: MatchResult): Ref? {
        val col = colIndex(m.groupValues[2]); val row = m.groupValues[4].toIntOrNull()?.minus(1) ?: return null
        if (col < 0 || col > 16383 || row < 0) return null
        return Ref(m.groupValues[1] == "$", col, m.groupValues[3] == "$", row)
    }

    /** Salin/pindah rumus sejauh (dr, dc): bagian relatif ikut bergeser, bagian $ tetap. */
    fun shift(f: String, dr: Int, dc: Int): String {
        if (dr == 0 && dc == 0) return f
        return rewrite(f) { a, b ->
            fun mv(x: Ref): Boolean {
                if (!x.rowAbs) x.row += dr
                if (!x.colAbs) x.col += dc
                return x.row >= 0 && x.col >= 0
            }
            val ok = mv(a) and (b == null || mv(b))
            if (!ok) "#REF!" else if (b == null) a.toString() else "$a:$b"
        }
    }

    /**
     * Baris ([rows] = true) atau kolom disisipkan ([n] > 0) / dihapus ([n] < 0) di [at]: semua rujukan (juga yang
     * $) ke indeks ≥ [at] ikut bergeser; rentang menyusut/memanjang; rujukan ke bagian yang dihapus menjadi #REF!.
     */
    fun structural(f: String, rows: Boolean, at: Int, n: Int): String {
        if (n == 0) return f
        return rewrite(f) { a, b ->
            fun get(x: Ref) = if (rows) x.row else x.col
            fun put(x: Ref, v: Int) { if (rows) x.row = v else x.col = v }
            if (n > 0) {
                if (get(a) >= at) put(a, get(a) + n)
                if (b != null && get(b) >= at) put(b, get(b) + n)
                if (b == null) a.toString() else "$a:$b"
            } else {
                val delEnd = at - n - 1   // indeks terakhir yang dihapus
                fun mapIdx(i: Int): Int = if (i > delEnd) i + n else i
                if (b == null) {
                    if (get(a) in at..delEnd) "#REF!" else { put(a, mapIdx(get(a))); a.toString() }
                } else {
                    val lo = minOf(get(a), get(b)); val hi = maxOf(get(a), get(b))
                    if (lo >= at && hi <= delEnd) "#REF!"
                    else {
                        val nlo = if (lo in at..delEnd) at else mapIdx(lo)
                        val nhi = if (hi in at..delEnd) at - 1 else mapIdx(hi)
                        if (get(a) <= get(b)) { put(a, nlo); put(b, nhi) } else { put(b, nlo); put(a, nhi) }
                        "$a:$b"
                    }
                }
            }
        }
    }
}
