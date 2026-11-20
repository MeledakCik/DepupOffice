package cloud.depup.office

import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Bagian murni (tanpa Android) dari lembar Cetak DepUp: membaca/menulis nomor halaman ("1-3, 5") dan menyusun PDF
 * tugas cetak — halaman pilihan × salinan — langsung dari objek PDF aslinya (tanpa render ulang). Diuji JVM di
 * PrintPagesTest.
 */
object PrintPages {
    /**
     * "1-3, 5, 8-" → halaman 0-based, urut & tanpa ganda. null bila kosong, bukan angka, atau di luar 1..[n].
     * "8-" = sampai halaman terakhir, "-3" = dari halaman pertama.
     */
    fun parse(t: String, n: Int): List<Int>? {
        val out = sortedSetOf<Int>()
        val norm = t.replace(Regex("\\s*[-–—]\\s*"), "-")
        for (p in norm.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d+)?(-)?(\\d+)?$").find(p) ?: return null
            val a = m.groupValues[1].toIntOrNull(); val dash = m.groupValues[2].isNotEmpty(); val b = m.groupValues[3].toIntOrNull()
            val (from, to) = when {
                !dash && a != null -> a to a
                dash && a != null && b != null -> a to b
                dash && a != null -> a to n
                dash && b != null -> 1 to b
                else -> return null
            }
            if (from !in 1..n || to !in 1..n) return null
            for (k in min(from, to)..max(from, to)) out.add(k - 1)
        }
        return if (out.isEmpty()) null else out.toList()
    }

    /** Teks ringkas "1-3, 5" dari halaman 0-based. */
    fun text(pages: Collection<Int>): String {
        val s = pages.sorted()
        val sb = StringBuilder()
        var i = 0
        while (i < s.size) {
            var j = i
            while (j + 1 < s.size && s[j + 1] == s[j] + 1) j++
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(s[i] + 1)
            if (j > i) sb.append('-').append(s[j] + 1)
            i = j + 1
        }
        return sb.toString()
    }

    private val INHERIT = listOf("Resources", "MediaBox", "CropBox", "Rotate")

    /**
     * PDF tugas cetak: halaman [pages] (0-based, urut) diulang [copies] kali dan tersusun (1-2-3, 1-2-3), TANPA render
     * ulang — isi & sumber daya halaman dipakai bersama, berkas hampir tidak membesar. [duplex] + jumlah halaman ganjil:
     * tiap salinan diberi halaman kosong di belakang supaya salinan berikutnya mulai di lembar baru.
     * false = tidak bisa (terkunci, rusak, terlalu besar) → pemanggil memakai berkas asli.
     */
    fun build(src: File, pages: List<Int>, copies: Int, duplex: Boolean, out: File): Boolean {
        val tmp = File(out.parentFile, out.name + ".part")
        return try {
            val f = PdfFile.open(src.readBytes())
            if (f.encrypted) return false
            val all = f.pages()
            if (pages.isEmpty() || pages.any { it !in all.indices || all[it].ref == null }) return false
            FileOutputStream(tmp).buffered(1 shl 16).use { os ->
                PdfSave.write(f, os) { ctx ->
                    val node = PDict().also { it["Type"] = PName("Pages") }
                    val nodeRef = ctx.add(node)
                    // atribut warisan (sumber daya, ukuran, rotasi) dijadikan milik halaman sendiri: induk lamanya tak dipakai
                    val flat = HashMap<Int, PDict>()
                    fun flatten(i: Int): PDict = flat.getOrPut(i) {
                        val p = all[i]
                        val d = PDict(LinkedHashMap(p.dict.m))
                        var par = f.rdict(p.dict["Parent"]); var depth = 0
                        while (par != null && depth++ < 64) {
                            for (k in INHERIT) if (d[k] == null && par[k] != null) d[k] = par[k]
                            par = f.rdict(par["Parent"])
                        }
                        if (d["MediaBox"] == null) d["MediaBox"] = listOf(0L, 0L, 612L, 792L)
                        if (d["Resources"] == null) d["Resources"] = PDict()
                        d["Parent"] = nodeRef
                        d
                    }
                    val kids = ArrayList<Any?>()
                    val pad = duplex && copies > 1 && pages.size % 2 == 1
                    for (c in 0 until copies) {
                        for (i in pages) {
                            val d = flatten(i)
                            kids.add(if (c == 0) all[i].ref!!.also { ctx.put(it, d) } else ctx.add(PDict(LinkedHashMap(d.m))))
                        }
                        if (pad && c < copies - 1) {
                            val last = flatten(pages.last())
                            kids.add(ctx.add(PDict().also { b ->
                                b["Type"] = PName("Page"); b["Parent"] = nodeRef
                                for (k in listOf("MediaBox", "CropBox", "Rotate")) last[k]?.let { b[k] = it }
                                b["Resources"] = PDict()
                            }))
                        }
                    }
                    node["Kids"] = kids
                    node["Count"] = kids.size.toLong()
                    ctx.catalog["Pages"] = nodeRef
                    // label & tujuan pembuka masih menunjuk susunan lama: tidak dibutuhkan untuk mencetak
                    ctx.catalog.m.remove("PageLabels"); ctx.catalog.m.remove("OpenAction")
                }
            }
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            true
        } catch (_: Throwable) {   // termasuk OutOfMemoryError (PDF sangat besar)
            tmp.delete(); out.delete(); false
        }
    }

    /** Jumlah halaman PDF tugas cetak (termasuk halaman kosong pengganjal bolak-balik). */
    fun jobPages(pages: Int, copies: Int, duplex: Boolean): Int =
        pages * copies + (if (duplex && copies > 1 && pages % 2 == 1) copies - 1 else 0)
}
