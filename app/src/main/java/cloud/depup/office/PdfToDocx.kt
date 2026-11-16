package cloud.depup.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF → Word (.docx) "semirip mungkin" (murni Kotlin, diuji di JVM). Isi PDF dibaca langsung ([PdfLayout]):
 *  - teks ASLI (bukan OCR) dengan font, ukuran, tebal/miring, warna; perataan kiri/tengah/kanan/rata kanan-kiri,
 *    inden, spasi baris & jarak antar-paragraf sesuai PDF; kolom bercelah lebar jadi tab,
 *  - tabel dari garis-garis PDF: ukuran kolom/baris, sel gabungan, warna arsiran, garis per sisi,
 *  - foto (JPEG asli / PNG transparan) di posisinya,
 *  - bentuk (kotak, kotak bersudut bulat, lingkaran, garis, poligon) sebagai shape Word di posisi persis halaman,
 *  - unsur berdampingan (logo + kop surat, foto + teks) disusun dalam tabel tata letak tanpa garis,
 *  - ukuran & margin halaman per halaman (section).
 * Halaman hasil pindai (tanpa teks) diserahkan ke [Scan] (OCR di lapisan Android).
 */
/** [editPassword] != null → dokumen hanya-baca; mengedit wajib memasukkan password itu ([DocxProtect]). */
class PdfToDocx(private val out: File, private val editPassword: String? = null) {

    /** Lapisan Android: OCR halaman pindaian → blok paragraf/tabel; null = tidak ada OCR. */
    interface Scan {
        fun ocr(pageIndex: Int): List<Block>?
        /** Gambar halaman (JPEG) bila isi tak terbaca yakin: (jpeg, lebarPx, tinggiPx). */
        fun pageImage(pageIndex: Int): Triple<ByteArray, Int, Int>?
    }

    private val body = StringBuilder()
    /** Glif bergaris bawah / dicoret (dari garis tipis PDF di bawah / tengah teks). */
    private val underlined = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PdfLayout.Glyph, Boolean>())
    private val struck = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PdfLayout.Glyph, Boolean>())
    private val media = ArrayList<Pair<String, ByteArray>>()
    private var shapeId = 1
    private var pageNo = 0

    // ------------------------------------------------------------------ API

    fun convert(f: PdfFile, scan: Scan?, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val pages = f.pages()
        fun layout(i: Int): PdfLayout.PageOut {
            val p = pages[i]
            return try { PdfLayout.page(f, p) } catch (e: OutOfMemoryError) { throw e } catch (_: Throwable) { PdfLayout.PageOut(p.box[2] - p.box[0], p.box[3] - p.box[1], emptyList(), emptyList(), emptyList()) }
        }
        // 1) ukur batas isi tiap halaman saja (hemat memori: unsur halaman tidak disimpan)
        val bounds = ArrayList<Plan>()
        for (i in pages.indices) {
            progress(i + 1, pages.size * 2)
            val pl = plan(layout(i), i, scan, ocr = false)
            bounds.add(Plan(pl.w, pl.h, emptyList(), "", pl.minX, pl.maxX, pl.minY, pl.maxY, isScan = pl.isScan))
        }
        // 2) halaman berukuran sama = satu section dengan margin bersama; pergantian halaman = "halaman baru sebelum".
        //    Tiap halaman disusun ulang lalu langsung ditulis.
        var i = 0
        while (i < bounds.size) {
            var j = i + 1
            while (j < bounds.size && abs(bounds[j].w - bounds[i].w) < 2f && abs(bounds[j].h - bounds[i].h) < 2f) j++
            val grp = bounds.subList(i, j)
            val w = grp[0].w; val h = grp[0].h
            val ml = grp.minOf { it.minX }.coerceIn(14f, w * 0.4f)
            // +2 pt kelonggaran: metrik font di Word bisa sedikit lebih lebar → kata terakhir baris tidak turun duluan
            val mr = (grp.minOf { w - it.maxX } - 2f).coerceIn(12f, w * 0.4f)
            val mt = grp.minOf { it.minY }.coerceIn(10f, h * 0.4f)
            val mb = grp.minOf { (h - it.maxY) * 0.5f }.coerceIn(8f, 54f)
            val sect = Sect(w, h, ml, mr, mt, mb)
            for (k in i until j) {
                progress(pages.size + k + 1, pages.size * 2)
                if (k > 0) body.append(spacer(1f, pageBreak = true))
                emit(plan(layout(k), k, scan, ocr = true), sect)
            }
            if (j < bounds.size) body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr>${sectXml(sect)}</w:pPr></w:p>")
            else finalSect = sectXml(sect)
            i = j
        }
        if (pages.isEmpty()) body.append("<w:p/>")
        finish()
    }

    private class Plan(val w: Float, val h: Float, val items: List<Item>, val anchors: String, val minX: Float, val maxX: Float, val minY: Float, val maxY: Float,
                       val scan: List<Block>? = null, val scanImg: Triple<ByteArray, Int, Int>? = null, val isScan: Boolean = false)

    // ------------------------------------------------------------------ model halaman

    private class Line(val glyphs: List<PdfLayout.Glyph>, val base: Float, val size: Float) {
        val x0 = glyphs.minOf { it.x }
        val x1 = glyphs.maxOf { it.x + it.w }
        val top get() = base - size * 0.8f
        val bot get() = base + size * 0.22f
        /** Bagian baris yang dipisah celah lebar (kolom / tab). */
        val segs: List<List<PdfLayout.Glyph>> by lazy {
            val r = ArrayList<List<PdfLayout.Glyph>>(); var cur = ArrayList<PdfLayout.Glyph>()
            var lastX = Float.NaN
            for (g in glyphs) {
                if (!lastX.isNaN() && g.x - lastX > max(size * 1.6f, 14f) && g.text.isNotBlank()) { r.add(cur); cur = ArrayList() }
                if (g.text.isNotBlank() || cur.isNotEmpty()) cur.add(g)
                lastX = if (g.text.isBlank()) lastX.takeIf { !it.isNaN() } ?: g.x else g.x + g.w
            }
            if (cur.isNotEmpty()) r.add(cur)
            r
        }
    }

    private sealed class Item { abstract val x0: Float; abstract val x1: Float; abstract val top: Float; abstract val bot: Float }
    private class LItem(val l: Line) : Item() { override val x0 get() = l.x0; override val x1 get() = l.x1; override val top get() = l.top; override val bot get() = l.bot }
    private class IItem(val im: PdfLayout.Image) : Item() { override val x0 get() = im.x0; override val x1 get() = im.x1; override val top get() = im.y0; override val bot get() = im.y1 }
    /** Wilayah berkolom (teks koran / dua kolom): kolom-kolom unsur yang dipisah celah tegak. */
    private class CItem(val cols: List<List<Item>>) : Item() {
        override val x0 = cols.first().minOf { it.x0 }; override val x1 = cols.last().maxOf { it.x1 }
        override val top = cols.minOf { c -> c.minOf { it.top } }; override val bot = cols.maxOf { c -> c.maxOf { it.bot } }
    }
    private class TItem(val t: Tbl) : Item() { override val x0 get() = t.xs.first(); override val x1 get() = t.xs.last(); override val top get() = t.ys.first(); override val bot get() = t.ys.last() }

    /** Garis lurus (dari garis berstroke atau kotak tipis berisi). */
    private class Rule(val h: Boolean, var a: Float, var b: Float, val pos: Float, val w: Float, val color: Int)

    private class Tbl(val xs: List<Float>, val ys: List<Float>, val rules: List<Rule>) {
        val rows get() = ys.size - 1
        val cols get() = xs.size - 1
        val cellText = HashMap<Long, MutableList<PdfLayout.Glyph>>()
        val shade = HashMap<Long, Int>()
        fun key(r: Int, c: Int) = r.toLong() * 1000 + c
        /** Ada garis di batas tegak x=xs[c] antara baris r. */
        fun vRule(c: Int, r: Int): Rule? = rules.firstOrNull { !it.h && abs(it.pos - xs[c]) <= 2.5f && it.a <= ys[r] + 2.5f + (ys[r + 1] - ys[r]) * 0.4f && it.b >= ys[r + 1] - 2.5f - (ys[r + 1] - ys[r]) * 0.4f }
        fun hRule(r: Int, c: Int): Rule? = rules.firstOrNull { it.h && abs(it.pos - ys[r]) <= 2.5f && it.a <= xs[c] + 2.5f + (xs[c + 1] - xs[c]) * 0.4f && it.b >= xs[c + 1] - 2.5f - (xs[c + 1] - xs[c]) * 0.4f }
    }

    private fun plan(po: PdfLayout.PageOut, idx: Int, scan: Scan?, ocr: Boolean): Plan {
        pageNo = idx
        underlined.clear(); struck.clear()
        val visible = po.glyphs.filter { it.upright && it.text.isNotEmpty() }
        val rotated = po.glyphs.filter { !it.upright && !it.invisible && it.text.isNotEmpty() }
        val hasInvisible = visible.any { it.invisible }
        var images = po.images.filter { it.x1 > 0 && it.y1 > 0 && it.x0 < po.w && it.y0 < po.h }
        val pageArea = po.w * po.h
        // PDF hasil OCR (gambar halaman + teks tersembunyi): pakai teksnya, buang gambar latar sehalaman
        if (hasInvisible) images = images.filter { (it.x1 - it.x0) * (it.y1 - it.y0) < pageArea * 0.6f }
        val glyphs = visible
        // halaman pindaian: tanpa teks, ada gambar besar → OCR
        if (glyphs.count { it.text.isNotBlank() } < 3 && scan != null && po.images.any { (it.x1 - it.x0) * (it.y1 - it.y0) > pageArea * 0.4f }) {
            val blocks = if (!ocr) null else try { scan.ocr(idx) } catch (_: Throwable) { null }
            val img = if (ocr && blocks.isNullOrEmpty()) try { scan.pageImage(idx) } catch (_: Throwable) { null } else null
            return Plan(po.w, po.h, emptyList(), "", 54f, po.w - 54f, 54f, po.h - 54f, blocks, img, true)
        }

        // --- garis & bentuk
        val rules = ArrayList<Rule>()
        val fills = ArrayList<PdfLayout.Shape>()
        val graphics = ArrayList<PdfLayout.Shape>()
        for (s in po.shapes) {
            val b = s.bbox; val w = b[2] - b[0]; val h = b[3] - b[1]
            if (b[2] < -2 || b[3] < -2 || b[0] > po.w + 2 || b[1] > po.h + 2) continue
            if (s.fill != PdfLayout.NONE && s.isRect && (h <= 2.6f && w > 3f)) { rules.add(Rule(true, b[0], b[2], (b[1] + b[3]) / 2, max(h, 0.5f), s.fill)); continue }
            if (s.fill != PdfLayout.NONE && s.isRect && (w <= 2.6f && h > 3f)) { rules.add(Rule(false, b[1], b[3], (b[0] + b[2]) / 2, max(w, 0.5f), s.fill)); continue }
            if (s.stroke != PdfLayout.NONE && s.fill == PdfLayout.NONE && isPolyline(s)) {
                var any = false
                for (sub in s.subs) {
                    var i = 0
                    while (i + 3 < sub.size) {
                        val ax = sub[i]; val ay = sub[i + 1]; val bx = sub[i + 2]; val by = sub[i + 3]
                        if (abs(ay - by) < 0.8f && abs(ax - bx) > 1f) { rules.add(Rule(true, min(ax, bx), max(ax, bx), (ay + by) / 2, s.lineW, s.stroke)); any = true }
                        else if (abs(ax - bx) < 0.8f && abs(ay - by) > 1f) { rules.add(Rule(false, min(ay, by), max(ay, by), (ax + bx) / 2, s.lineW, s.stroke)); any = true }
                        i += 2
                    }
                }
                if (any && s.subs.all { axisOnly(it) }) continue
            }
            if (s.fill != PdfLayout.NONE && s.isRect && s.stroke == PdfLayout.NONE) { fills.add(s); continue }
            if (s.fill == 0xFFFFFFFF.toInt() && s.stroke == PdfLayout.NONE && w * h > pageArea * 0.8f) continue   // latar putih sehalaman
            graphics.add(s)
        }
        // garis tipis tepat di bawah / di tengah teks (tidak tersambung ke garis tegak) = garis bawah / coret
        run {
            val vs = rules.filter { !it.h }
            val it = rules.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (!r.h || r.w > 1.8f) continue
                if (vs.any { v -> abs(v.pos - r.a) <= 2.5f || abs(v.pos - r.b) <= 2.5f }) continue
                val inX = glyphs.filter { g -> g.text.isNotBlank() && g.x + g.w / 2 >= r.a - 1f && g.x + g.w / 2 <= r.b + 1f }
                val under = inX.filter { g -> r.pos - g.y in -0.5f..g.size * 0.32f }
                val strike = inX.filter { g -> g.y - r.pos in g.size * 0.15f..g.size * 0.45f }
                val hit = if (under.size >= strike.size) under else strike
                if (hit.isEmpty()) continue
                // garis harus sepanjang teksnya (garis tabel / pemisah lebih panjang dari tulisan di atasnya)
                if (r.a < hit.minOf { it.x } - 3f || r.b > hit.maxOf { it.x + it.w } + 3f) continue
                if (hit === under) underlined.addAll(hit) else struck.addAll(hit)
                it.remove()
            }
        }
        val tables = findTables(rules)
        // ruas garis asli yang menjadi bagian tabel (ruas di tabel sudah digabung, jadi cocokkan menurut letak)
        val usedRules = HashSet<Rule>()
        for (r in rules) if (tables.any { t -> r.pos >= (if (r.h) t.ys.first() else t.xs.first()) - 2.5f && r.pos <= (if (r.h) t.ys.last() else t.xs.last()) + 2.5f &&
                r.a >= (if (r.h) t.xs.first() else t.ys.first()) - 2.5f && r.b <= (if (r.h) t.xs.last() else t.ys.last()) + 2.5f }) usedRules.add(r)
        // arsiran sel & teks dalam sel
        val freeFills = ArrayList<PdfLayout.Shape>()
        for (fl in fills) {
            val b = fl.bbox
            var used = false
            for (t in tables) for (r in 0 until t.rows) for (c in 0 until t.cols) {
                val cx0 = t.xs[c]; val cx1 = t.xs[c + 1]; val cy0 = t.ys[r]; val cy1 = t.ys[r + 1]
                val ix = min(b[2], cx1) - max(b[0], cx0); val iy = min(b[3], cy1) - max(b[1], cy0)
                if (ix > 0 && iy > 0 && ix * iy >= (cx1 - cx0) * (cy1 - cy0) * 0.7f) { t.shade[t.key(r, c)] = fl.fill; used = true }
            }
            if (!used) freeFills.add(fl)
        }
        val free = ArrayList<PdfLayout.Glyph>()
        for (g in glyphs) {
            val cx = g.x + g.w / 2; val cy = g.y - g.size * 0.3f
            val t = tables.firstOrNull { cx >= it.xs.first() && cx <= it.xs.last() && cy >= it.ys.first() && cy <= it.ys.last() }
            if (t == null) { free.add(g); continue }
            val c = (0 until t.cols).firstOrNull { cx < t.xs[it + 1] } ?: (t.cols - 1)
            val r = (0 until t.rows).firstOrNull { cy < t.ys[it + 1] } ?: (t.rows - 1)
            t.cellText.getOrPut(t.key(r, c)) { ArrayList() }.add(g)
        }
        val lines = buildLines(free)
        val items = ArrayList<Item>()
        lines.forEach { items.add(LItem(it)) }
        images.forEach { items.add(IItem(it)) }
        tables.forEach { items.add(TItem(it)) }
        items.sortWith(compareBy({ it.top }, { it.x0 }))

        // bentuk bebas & isian di luar tabel → shape tertambat ke halaman (dipasang di paragraf teks pertama halaman)
        val anchors = StringBuilder()
        for (s in freeFills) anchors.append(shapeXml(s, behind = true))
        for (s in graphics) anchors.append(shapeXml(s, behind = s.fill != PdfLayout.NONE))
        for (r in rules) if (r !in usedRules) anchors.append(ruleXml(r))
        rotatedText(rotated, anchors)
        return Plan(po.w, po.h, items, anchors.toString(),
            items.minOfOrNull { it.x0 } ?: 72f, items.maxOfOrNull { it.x1 } ?: (po.w - 72f),
            items.minOfOrNull { it.top } ?: 72f, items.maxOfOrNull { it.bot } ?: (po.h - 72f))
    }

    private fun emit(pl: Plan, sect: Sect) {
        if (pl.isScan) { scanPage(pl, sect); return }
        val startLen = body.length
        val anchors = StringBuilder(pl.anchors)
        flow(pl.items, sect.ml, sect.w - sect.mr, sect.mt, anchors)
        if (body.length == startLen || anchors.isNotEmpty()) body.append(spacer(1f, anchors.toString()))
    }

    private fun isPolyline(s: PdfLayout.Shape) = s.subs.all { it.size <= 64 }
    private fun axisOnly(sub: FloatArray): Boolean {
        var i = 0
        while (i + 3 < sub.size) { if (abs(sub[i] - sub[i + 2]) > 0.8f && abs(sub[i + 1] - sub[i + 3]) > 0.8f) return false; i += 2 }
        return true
    }

    // ------------------------------------------------------------------ baris

    private fun buildLines(gs: List<PdfLayout.Glyph>): List<Line> {
        // glif besar membentuk baris lebih dulu; pangkat/indeks (lebih kecil, naik/turun) lalu menempel ke baris terdekat
        val sorted = gs.sortedWith(compareBy({ -(it.size * 2).roundToInt() }, { it.y }, { it.x }))
        class Row(val base: Float, val size: Float, val g: MutableList<PdfLayout.Glyph>) { var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE }
        val rows = ArrayList<Row>()
        for (g in sorted) {
            var best: Row? = null; var bd = Float.MAX_VALUE
            for (r in rows) {
                val dy = g.y - r.base
                val small = g.size < r.size * 0.88f
                val ok = if (small) dy >= -r.size * 0.6f && dy <= r.size * 0.4f else abs(dy) <= max(r.size, g.size) * 0.35f
                // pangkat harus bersebelahan dengan isi baris (bukan baris lain di kolom sebelah)
                if (ok && small && (g.x > r.x1 + r.size * 2f || g.x + g.w < r.x0 - r.size * 2f)) continue
                if (ok && abs(dy) < bd) { bd = abs(dy); best = r }
            }
            val r = best ?: Row(g.y, g.size, ArrayList()).also { rows.add(it) }
            r.g.add(g); r.x0 = min(r.x0, g.x); r.x1 = max(r.x1, g.x + g.w)
        }
        val out = ArrayList<Line>()
        for (r in rows) {
            val s = r.g.sortedBy { it.x }
            // buang glif kembar (teks tebal palsu: huruf yang sama digambar dua kali bergeser sedikit)
            val dedup = ArrayList<PdfLayout.Glyph>()
            for (g in s) { val p = dedup.lastOrNull(); if (p != null && p.text == g.text && abs(p.x - g.x) < g.size * 0.15f && abs(p.y - g.y) < 0.5f && g.text.isNotBlank()) continue; dedup.add(g) }
            if (dedup.any { it.text.isNotBlank() }) out.add(Line(dedup, r.base, r.size))
        }
        return out.sortedBy { it.base }
    }

    // ------------------------------------------------------------------ tabel dari garis

    private fun findTables(rules0: List<Rule>): List<Tbl> {
        // gabungkan ruas segaris yang bersambung
        fun merge(list: List<Rule>): List<Rule> {
            val out = ArrayList<Rule>()
            for (r in list.sortedWith(compareBy({ (it.pos * 2).roundToInt() }, { it.a }))) {
                val p = out.lastOrNull()
                if (p != null && abs(p.pos - r.pos) <= 1.2f && r.a <= p.b + 2f) { p.b = max(p.b, r.b) } else out.add(Rule(r.h, r.a, r.b, r.pos, r.w, r.color))
            }
            return out
        }
        val hs = merge(rules0.filter { it.h }); val vs = merge(rules0.filter { !it.h })
        val all = hs + vs
        if (hs.size < 2 || vs.size < 2) return emptyList()
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for ((i, h) in hs.withIndex()) for ((j, v) in vs.withIndex()) {
            if (v.pos >= h.a - 2.5f && v.pos <= h.b + 2.5f && h.pos >= v.a - 2.5f && h.pos <= v.b + 2.5f) {
                val a = find(i); val b = find(hs.size + j); if (a != b) parent[a] = b
            }
        }
        val groups = all.indices.groupBy { find(it) }.values
        val out = ArrayList<Tbl>()
        for (g in groups) {
            val rs = g.map { all[it] }
            val gh = rs.filter { it.h }; val gv = rs.filter { !it.h }
            if (gh.size < 2 || gv.size < 2) continue
            val xs = cluster(gv.map { it.pos }); val ys = cluster(gh.map { it.pos })
            if (xs.size < 2 || ys.size < 2) continue
            if (xs.last() - xs.first() < 20f || ys.last() - ys.first() < 8f) continue
            // tolak kotak tunggal raksasa (bingkai halaman) — tetap tabel 1x1 bila kecil
            out.add(Tbl(xs, ys, rs))
        }
        return out
    }

    private fun cluster(v: List<Float>): List<Float> {
        val s = v.sorted(); val out = ArrayList<Float>(); var acc = ArrayList<Float>()
        for (x in s) { if (acc.isNotEmpty() && x - acc.last() > 2.5f) { out.add(acc.average().toFloat()); acc = ArrayList() }; acc.add(x) }
        if (acc.isNotEmpty()) out.add(acc.average().toFloat())
        return out
    }

    // ------------------------------------------------------------------ alur (flow)

    /** Susun unsur dalam wilayah [left,right] mulai [top] ke [body]. [anchors] ditempel di paragraf pertama. */
    private fun flow(items0: List<Item>, left: Float, right: Float, top: Float, anchors: StringBuilder, sink: StringBuilder = body) {
        var cursor = top
        var i = 0
        val items = columnize(items0)
        val sorted = items.sortedWith(compareBy({ it.top }, { it.x0 }))
        // baris teks beruntun (lintas pita) dikumpulkan dulu supaya bisa jadi paragraf utuh
        val pending = ArrayList<Line>()
        fun flushLines() { if (pending.isNotEmpty()) { cursor = paragraphs(ArrayList(pending), left, right, cursor, anchors, sink); pending.clear() } }
        while (i < sorted.size) {
            // pita: unsur yang saling tumpang secara tegak
            var bandBot = sorted[i].bot
            var j = i + 1
            while (j < sorted.size && sorted[j].top < bandBot - 1.5f) { bandBot = max(bandBot, sorted[j].bot); j++ }
            val band = sorted.subList(i, j)
            val ci = band.singleOrNull() as? CItem
            if (ci != null) {
                flushLines()
                layoutRow(ci.cols, left, right, cursor, anchors, sink)
                cursor = ci.bot; i = j; continue
            }
            val cols = columns(band)
            if (cols.size >= 2) {
                flushLines()
                layoutRow(cols, left, right, cursor, anchors, sink)
                cursor = band.maxOf { it.bot }
            } else {
                // satu kolom: baris-baris jadi paragraf, sisanya berurutan
                for (it0 in band.sortedWith(compareBy({ it.top }, { it.x0 }))) when (it0) {
                    is LItem -> pending.add(it0.l)
                    is IItem -> { flushLines(); cursor = imagePara(it0.im, left, right, cursor, anchors, sink) }
                    is TItem -> { flushLines(); cursor = table(it0.t, left, right, cursor, anchors, sink) }
                    is CItem -> { flushLines(); layoutRow(it0.cols, left, right, cursor, anchors, sink); cursor = it0.bot }
                }
            }
            i = j
        }
        flushLines()
    }

    /**
     * Cari celah tegak (gutter) yang dipakai bersama beberapa pasang baris berdampingan; wilayah di antaranya dipecah
     * jadi kolom kiri/kanan ([CItem]). Baris yang melintasi celah (judul selebar halaman) memutus wilayah.
     */
    private fun columnize(items0: List<Item>): List<Item> {
        val ls = items0.filterIsInstance<LItem>().map { it.l }
        if (ls.size < 4) return items0
        val gaps = ArrayList<FloatArray>()            // [kiri, kanan, garisdasarA, garisdasarB]
        // celah di DALAM baris (kolom yang garis dasarnya kebetulan sama)
        for (l in ls) for (k in 1 until l.segs.size) {
            val a = l.segs[k - 1].maxOf { it.x + it.w }; val b = l.segs[k].minOf { it.x }
            if (b - a >= 8f) gaps.add(floatArrayOf(a, b, l.base, l.base))
        }
        // pasangan potongan baris berdampingan (garis dasar berbeda sedikit); potongan = segmen baris (dipisah celah lebar)
        val pieces = ArrayList<FloatArray>()        // [x0, x1, garisdasar, ukuran, indeksBaris]
        for ((li, l) in ls.withIndex()) for (sg in l.segs) pieces.add(floatArrayOf(sg.minOf { it.x }, sg.maxOf { it.x + it.w }, l.base, l.size, li.toFloat()))
        pieces.sortBy { it[2] }
        for ((ai, a) in pieces.withIndex()) {
            var k = ai + 1
            while (k < pieces.size && pieces[k][2] - a[2] <= max(a[3], pieces[k][3]) * 1.6f) {
                val b = pieces[k]
                if (b[4] != a[4]) {
                    if (a[1] < b[0] - 6f) gaps.add(floatArrayOf(a[1], b[0], a[2], b[2]))
                    else if (b[1] < a[0] - 6f) gaps.add(floatArrayOf(b[1], a[0], a[2], b[2]))
                }
                k++
            }
        }
        if (gaps.size < 3) return items0
        // titik x yang paling banyak tercakup celah
        val ev = gaps.flatMap { listOf(it[0] to 1, it[1] to -1) }.sortedWith(compareBy({ it.first }, { -it.second }))
        var c = 0; var best = 0; var gx = 0f
        for ((k, e) in ev.withIndex()) { c += e.second; if (c > best && k + 1 < ev.size) { best = c; gx = (e.first + ev[k + 1].first) / 2 } }
        val ev2 = gaps.filter { it[0] <= gx && it[1] >= gx }
        // bukti dari minimal 3 garis dasar berbeda
        if (ev2.flatMap { listOf(it[2].roundToInt(), it[3].roundToInt()) }.toSet().size < 3) return items0
        // pecah baris yang punya celah tepat di gx
        val items = ArrayList<Item>()
        for (it in items0) {
            val l = (it as? LItem)?.l
            if (l != null && l.x0 < gx && l.x1 > gx && l.glyphs.none { g -> g.text.isNotBlank() && g.x < gx - 0.5f && g.x + g.w > gx + 0.5f }) {
                val lg = l.glyphs.filter { g -> g.x + g.w / 2 < gx }; val rg = l.glyphs.filter { g -> g.x + g.w / 2 >= gx }
                if (lg.any { g -> g.text.isNotBlank() }) items.add(LItem(Line(lg, l.base, l.size)))
                if (rg.any { g -> g.text.isNotBlank() }) items.add(LItem(Line(rg, l.base, l.size)))
            } else items.add(it)
        }
        val sz = ls.filter { l -> ev2.any { abs(it[2] - l.base) < 0.5f || abs(it[3] - l.base) < 0.5f } }.maxOfOrNull { it.size } ?: ls.maxOf { it.size }
        val yTop = ev2.minOf { min(it[2], it[3]) } - sz
        val yBot = ev2.maxOf { max(it[2], it[3]) } + sz * 0.3f
        val inRange = items.filter { it.top >= yTop - 2f && it.top <= yBot }
        val out = ArrayList(items.filter { it !in inRange })
        // potong wilayah pada unsur yang melintasi celah
        val seq = inRange.sortedBy { it.top }
        var l = ArrayList<Item>(); var r = ArrayList<Item>()
        fun flush() {
            if (l.size + r.size >= 3 && l.isNotEmpty() && r.isNotEmpty()) out.add(CItem(listOf(l, r))) else { out.addAll(l); out.addAll(r) }
            l = ArrayList(); r = ArrayList()
        }
        for (it in seq) when {
            it.x1 <= gx + 1f -> l.add(it)
            it.x0 >= gx - 1f -> r.add(it)
            else -> { flush(); out.add(it) }
        }
        flush()
        return out
    }

    /** Kelompokkan unsur pita jadi kolom yang tidak saling tumpang mendatar. */
    private fun columns(band: List<Item>): List<List<Item>> {
        if (band.size < 2) return listOf(band)
        val s = band.sortedBy { it.x0 }
        val cols = ArrayList<MutableList<Item>>(); var end = -Float.MAX_VALUE
        for (it in s) {
            if (cols.isEmpty() || it.x0 > end + 4f) { cols.add(arrayListOf(it)); end = it.x1 } else { cols.last().add(it); end = max(end, it.x1) }
        }
        // kolom berisi tabel penuh tidak dipecah
        if (cols.size >= 2 && cols.any { c -> c.any { it is TItem } } && band.size == cols.sumOf { it.size } && cols.all { c -> c.all { it is TItem } }) return cols
        // baris teks biasa bercelah lebar (mis. "Nama ...... Jabatan") sudah ditangani tab — hanya pecah bila
        // ada unsur bertinggi lebih dari satu baris (foto, beberapa baris) di salah satu kolom
        if (cols.size >= 2 && cols.all { c -> c.size == 1 && c[0] is LItem }) return listOf(band)
        return cols
    }

    /** Unsur berdampingan → tabel tata letak 1 baris tanpa garis. */
    private fun layoutRow(cols: List<List<Item>>, left: Float, right: Float, cursor: Float, anchors: StringBuilder, sink: StringBuilder) {
        val top = cols.minOf { c -> c.minOf { it.top } }
        val bot = cols.maxOf { c -> c.maxOf { it.bot } }
        // batas kolom: tengah celah antar kolom
        val bounds = ArrayList<Float>()
        bounds.add(min(left, cols[0].minOf { it.x0 }))
        for (k in 1 until cols.size) bounds.add((cols[k - 1].maxOf { it.x1 } + cols[k].minOf { it.x0 }) / 2f)
        bounds.add(max(right, cols.last().maxOf { it.x1 }))
        val before = max(0f, top - cursor)
        if (before > 0.5f) sink.append(spacer(before))
        val grid = (0 until cols.size).joinToString("") { "<w:gridCol w:w=\"${tw(bounds[it + 1] - bounds[it])}\"/>" }
        sink.append("<w:tbl><w:tblPr><w:tblW w:w=\"${tw(bounds.last() - bounds.first())}\" w:type=\"dxa\"/><w:tblInd w:w=\"${tw(bounds.first() - left)}\" w:type=\"dxa\"/>")
        sink.append("<w:tblBorders>${noBorders()}</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"0\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/></w:tblCellMar><w:tblLook w:val=\"0000\"/></w:tblPr>")
        sink.append("<w:tblGrid>$grid</w:tblGrid><w:tr><w:trPr><w:trHeight w:val=\"${tw(bot - top)}\" w:hRule=\"atLeast\"/><w:cantSplit/></w:trPr>")
        for ((k, c) in cols.withIndex()) {
            sink.append("<w:tc><w:tcPr><w:tcW w:w=\"${tw(bounds[k + 1] - bounds[k])}\" w:type=\"dxa\"/></w:tcPr>")
            val cell = StringBuilder()
            flow(c, bounds[k], bounds[k + 1], top, StringBuilder(), cell)
            if (cell.isEmpty() || !cell.endsWith("</w:p>")) cell.append(spacer(1f))
            sink.append(cell).append("</w:tc>")
        }
        sink.append("</w:tr></w:tbl>")
    }

    // ------------------------------------------------------------------ paragraf

    private class Para(val lines: MutableList<Line>)

    private fun paragraphs(lines: List<Line>, left: Float, right: Float, cursor0: Float, anchors: StringBuilder, sink: StringBuilder): Float {
        var cursor = cursor0
        val paras = ArrayList<Para>()
        // tepi kanan teks yang sebenarnya (di kolom, batas sel ada di tengah celah)
        val textRight = min(right, lines.maxOf { it.x1 } + 0.5f).let { if (right - it < 40f) it else right }
        val width = right - left
        val cx = (left + right) / 2
        fun full(l: Line) = l.x1 >= textRight - max(l.size * 3.5f, width * 0.12f)
        fun centered(l: Line) = abs((l.x0 + l.x1) / 2 - cx) <= max(3f, l.size * 0.4f) && l.x0 > left + l.size
        for (l in lines) {
            val p = paras.lastOrNull()
            if (p != null) {
                val prev = p.lines.last()
                val d = l.base - prev.base
                val okGap = d > 0 && d <= max(prev.size, l.size) * 1.75f
                val okSize = abs(l.size - prev.size) <= max(l.size, prev.size) * 0.12f
                val prevD = if (p.lines.size >= 2) prev.base - p.lines[p.lines.size - 2].base else d
                val okRhythm = abs(d - prevD) <= l.size * 0.3f
                val firstX = p.lines[0].x0
                val bodyX = if (p.lines.size >= 2) p.lines[1].x0 else l.x0
                val okLeft = abs(l.x0 - bodyX) <= 2.5f && (p.lines.size >= 2 || l.x0 <= firstX + 1f && firstX - l.x0 <= l.size * 4f)
                val okCenter = centered(prev) && centered(l) && full(prev).not() && prev.segs.size == 1 && l.segs.size == 1 && false
                if (okGap && okSize && okRhythm && full(prev) && (okLeft || okCenter) && prev.segs.size == 1 && l.segs.size == 1) { p.lines.add(l); continue }
            }
            paras.add(Para(arrayListOf(l)))
        }
        var first = true
        for (p in paras) {
            val ls = p.lines
            val n = ls.size
            val lead = if (n >= 2) (ls[n - 1].base - ls[0].base) / (n - 1) else ls[0].size * 1.17f
            val lineH = max(lead, ls.maxOf { it.size } * 1.0f)
            val top = ls[0].base - lineH * 0.8f
            val before = max(0f, top - cursor)
            var align = "left"
            var indL = ls.minOf { it.x0 } - left
            var firstInd = ls[0].x0 - (if (n >= 2) ls[1].x0 else ls[0].x0)
            var indR = 0f
            val tabs = ArrayList<Pair<String, Float>>()
            if (n == 1) {
                val l = ls[0]
                if (l.segs.size == 1 && centered(l)) { align = "center"; indL = 0f; firstInd = 0f }
                else if (l.segs.size == 1 && abs(l.x1 - right) <= max(3f, l.size * 0.5f) && l.x0 > left + width * 0.25f) { align = "right"; indL = 0f; firstInd = 0f }
                else if (l.segs.size > 1) {
                    indL = l.x0 - left
                    for (sg in l.segs.drop(1)) {
                        val sx0 = sg.minOf { it.x }; val sx1 = sg.maxOf { it.x + it.w }
                        if (abs(sx1 - right) <= l.size * 0.6f) tabs.add("right" to (right - left)) else tabs.add("left" to (sx0 - left))
                    }
                }
            } else {
                val bodyLines = ls.dropLast(1)
                if (bodyLines.all { abs(it.x1 - textRight) <= max(2.5f, it.size * 0.35f) }) align = "both"
                if (ls.all { centered(it) }) { align = "center"; indL = 0f; firstInd = 0f }
                else indR = max(0f, right - ls.maxOf { it.x1 }).let { if (align == "both") 0f else min(it, width * 0.3f) }.let { 0f }
            }
            val runs = StringBuilder()
            for ((k, l) in ls.withIndex()) {
                if (k > 0) {
                    // sambung baris: spasi, kecuali pemenggalan "-" di ujung
                    val prevTxt = ls[k - 1].glyphs.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
                    if (!prevTxt.endsWith("-") && !prevTxt.endsWith("­")) runs.append(run(" ", l.glyphs.first()))
                }
                for ((si, sg) in l.segs.withIndex()) {
                    if (si > 0) runs.append("<w:r><w:tab/></w:r>")
                    runs.append(runsOf(sg, l.size, l.base))
                }
            }
            val anc = if (first && anchors.isNotEmpty()) anchors.toString().also { anchors.setLength(0) } else ""
            val tabXml = if (tabs.isEmpty()) null else tabs.joinToString("", "<w:tabs>", "</w:tabs>") { "<w:tab w:val=\"${it.first}\" w:pos=\"${tw(it.second)}\"/>" }
            sink.append("<w:p>").append(pPr(tw(max(0f, indL)), tw(firstInd), align, tw(before), tw(lineH), 0, tw(indR), tabXml)).append(anc).append(runs).append("</w:p>")
            cursor = ls.last().base + lineH * 0.2f
            first = false
        }
        return cursor
    }

    /** Run per kelompok glif berformat sama; spasi disisipkan pada celah antar-kata. */
    private fun runsOf(gs: List<PdfLayout.Glyph>, size: Float, base: Float): String {
        val sb = StringBuilder()
        var cur: PdfLayout.Glyph? = null
        val text = StringBuilder()
        var lastX = Float.NaN
        fun va(g: PdfLayout.Glyph) = if (g.size < size * 0.88f && g.y < base - size * 0.18f) 1 else if (g.size < size * 0.88f && g.y > base + size * 0.12f) -1 else 0
        fun flush() { val c = cur ?: return; if (text.isNotEmpty()) sb.append(run(text.toString(), c, va(c), c in underlined, c in struck)); text.setLength(0) }
        fun same(a: PdfLayout.Glyph, b: PdfLayout.Glyph) = a.font.family == b.font.family && a.font.bold == b.font.bold && a.font.italic == b.font.italic &&
            a.color == b.color && abs(a.size - b.size) < 0.6f && va(a) == va(b) && (a in underlined) == (b in underlined) && (a in struck) == (b in struck)
        for (g in gs) {
            val gap = if (lastX.isNaN()) 0f else g.x - lastX
            val needSpace = !lastX.isNaN() && gap > g.size * 0.17f && !text.endsWith(" ") && g.text != " "
            if (cur != null && !same(cur, g)) flush()
            if (needSpace) {
                // spasi ikut format sebelumnya kecuali garis bawahnya beda (spasi di antara kata bergaris bawah tetap bergaris)
                text.append(' ')
            }
            if (cur == null || text.isEmpty() || !same(cur, g)) cur = if (text.isEmpty()) g else cur
            if (g.text == " " && text.endsWith(" ")) { lastX = g.x + g.w; continue }
            text.append(g.text)
            lastX = g.x + g.w
        }
        flush()
        return sb.toString()
    }

    private fun run(t: String, g: PdfLayout.Glyph, vert: Int = 0, under: Boolean = false, strike: Boolean = false): String {
        val f = g.font
        // Word mengecilkan pangkat/indeks ±65%: ukuran disetel agar tampak sama dengan PDF
        val pt = if (vert != 0) g.size / 0.65f else g.size
        val sz = (pt * 2).roundToInt().coerceIn(2, 3276)
        val col = String.format("%06X", g.color and 0xFFFFFF)
        val rp = StringBuilder("<w:rPr><w:rFonts w:ascii=\"${esc(f.family)}\" w:hAnsi=\"${esc(f.family)}\" w:cs=\"${esc(f.family)}\" w:eastAsia=\"${esc(f.family)}\"/>")
        if (f.bold) rp.append("<w:b/><w:bCs/>")
        if (f.italic) rp.append("<w:i/><w:iCs/>")
        if (strike) rp.append("<w:strike/>")
        if (col != "000000") rp.append("<w:color w:val=\"$col\"/>")
        rp.append("<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/>")
        if (under) rp.append("<w:u w:val=\"single\"/>")
        if (vert != 0) rp.append("<w:vertAlign w:val=\"${if (vert > 0) "superscript" else "subscript"}\"/>")
        rp.append("</w:rPr>")
        return "<w:r>$rp<w:t xml:space=\"preserve\">${esc(t)}</w:t></w:r>"
    }

    /** Paragraf pemberi jarak / penampung: tinggi [h] pt lewat spasi-sebelum (dihormati semua penampil), tanda paragraf 1 pt. */
    private fun spacer(h: Float, inner: String = "", pageBreak: Boolean = false): String =
        "<w:p><w:pPr>${if (pageBreak) "<w:pageBreakBefore/>" else ""}<w:spacing w:before=\"${max(0, tw(h) - 20)}\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/><w:szCs w:val=\"2\"/></w:rPr></w:pPr>$inner</w:p>"

    private fun pPr(ind: Int, first: Int, align: String?, before: Int, line: Int, after: Int, indR: Int, tabs: String? = null): String {
        val sb = StringBuilder("<w:pPr>")
        if (tabs != null) sb.append(tabs)
        sb.append("<w:spacing w:before=\"${max(0, before)}\" w:after=\"${max(0, after)}\"")
        if (line > 0) sb.append(" w:line=\"$line\" w:lineRule=\"exact\"") else if (line < 0) sb.append(" w:line=\"${-line}\" w:lineRule=\"exact\"")
        sb.append("/>")
        if (line == 0 && after < 0) {}
        if (ind != 0 || first != 0 || indR != 0) {
            sb.append("<w:ind w:left=\"$ind\" w:right=\"$indR\"")
            if (first > 0) sb.append(" w:firstLine=\"$first\"") else if (first < 0) sb.append(" w:hanging=\"${-first}\"")
            sb.append("/>")
        }
        if (align != null && align != "left") sb.append("<w:jc w:val=\"$align\"/>")
        sb.append("</w:pPr>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ foto

    private fun imagePara(im: PdfLayout.Image, left: Float, right: Float, cursor: Float, anchors: StringBuilder, sink: StringBuilder): Float {
        val w = im.x1 - im.x0; val h = im.y1 - im.y0
        val before = max(0f, im.y0 - cursor)
        val anc = if (anchors.isNotEmpty()) anchors.toString().also { anchors.setLength(0) } else ""
        val ind = max(0f, im.x0 - left)
        sink.append("<w:p>").append(pPr(tw(ind), 0, null, tw(before), 0, 0, 0)).append(anc).append("<w:r>").append(inlinePic(im, w, h)).append("</w:r></w:p>")
        return im.y1
    }

    private fun addMedia(im: PdfLayout.Image): String? {
        val (data, ext) = when { im.jpeg != null -> im.jpeg to "jpeg"; im.png != null -> im.png to "png"; else -> return null }
        val name = "image${media.size + 1}.$ext"
        media.add(name to data)
        return "rIdImg${media.size}"
    }

    private fun inlinePic(im: PdfLayout.Image, w: Float, h: Float): String {
        val rid = addMedia(im) ?: return ""
        val id = shapeId++
        val cx = emu(w); val cy = emu(h)
        return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Gambar $id\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Gambar $id\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing>"
    }

    // ------------------------------------------------------------------ tabel

    private fun table(t: Tbl, left: Float, right: Float, cursor: Float, anchors: StringBuilder, sink: StringBuilder): Float {
        val before = max(0f, t.ys.first() - cursor)
        // paragraf pemberi jarak setinggi celah (shape tertambat TIDAK dipasang di sini — lihat flow)
        if (before > 0.5f) sink.append(spacer(before))
        // gabung sel: sel (r,c) menyatu ke kanan bila tidak ada garis tegak di antaranya, ke bawah bila tak ada garis datar
        val rows = t.rows; val cols = t.cols
        val owner = Array(rows) { IntArray(cols) { -1 } }
        val spans = ArrayList<IntArray>()      // r, c, rs, cs
        for (r in 0 until rows) for (c in 0 until cols) {
            if (owner[r][c] >= 0) continue
            var cs = 1
            while (c + cs < cols && t.vRule(c + cs, r) == null) cs++
            var rs = 1
            while (r + rs < rows && (c until c + cs).all { t.hRule(r + rs, it) == null }) rs++
            val id = spans.size
            spans.add(intArrayOf(r, c, rs, cs))
            for (rr in r until r + rs) for (cc in c until c + cs) owner[rr][cc] = id
        }
        val w = t.xs.last() - t.xs.first()
        val centered = abs((t.xs.first() + t.xs.last()) / 2 - (left + right) / 2) <= 3f && t.xs.first() - left > 6f
        sink.append("<w:tbl><w:tblPr><w:tblW w:w=\"${tw(w)}\" w:type=\"dxa\"/>" +
            (if (centered) "<w:jc w:val=\"center\"/>" else "<w:tblInd w:w=\"${tw(t.xs.first() - left)}\" w:type=\"dxa\"/>"))
        // semua batas bergaris sama → garis tingkat tabel (paling luas didukung); pengecualian ditangani per sel
        val any = t.rules.firstOrNull()
        val uniform = any != null && t.rules.all { abs(it.w - any.w) < 0.3f && it.color == any.color }
        sink.append("<w:tblBorders>${if (uniform) listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { border(it, any) } else noBorders()}</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"0\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/></w:tblCellMar><w:tblLook w:val=\"0000\"/></w:tblPr><w:tblGrid>")
        for (c in 0 until cols) sink.append("<w:gridCol w:w=\"${tw(t.xs[c + 1] - t.xs[c])}\"/>")
        sink.append("</w:tblGrid>")
        for (r in 0 until rows) {
            sink.append("<w:tr><w:trPr><w:trHeight w:val=\"${tw(t.ys[r + 1] - t.ys[r])}\" w:hRule=\"atLeast\"/><w:cantSplit/></w:trPr>")
            var c = 0
            while (c < cols) {
                val sp = spans[owner[r][c]]
                val cs = sp[3]
                val cw = t.xs[c + cs] - t.xs[c]
                val startRow = sp[0] == r
                sink.append("<w:tc><w:tcPr><w:tcW w:w=\"${tw(cw)}\" w:type=\"dxa\"/>")
                if (cs > 1) sink.append("<w:gridSpan w:val=\"$cs\"/>")
                if (sp[2] > 1) sink.append(if (startRow) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                // garis per sisi
                val rb = sp[0] + sp[2]
                sink.append("<w:tcBorders>")
                sink.append(border("top", t.hRule(r, c)))
                sink.append(border("left", t.vRule(c, r)))
                sink.append(border("bottom", if (r + 1 == rb) t.hRule(r + 1, c) else null))
                sink.append(border("right", t.vRule(c + cs, r)))
                sink.append("</w:tcBorders>")
                t.shade[t.key(sp[0], sp[1])]?.let { sink.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${String.format("%06X", it and 0xFFFFFF)}\"/>") }
                val gl = ArrayList<PdfLayout.Glyph>()
                if (startRow) for (rr in sp[0] until sp[0] + sp[2]) for (cc in sp[1] until sp[1] + sp[3]) t.cellText[t.key(rr, cc)]?.let { gl.addAll(it) }
                val ls = if (startRow) buildLines(gl) else emptyList()
                // isi di tengah / bawah sel (umum di sel gabungan) → rata tengah/bawah, bukan jarak-atas besar
                val spanTop = t.ys[sp[0]]; val spanBot = t.ys[sp[0] + sp[2]]
                val vAlign = if (ls.isEmpty()) "top" else {
                    val tt = ls.first().top; val tb = ls.last().bot
                    val gapTop = tt - spanTop; val gapBot = spanBot - tb
                    when { abs(gapTop - gapBot) <= max(2.5f, (spanBot - spanTop) * 0.12f) && gapTop > 3f -> "center"; gapBot < gapTop * 0.4f && gapTop > 6f -> "bottom"; else -> "top" }
                }
                sink.append("<w:vAlign w:val=\"$vAlign\"/></w:tcPr>")
                if (startRow) {
                    val cell = StringBuilder()
                    if (ls.isNotEmpty()) paragraphs(ls, t.xs[c] + 0.5f, t.xs[c + cs] - 0.5f, if (vAlign == "top") t.ys[r] else ls.first().top, StringBuilder(), cell)
                    if (cell.isEmpty()) cell.append(spacer(1f))
                    sink.append(cell)
                } else sink.append(spacer(1f))
                sink.append("</w:tc>")
                c += cs
            }
            sink.append("</w:tr>")
        }
        sink.append("</w:tbl>")
        return t.ys.last()
    }

    private fun border(side: String, r: Rule?): String =
        if (r == null) "<w:$side w:val=\"nil\"/>"
        else "<w:$side w:val=\"single\" w:sz=\"${(r.w * 8).roundToInt().coerceIn(2, 96)}\" w:space=\"0\" w:color=\"${String.format("%06X", r.color and 0xFFFFFF)}\"/>"

    private fun noBorders() = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"nil\"/>" }

    // ------------------------------------------------------------------ bentuk tertambat

    private fun shapeXml(s: PdfLayout.Shape, behind: Boolean): String {
        val b = s.bbox
        var w = b[2] - b[0]; var h = b[3] - b[1]
        if (w < 0.3f && h < 0.3f) return ""
        val lw = if (s.stroke != PdfLayout.NONE) s.lineW else 0f
        w = max(w, 0.5f); h = max(h, 0.5f)
        val geom: String
        if (s.isRect) geom = "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
        else if (isEllipse(s)) geom = "<a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom>"
        else if (roundRadius(s) > 0f) geom = "<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val ${(roundRadius(s) / min(w, h) * 100000).roundToInt().coerceIn(0, 50000)}\"/></a:avLst></a:prstGeom>"
        else {
            // jalur bebas (termasuk kotak bersudut bulat & poligon)
            val W = emu(w); val H = emu(h)
            val p = StringBuilder("<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/><a:pathLst><a:path w=\"$W\" h=\"$H\"${if (s.fill == PdfLayout.NONE) " fill=\"none\"" else ""}>")
            for ((k, sub) in s.subs.withIndex()) {
                var i = 0
                while (i + 1 < sub.size) {
                    val x = emu(sub[i] - b[0]); val y = emu(sub[i + 1] - b[1])
                    p.append(if (i == 0) "<a:moveTo><a:pt x=\"$x\" y=\"$y\"/></a:moveTo>" else "<a:lnTo><a:pt x=\"$x\" y=\"$y\"/></a:lnTo>")
                    i += 2
                }
                if (s.closed.getOrElse(k) { false }) p.append("<a:close/>")
            }
            p.append("</a:path></a:pathLst></a:custGeom>")
            geom = p.toString()
        }
        val fill = if (s.fill == PdfLayout.NONE) "<a:noFill/>" else "<a:solidFill><a:srgbClr val=\"${String.format("%06X", s.fill and 0xFFFFFF)}\"/></a:solidFill>"
        val ln = if (s.stroke == PdfLayout.NONE) "<a:ln><a:noFill/></a:ln>" else "<a:ln w=\"${emu(lw)}\"><a:solidFill><a:srgbClr val=\"${String.format("%06X", s.stroke and 0xFFFFFF)}\"/></a:solidFill></a:ln>"
        return anchor(b[0], b[1], w, h, "$geom$fill$ln", behind)
    }

    private fun isEllipse(s: PdfLayout.Shape): Boolean {
        if (s.subs.size != 1) return false
        val sub = s.subs[0]; val b = s.bbox
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2; val rx = (b[2] - b[0]) / 2; val ry = (b[3] - b[1]) / 2
        if (rx < 1 || ry < 1 || sub.size < 24) return false
        var i = 0
        while (i + 1 < sub.size) {
            val dx = (sub[i] - cx) / rx; val dy = (sub[i + 1] - cy) / ry
            if (abs(dx * dx + dy * dy - 1f) > 0.12f) return false
            i += 2
        }
        return true
    }

    /** Kotak bersudut bulat: jari-jari sudut (pt), 0 bila bukan. Semua titik jalur harus di garis luarnya. */
    private fun roundRadius(s: PdfLayout.Shape): Float {
        if (s.subs.size != 1 || s.isRect) return 0f
        val sub = s.subs[0]; val b = s.bbox
        if (sub.size < 16) return 0f
        var topMin = Float.MAX_VALUE
        var i = 0
        while (i + 1 < sub.size) { if (abs(sub[i + 1] - b[1]) <= 0.05f) topMin = min(topMin, sub[i]); i += 2 }
        val r = topMin - b[0]
        if (r <= 0.8f || r > min(b[2] - b[0], b[3] - b[1]) / 2f + 0.6f) return 0f
        i = 0
        while (i + 1 < sub.size) {
            val qx = sub[i].coerceIn(b[0] + r, max(b[0] + r, b[2] - r)); val qy = sub[i + 1].coerceIn(b[1] + r, max(b[1] + r, b[3] - r))
            val d = kotlin.math.sqrt((sub[i] - qx) * (sub[i] - qx) + (sub[i + 1] - qy) * (sub[i + 1] - qy))
            if (abs(d - r) > max(0.8f, r * 0.08f)) return 0f
            i += 2
        }
        return r
    }

    /** Teks miring/tegak (diputar) → kotak teks Word diputar dengan sudut yang sama, di posisi persis halaman. */
    private fun rotatedText(gs: List<PdfLayout.Glyph>, anchors: StringBuilder) {
        if (gs.isEmpty()) return
        val groups = ArrayList<MutableList<PdfLayout.Glyph>>()
        for (g in gs) {
            val th = Math.toRadians(g.angle.toDouble()); val nx = -kotlin.math.sin(th).toFloat(); val ny = kotlin.math.cos(th).toFloat()
            val grp = groups.firstOrNull { l ->
                val f = l[0]
                abs(f.angle - g.angle) < 2f && abs((g.x - f.x) * nx + (g.y - f.y) * ny) < f.size * 0.4f
            }
            if (grp != null) grp.add(g) else groups.add(arrayListOf(g))
        }
        for (l in groups) {
            val f = l[0]
            val th = Math.toRadians(f.angle.toDouble()); val dx = kotlin.math.cos(th).toFloat(); val dy = kotlin.math.sin(th).toFloat()
            val s = l.sortedBy { it.x * dx + it.y * dy }
            val a = s.first(); val z = s.last()
            val len = (z.x - a.x) * dx + (z.y - a.y) * dy + z.w
            val size = s.maxOf { it.size }
            val w = len + size * 0.6f; val h = size * 1.35f
            // pusat kotak: tengah garis dasar, digeser ke arah atas huruf
            val ux = -kotlin.math.sin(th).toFloat(); val uy = kotlin.math.cos(th).toFloat()     // "bawah" lokal
            val cx = a.x + dx * len / 2 - ux * size * 0.3f; val cy = a.y + dy * len / 2 - uy * size * 0.3f
            val sb = StringBuilder(); var lastP = Float.NaN
            for (g in s) {
                val p = g.x * dx + g.y * dy
                if (!lastP.isNaN() && p - lastP > g.size * 0.17f && !sb.endsWith(" ")) sb.append(' ')
                sb.append(g.text); lastP = p + g.w
            }
            val runXml = run(sb.toString().trim(), f)
            val wsp = "<wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm rot=\"${(f.angle * 60000).roundToInt()}\"><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${emu(w)}\" cy=\"${emu(h)}\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>" +
                "<wps:txbx><w:txbxContent><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr>$runXml</w:p></w:txbxContent></wps:txbx>" +
                "<wps:bodyPr rot=\"0\" wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"ctr\"><a:noAutofit/></wps:bodyPr>"
            anchors.append(anchor(cx - w / 2, cy - h / 2, w, h, "", false, wsp))
        }
    }

    private fun ruleXml(r: Rule): String {
        val (x, y, w, h) = if (r.h) listOf(r.a, r.pos - r.w / 2, r.b - r.a, r.w) else listOf(r.pos - r.w / 2, r.a, r.w, r.b - r.a)
        val fill = "<a:solidFill><a:srgbClr val=\"${String.format("%06X", r.color and 0xFFFFFF)}\"/></a:solidFill>"
        return anchor(x, y, max(w, 0.3f), max(h, 0.3f), "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>$fill<a:ln><a:noFill/></a:ln>", true)
    }

    private fun anchor(x: Float, y: Float, w: Float, h: Float, spPr: String, behind: Boolean, wsp: String? = null): String {
        val id = shapeId++
        return "<w:r><w:rPr><w:noProof/><w:sz w:val=\"2\"/></w:rPr><mc:AlternateContent><mc:Choice Requires=\"wps\"><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"${if (behind) 1 else 251658240 + id}\" behindDoc=\"${if (behind) 1 else 0}\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">" +
            "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>${emu(x)}</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>${emu(y)}</wp:posOffset></wp:positionV>" +
            "<wp:extent cx=\"${emu(w)}\" cy=\"${emu(h)}\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/><wp:docPr id=\"$id\" name=\"Bentuk $id\"/><wp:cNvGraphicFramePr/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp>${wsp ?: "<wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${emu(w)}\" cy=\"${emu(h)}\"/></a:xfrm>$spPr</wps:spPr><wps:bodyPr/>"}</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice></mc:AlternateContent></w:r>"
    }

    // ------------------------------------------------------------------ halaman pindaian (OCR)

    private fun scanPage(pl: Plan, sect: Sect) {
        val po = pl; val ml = sect.ml; val mr = sect.mr; val mt = sect.mt; val mb = sect.mb
        val blocks = pl.scan
        val textW = po.w - ml - mr
        if (blocks.isNullOrEmpty()) {
            pl.scanImg?.let { (jpg, pw, ph) ->
                val s = min(textW / pw, (po.h - mt - mb) / ph)
                val im = PdfLayout.Image(0f, 0f, pw * s, ph * s, jpg, null, pw, ph)
                body.append("<w:p>${pPr(0, 0, "center", 0, 0, 0, 0)}<w:r>${inlinePic(im, pw * s, ph * s)}</w:r></w:p>")
            } ?: body.append("<w:p/>")
        } else for (b in blocks) when (b) {
            is cloud.depup.office.Para -> body.append("<w:p>${pPr(0, 0, null, 0, 0, 120, 0)}${b.runs.joinToString("") { ocrRun(it) }}</w:p>")
            is Table -> {
                val colW = if (b.colW.isNotEmpty()) b.colW else List(b.cols) { 1 }
                val tot = colW.sum().toFloat().coerceAtLeast(1f)
                val tblW = textW * b.widthFrac.coerceIn(0.2f, 1f)
                body.append("<w:tbl><w:tblPr><w:tblW w:w=\"${tw(tblW)}\" w:type=\"dxa\"/><w:tblBorders>")
                for (s in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append(if (b.bordered) "<w:$s w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>" else "<w:$s w:val=\"nil\"/>")
                body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
                for (c in 0 until b.cols) body.append("<w:gridCol w:w=\"${tw(tblW * colW.getOrElse(c) { 1 } / tot)}\"/>")
                body.append("</w:tblGrid>")
                val at = HashMap<Long, TCell>(); val covered = HashSet<Long>()
                for (cell in b.cells) { at[cell.r * 1000L + cell.c] = cell; for (rr in cell.r until cell.r + cell.rs) for (cc in cell.c until cell.c + cell.cs) if (rr != cell.r || cc != cell.c) covered.add(rr * 1000L + cc) }
                for (r in 0 until b.rows) {
                    body.append("<w:tr>")
                    var c = 0
                    while (c < b.cols) {
                        val cell = at[r * 1000L + c]
                        val origin = b.cells.firstOrNull { r >= it.r && r < it.r + it.rs && c >= it.c && c < it.c + it.cs }
                        val cs = origin?.cs ?: 1
                        body.append("<w:tc><w:tcPr>")
                        if (cs > 1) body.append("<w:gridSpan w:val=\"$cs\"/>")
                        if (origin != null && origin.rs > 1) body.append(if (origin.r == r) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                        body.append("</w:tcPr>")
                        val paras = if (cell != null) cell.paras else emptyList()
                        if (paras.isEmpty()) body.append("<w:p/>") else for (pr in paras) body.append("<w:p>${pr.joinToString("") { ocrRun(it) }}</w:p>")
                        body.append("</w:tc>")
                        c += cs
                    }
                    body.append("</w:tr>")
                }
                body.append("</w:tbl><w:p/>")
            }
        }
    }

    private fun ocrRun(r: Run): String {
        val va = if (r.sup) "<w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>" else if (r.sub) "<w:rPr><w:vertAlign w:val=\"subscript\"/></w:rPr>" else ""
        return "<w:r>$va<w:t xml:space=\"preserve\">${esc(r.text)}</w:t></w:r>"
    }

    // ------------------------------------------------------------------ section & paket

    private class Sect(val w: Float, val h: Float, val ml: Float, val mr: Float, val mt: Float, val mb: Float)

    private var finalSect: String = ""

    private fun sectXml(s: Sect) = "<w:sectPr><w:pgSz w:w=\"${tw(s.w)}\" w:h=\"${tw(s.h)}\"${if (s.w > s.h) " w:orient=\"landscape\"" else ""}/>" +
        "<w:pgMar w:top=\"${tw(s.mt)}\" w:right=\"${tw(s.mr)}\" w:bottom=\"${tw(s.mb)}\" w:left=\"${tw(s.ml)}\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/><w:cols w:space=\"720\"/></w:sectPr>"

    private fun finish() {
        val ns = "xmlns:wpc=\"http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" " +
            "xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
            "xmlns:wp14=\"http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:w10=\"urn:schemas-microsoft-com:office:word\" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" xmlns:wpg=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\" " +
            "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" mc:Ignorable=\"w14 wp14\""
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:document $ns><w:body>$body${finalSect.ifEmpty { sectXml(Sect(595f, 842f, 72f, 72f, 72f, 72f)) }}</w:body></w:document>"
        val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        rels.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        rels.append("<Relationship Id=\"rIdSettings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>")
        media.forEachIndexed { i, (n, _) -> rels.append("<Relationship Id=\"rIdImg${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$n\"/>") }
        rels.append("</Relationships>")
        val ct = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"
        val root = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Arial\" w:cs=\"Arial\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"id-ID\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"0\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style></w:styles>"
        val settings = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            (editPassword?.let { DocxProtect.protectionXml(it) } ?: "") + "<w:defaultTabStop w:val=\"720\"/>" +
            "<w:characterSpacingControl w:val=\"doNotCompress\"/><w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>"
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        val core = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:creator>Depup Office</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created></cp:coreProperties>"
        ZipOutputStream(FileOutputStream(out)).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("[Content_Types].xml", ct.toByteArray())
            put("_rels/.rels", root.toByteArray())
            put("docProps/core.xml", core.toByteArray())
            put("word/document.xml", doc.toByteArray(Charsets.UTF_8))
            put("word/styles.xml", styles.toByteArray())
            put("word/settings.xml", settings.toByteArray())
            put("word/_rels/document.xml.rels", rels.toString().toByteArray())
            for ((n, b) in media) put("word/media/$n", b)
        }
    }

    // ------------------------------------------------------------------ util

    private fun tw(pt: Float): Int = (pt * 20f).roundToInt()
    private fun emu(pt: Float): Long = (pt * 12700f).toLong().coerceAtLeast(1)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;"); ch == '"' -> sb.append("&quot;")
            ch.code < 0x20 && ch != '\t' -> {}
            ch.code in 0xFFFE..0xFFFF -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}
