package cloud.depup.office

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Penafsir isi halaman PDF (murni Kotlin): mengubah operator gambar PDF menjadi unsur halaman yang bisa disusun ulang
 * jadi dokumen Word — huruf (posisi, font, ukuran, warna, tebal/miring), foto (JPEG asli / PNG), dan bentuk vektor
 * (garis, kotak, lingkaran, poligon; dipakai juga untuk mengenali tabel). Koordinat keluaran: poin, pojok kiri-ATAS
 * halaman (sesudah /Rotate & kotak halaman).
 */
object PdfLayout {

    class FontInfo(val family: String, val bold: Boolean, val italic: Boolean, val mono: Boolean, val serif: Boolean)

    /** Satu huruf/glif. [y] = garis dasar; [size] = ukuran tampak (pt). [invisible] = mode render 3 (teks OCR tersembunyi). */
    class Glyph(val x: Float, val y: Float, val w: Float, val size: Float, val text: String, val font: FontInfo, val color: Int,
                val invisible: Boolean, val upright: Boolean, val angle: Float = 0f)

    /** Foto: [jpeg] (JPEG asli) atau [png]. [rect] = x0,y0,x1,y1. */
    class Image(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val jpeg: ByteArray?, val png: ByteArray?, val pxW: Int, val pxH: Int)

    /** Warna "tidak ada" (alfa 0; semua warna nyata beralfa FF). */
    const val NONE = 0

    /** Bentuk vektor: [subs] = sub-jalur (x,y berselang), [closed] per sub-jalur. Warna [NONE] = tidak diisi/digaris. */
    class Shape(val subs: List<FloatArray>, val closed: List<Boolean>, val fill: Int, val stroke: Int, val lineW: Float, val isRect: Boolean) {
        val bbox: FloatArray by lazy {
            var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
            for (s in subs) { var i = 0; while (i + 1 < s.size) { a = min(a, s[i]); c = max(c, s[i]); b = min(b, s[i + 1]); d = max(d, s[i + 1]); i += 2 } }
            floatArrayOf(a, b, c, d)
        }
    }

    class PageOut(val w: Float, val h: Float, val glyphs: List<Glyph>, val images: List<Image>, val shapes: List<Shape>)

    /** Gabungkan JPEG + kanal alfa (abu-abu 8-bit [w]x[h]) jadi PNG. Diisi lapisan Android (BitmapFactory). */
    @JvmStatic var jpegWithAlpha: ((ByteArray, ByteArray, Int, Int) -> ByteArray?)? = null
    /** Batas unsur per halaman (berkas rusak / jalur raksasa). */
    private const val MAX_OPS = 2_000_000

    fun page(f: PdfFile, p: PdfFile.Page): PageOut {
        val bw = p.box[2] - p.box[0]; val bh = p.box[3] - p.box[1]
        // PDF (kiri-bawah) → keluaran (kiri-atas), termasuk /Rotate
        val base = when (p.rotate) {
            90 -> M(0f, 1f, 1f, 0f, -p.box[1], -p.box[0])
            180 -> M(-1f, 0f, 0f, 1f, p.box[2], -p.box[1])
            270 -> M(0f, -1f, -1f, 0f, p.box[3], p.box[2])
            else -> M(1f, 0f, 0f, -1f, -p.box[0], p.box[3])
        }
        val (ow, oh) = if (p.rotate == 90 || p.rotate == 270) bh to bw else bw to bh
        val r = Run(f, ow, oh)
        r.exec(f.contents(p), p.res, base, 0)
        return PageOut(ow, oh, r.glyphs, r.images, r.shapes)
    }

    // ------------------------------------------------------------------ matriks

    class M(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
        /** this × o (terapkan this dulu, lalu o) */
        fun times(o: M) = M(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d, e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f)
        fun x(px: Float, py: Float) = px * a + py * c + e
        fun y(px: Float, py: Float) = px * b + py * d + f
        fun scaleY() = sqrt(c * c + d * d)
        fun scaleX() = sqrt(a * a + b * b)
        companion object { val I = M(1f, 0f, 0f, 1f, 0f, 0f) }
    }

    // ------------------------------------------------------------------ penafsir

    private class GS(
        var ctm: M, var fill: Int = 0xFF000000.toInt(), var stroke: Int = 0xFF000000.toInt(), var lw: Float = 1f,
        var fillCs: String = "DeviceGray", var strokeCs: String = "DeviceGray",
        var font: Font? = null, var fs: Float = 12f, var tc: Float = 0f, var tw: Float = 0f, var th: Float = 1f,
        var tl: Float = 0f, var rise: Float = 0f, var mode: Int = 0, var fillA: Float = 1f,
        /** Clip bukan-kotak terakhir (mis. sudut membulat dari peramban): sub-jalur + kotak batasnya. */
        var clip: List<FloatArray>? = null, var clipBox: FloatArray? = null
    ) { fun copy() = GS(ctm, fill, stroke, lw, fillCs, strokeCs, font, fs, tc, tw, th, tl, rise, mode, fillA, clip, clipBox) }

    private class Run(val f: PdfFile, val pw: Float, val ph: Float) {
        val glyphs = ArrayList<Glyph>()
        val images = ArrayList<Image>()
        val shapes = ArrayList<Shape>()
        val fonts = HashMap<Any, Font>()
        var ops = 0

        fun exec(content: ByteArray, res: PDict, ctm0: M, depth: Int) {
            if (depth > 12) return
            val stack = ArrayList<GS>()
            var gs = GS(ctm0)
            var tm = M.I; var tlm = M.I
            val args = ArrayList<Any?>()
            val subs = ArrayList<FloatArray>(); val closed = ArrayList<Boolean>()
            var cur = FloatArrayBuilder(); var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
            var isRect = true
            fun flushSub(close: Boolean) { if (cur.n >= 4) { subs.add(cur.toArray()); closed.add(close) }; cur = FloatArrayBuilder() }
            fun dev(x: Float, y: Float) { cur.add(gs.ctm.x(x, y)); cur.add(gs.ctm.y(x, y)) }
            var pendingClip = false
            fun paint(fill: Boolean, stroke: Boolean, close: Boolean) {
                if (close && cur.n >= 4) flushSub(true) else flushSub(close)
                if (pendingClip) {
                    pendingClip = false
                    // simpan clip bukan-kotak (satu sub-jalur melengkung)
                    if (subs.size == 1 && !isRect) {
                        val sb = subs[0]
                        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
                        var i = 0; while (i + 1 < sb.size) { a = min(a, sb[i]); c = max(c, sb[i]); b = min(b, sb[i + 1]); d = max(d, sb[i + 1]); i += 2 }
                        gs.clip = listOf(sb); gs.clipBox = floatArrayOf(a, b, c, d)
                    } else if (subs.size == 1 && isRect) { gs.clip = null; gs.clipBox = null }
                }
                // kotak berisi yang menutupi clip melengkung → bentuk clip itu (kotak bersudut bulat, kapsul, lingkaran)
                val cb = gs.clipBox
                if (fill && !stroke && isRect && subs.size == 1 && cb != null && gs.clip != null) {
                    val sb = subs[0]
                    var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
                    var i = 0; while (i + 1 < sb.size) { a = min(a, sb[i]); c = max(c, sb[i]); b = min(b, sb[i + 1]); d = max(d, sb[i + 1]); i += 2 }
                    if (a <= cb[0] + 1f && b <= cb[1] + 1f && c >= cb[2] - 1f && d >= cb[3] - 1f) {
                        shapes.add(Shape(gs.clip!!, listOf(true), withAlpha(gs.fill, gs.fillA), NONE, 0f, false))
                        subs.clear(); closed.clear(); isRect = true
                        return
                    }
                }
                if ((fill || stroke) && subs.isNotEmpty()) {
                    shapes.add(Shape(ArrayList(subs), ArrayList(closed), if (fill) withAlpha(gs.fill, gs.fillA) else NONE, if (stroke) gs.stroke else NONE,
                        gs.lw * max(gs.ctm.scaleX(), gs.ctm.scaleY()) .let { if (it <= 0f) 0.5f else it }, isRect && subs.size == 1))
                }
                subs.clear(); closed.clear(); isRect = true
            }
            val lx = PdfLexer(content)
            while (true) {
                if (++ops > MAX_OPS) return
                val o = try { lx.obj() } catch (_: Exception) { null } ?: break
                if (o !is PdfLexer.Op) { args.add(o); continue }
                val op = o.k
                fun n(i: Int) = PdfFile.num(args.getOrNull(i)).toFloat()
                try {
                    when (op) {
                        "q" -> stack.add(gs.copy())
                        "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
                        "cm" -> if (args.size >= 6) gs.ctm = M(n(0), n(1), n(2), n(3), n(4), n(5)).times(gs.ctm)
                        "w" -> gs.lw = n(0)
                        "gs" -> (args.getOrNull(0) as? PName)?.let { nm ->
                            val e = f.rdict(f.rdict(res["ExtGState"])?.get(nm.n))
                            (e?.get("ca") as? Number)?.let { gs.fillA = it.toFloat() }
                            (e?.get("LW") as? Number)?.let { gs.lw = it.toFloat() }
                        }
                        // warna
                        "g" -> { gs.fill = gray(n(0)); gs.fillCs = "DeviceGray" }
                        "G" -> { gs.stroke = gray(n(0)); gs.strokeCs = "DeviceGray" }
                        "rg" -> { gs.fill = rgb(n(0), n(1), n(2)); gs.fillCs = "DeviceRGB" }
                        "RG" -> { gs.stroke = rgb(n(0), n(1), n(2)); gs.strokeCs = "DeviceRGB" }
                        "k" -> { gs.fill = cmyk(n(0), n(1), n(2), n(3)); gs.fillCs = "DeviceCMYK" }
                        "K" -> { gs.stroke = cmyk(n(0), n(1), n(2), n(3)); gs.strokeCs = "DeviceCMYK" }
                        "cs" -> { gs.fillCs = csName(res, args.getOrNull(0)); gs.fill = 0xFF000000.toInt() }
                        "CS" -> { gs.strokeCs = csName(res, args.getOrNull(0)); gs.stroke = 0xFF000000.toInt() }
                        "sc", "scn" -> colorFrom(gs.fillCs, args, res)?.let { gs.fill = it }
                        "SC", "SCN" -> colorFrom(gs.strokeCs, args, res)?.let { gs.stroke = it }
                        // jalur
                        "m" -> { flushSub(false); cx = n(0); cy = n(1); sx = cx; sy = cy; dev(cx, cy) }
                        "l" -> { if (cur.n == 0) dev(cx, cy); cx = n(0); cy = n(1); dev(cx, cy) }
                        "c", "v", "y" -> {
                            isRect = false
                            if (cur.n == 0) dev(cx, cy)
                            val (x1, y1, x2, y2, x3, y3) = when (op) {
                                "c" -> F6(n(0), n(1), n(2), n(3), n(4), n(5))
                                "v" -> F6(cx, cy, n(0), n(1), n(2), n(3))
                                else -> F6(n(0), n(1), n(2), n(3), n(2), n(3))
                            }
                            for (k in 1..8) {
                                val t = k / 8f; val u = 1 - t
                                val px = u * u * u * cx + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
                                val py = u * u * u * cy + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
                                dev(px, py)
                            }
                            cx = x3; cy = y3
                        }
                        "h" -> { if (cur.n >= 2) { dev(sx, sy); flushSub(true) }; cx = sx; cy = sy }
                        "re" -> {
                            flushSub(false)
                            val x = n(0); val y = n(1); val w = n(2); val h = n(3)
                            dev(x, y); dev(x + w, y); dev(x + w, y + h); dev(x, y + h); dev(x, y)
                            flushSub(true); cx = x; cy = y
                            if (subs.size > 1) isRect = false
                        }
                        "S" -> paint(false, true, false)
                        "s" -> paint(false, true, true)
                        "f", "F", "f*" -> paint(true, false, true)
                        "B", "B*" -> paint(true, true, true)
                        "b", "b*" -> paint(true, true, true)
                        "n" -> paint(false, false, false)
                        "W", "W*" -> pendingClip = true
                        // teks
                        "BT" -> { tm = M.I; tlm = M.I }
                        "ET" -> {}
                        "Tf" -> { gs.font = font(res, args.getOrNull(0)); gs.fs = n(1) }
                        "Tc" -> gs.tc = n(0)
                        "Tw" -> gs.tw = n(0)
                        "Tz" -> gs.th = n(0) / 100f
                        "TL" -> gs.tl = n(0)
                        "Ts" -> gs.rise = n(0)
                        "Tr" -> gs.mode = n(0).toInt()
                        "Td" -> { tlm = M(1f, 0f, 0f, 1f, n(0), n(1)).times(tlm); tm = tlm }
                        "TD" -> { gs.tl = -n(1); tlm = M(1f, 0f, 0f, 1f, n(0), n(1)).times(tlm); tm = tlm }
                        "Tm" -> { tlm = M(n(0), n(1), n(2), n(3), n(4), n(5)); tm = tlm }
                        "T*" -> { tlm = M(1f, 0f, 0f, 1f, 0f, -gs.tl).times(tlm); tm = tlm }
                        "Tj" -> (args.getOrNull(0) as? PStr)?.let { tm = show(it.b, gs, tm) }
                        "'" -> { tlm = M(1f, 0f, 0f, 1f, 0f, -gs.tl).times(tlm); tm = tlm; (args.getOrNull(0) as? PStr)?.let { tm = show(it.b, gs, tm) } }
                        "\"" -> { gs.tw = n(0); gs.tc = n(1); tlm = M(1f, 0f, 0f, 1f, 0f, -gs.tl).times(tlm); tm = tlm; (args.getOrNull(2) as? PStr)?.let { tm = show(it.b, gs, tm) } }
                        "TJ" -> (args.getOrNull(0) as? List<*>)?.forEach { e ->
                            when (e) {
                                is PStr -> tm = show(e.b, gs, tm)
                                is Number -> tm = M(1f, 0f, 0f, 1f, -e.toFloat() / 1000f * gs.fs * gs.th, 0f).times(tm)
                            }
                        }
                        // gambar & form
                        "Do" -> (args.getOrNull(0) as? PName)?.let { nm -> xobject(res, nm.n, gs, depth) }
                        "BI" -> inlineImage(lx, gs, res)
                    }
                } catch (_: Exception) {}
                args.clear()
            }
        }

        private data class F6(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)

        private fun show(b: ByteArray, gs: GS, tm0: M): M {
            val font = gs.font ?: return tm0
            var tm = tm0
            val inv = gs.mode == 3 || gs.mode == 7
            for (cg in font.codes(b)) {
                val w0 = font.width(cg.code) / 1000f
                val trm = M(gs.fs * gs.th, 0f, 0f, gs.fs, 0f, gs.rise).times(tm).times(gs.ctm)
                val x = trm.x(0f, 0f); val y = trm.y(0f, 0f)
                val size = trm.scaleY()
                val adv = (w0 * gs.fs + gs.tc + (if (cg.single && cg.code == 32) gs.tw else 0f)) * gs.th
                val x2 = M(1f, 0f, 0f, 1f, w0 * gs.fs * gs.th, 0f).times(tm).times(gs.ctm).x(0f, 0f)
                val upright = abs(trm.b) < 0.01f * size && trm.a > 0
                if (cg.text.isNotEmpty() && size > 0.5f && x > -50 && x < pw + 50 && y > -50 && y < ph + 50)
                    glyphs.add(Glyph(x, y, if (upright) abs(x2 - x) else kotlin.math.hypot(x2 - x, M(1f, 0f, 0f, 1f, w0 * gs.fs * gs.th, 0f).times(tm).times(gs.ctm).y(0f, 0f) - y),
                        size, cg.text, font.info, gs.fill, inv, upright, Math.toDegrees(kotlin.math.atan2(trm.b.toDouble(), trm.a.toDouble())).toFloat()))
                tm = M(1f, 0f, 0f, 1f, adv, 0f).times(tm)
            }
            return tm
        }

        private fun font(res: PDict, nm: Any?): Font? {
            val name = (nm as? PName)?.n ?: return null
            val ref = f.rdict(res["Font"])?.get(name) ?: return null
            return fonts.getOrPut(ref) { Font.load(f, f.rdict(ref) ?: PDict()) }
        }

        private fun csName(res: PDict, nm: Any?): String {
            val n = (nm as? PName)?.n ?: return "DeviceGray"
            if (n.startsWith("Device") || n == "Pattern") return n
            val cs = f.res(f.rdict(res["ColorSpace"])?.get(n))
            return when (cs) {
                is PName -> cs.n
                is List<*> -> when ((cs.firstOrNull() as? PName)?.n) {
                    "ICCBased" -> when (PdfFile.int(f.rdict(cs.getOrNull(1))?.get("N"), 3)) { 1 -> "DeviceGray"; 4 -> "DeviceCMYK"; else -> "DeviceRGB" }
                    "CalRGB", "Lab" -> "DeviceRGB"; "CalGray" -> "DeviceGray"
                    "Separation", "DeviceN" -> "Separation"
                    "Indexed" -> "Indexed:$n"
                    else -> "DeviceRGB"
                }
                else -> "DeviceGray"
            }
        }

        private fun colorFrom(cs: String, a: List<Any?>, res: PDict): Int? {
            val v = a.filterIsInstance<Number>().map { it.toFloat() }
            return when {
                cs == "DeviceGray" && v.isNotEmpty() -> gray(v[0])
                cs == "DeviceRGB" && v.size >= 3 -> rgb(v[0], v[1], v[2])
                cs == "DeviceCMYK" && v.size >= 4 -> cmyk(v[0], v[1], v[2], v[3])
                cs == "Separation" && v.isNotEmpty() -> gray(1f - v[0])           // tinta spot: makin besar makin gelap
                v.size >= 3 -> rgb(v[0], v[1], v[2])
                v.size == 1 -> gray(v[0])
                else -> null                                                      // pola (Pattern): biarkan warna lama
            }
        }

        private fun xobject(res: PDict, name: String, gs: GS, depth: Int) {
            val st = f.res(f.rdict(res["XObject"])?.get(name)) as? PStream ?: return
            when (st.dict.name("Subtype")) {
                "Form" -> {
                    val mt = (f.res(st.dict["Matrix"]) as? List<*>)?.map { PdfFile.num(f.res(it)).toFloat() }
                    val m = if (mt != null && mt.size == 6) M(mt[0], mt[1], mt[2], mt[3], mt[4], mt[5]) else M.I
                    val sub = gs.copy(); sub.ctm = m.times(gs.ctm)
                    val r2 = f.rdict(st.dict["Resources"]) ?: res
                    val body = try { f.decode(st, null) } catch (_: Exception) { return }
                    // penafsir baru dengan keadaan grafis yang diwarisi
                    val saveFill = sub.fill
                    execForm(body, r2, sub, depth)
                    sub.fill = saveFill
                }
                "Image" -> image(st, gs)
            }
        }

        private fun execForm(body: ByteArray, res: PDict, gs: GS, depth: Int) {
            // warisi warna isi (penting untuk ikon/stensil) — jalankan dengan ctm form
            val r = this
            val before = ops
            r.exec(prefix(gs) + body, res, gs.ctm, depth + 1)
            if (ops - before > MAX_OPS) return
        }

        /** Awali isi form dengan warna isi saat ini (exec memulai keadaan grafis baru). */
        private fun prefix(gs: GS): ByteArray {
            val c = gs.fill
            return String.format(java.util.Locale.US, "%.4f %.4f %.4f rg ", ((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f).toByteArray()
        }

        private fun place(gs: GS): FloatArray {
            val m = gs.ctm
            val xs = floatArrayOf(m.x(0f, 0f), m.x(1f, 0f), m.x(0f, 1f), m.x(1f, 1f))
            val ys = floatArrayOf(m.y(0f, 0f), m.y(1f, 0f), m.y(0f, 1f), m.y(1f, 1f))
            return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }

        private fun image(st: PStream, gs: GS) {
            val r = place(gs)
            if (r[2] - r[0] < 1f || r[3] - r[1] < 1f) return
            val img = ImgDec.decode(f, st, gs.fill) ?: return
            images.add(Image(r[0], r[1], r[2], r[3], img.jpeg, img.png, img.w, img.h))
        }

        private fun inlineImage(lx: PdfLexer, gs: GS, res: PDict) {
            val d = PDict()
            while (true) {
                val k = lx.obj() ?: return
                if (k is PdfLexer.Op && k.k == "ID") break
                val v = lx.obj() ?: return
                if (k is PName) d[expand(k.n)] = if (v is PName) PName(expand(v.n)) else if (v is List<*>) v.map { if (it is PName) PName(expand(it.n)) else it } else v
            }
            val start = lx.p + 1
            // cari "EI" yang diapit spasi
            var e = start
            while (e + 2 < lx.end) {
                if (lx.d[e] == 'E'.code.toByte() && lx.d[e + 1] == 'I'.code.toByte() && PdfLexer.ws(lx.at(e - 1)) && (e + 2 >= lx.end || PdfLexer.ws(lx.at(e + 2)))) break
                e++
            }
            val raw = lx.d.copyOfRange(start, maxOf(start, e - 1))
            lx.p = minOf(lx.end, e + 2)
            if (d["ColorSpace"] is PName) (d["ColorSpace"] as PName).n.let { cs -> if (!cs.startsWith("Device")) f.rdict(res["ColorSpace"])?.get(cs)?.let { d["ColorSpace"] = it } }
            image(PStream(d, raw), gs)
        }

        private fun expand(k: String) = when (k) {
            "W" -> "Width"; "H" -> "Height"; "BPC" -> "BitsPerComponent"; "CS" -> "ColorSpace"; "F" -> "Filter"; "DP" -> "DecodeParms"
            "IM" -> "ImageMask"; "D" -> "Decode"; "I" -> "Interpolate"; "G" -> "DeviceGray"; "RGB" -> "DeviceRGB"; "CMYK" -> "DeviceCMYK"
            "AHx" -> "ASCIIHexDecode"; "A85" -> "ASCII85Decode"; "Fl" -> "FlateDecode"; "DCT" -> "DCTDecode"; "RL" -> "RunLengthDecode"; "LZW" -> "LZWDecode"
            else -> k
        }
    }

    class FloatArrayBuilder { var a = FloatArray(16); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }

    fun gray(v: Float): Int { val c = (v.coerceIn(0f, 1f) * 255).toInt(); return (0xFF shl 24) or (c shl 16) or (c shl 8) or c }
    fun rgb(r: Float, g: Float, b: Float) = (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
    fun cmyk(c: Float, m: Float, y: Float, k: Float) = rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))
    private fun withAlpha(c: Int, a: Float): Int {
        if (a >= 0.99f) return c
        // campur dengan putih (latar kertas) — Word tidak selalu menghormati transparansi bentuk
        val r = ((c shr 16) and 255) * a + 255 * (1 - a); val g = ((c shr 8) and 255) * a + 255 * (1 - a); val b = (c and 255) * a + 255 * (1 - a)
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    // ------------------------------------------------------------------ font

    class CodeGlyph(val code: Int, val text: String, val single: Boolean)

    class Font private constructor(val info: FontInfo, private val widths: Map<Int, Float>, private val dw: Float,
                                   private val toUni: Map<Int, String>, private val enc: Array<String?>?, private val cid: Boolean,
                                   private val ranges: List<IntArray>, private val std: FloatArray?, private val stdMono: Boolean,
                                   private val fm: Float) {
        fun width(code: Int): Float {
            widths[code]?.let { return it * fm }
            if (std != null && !cid) return if (stdMono) 600f else (if (code in 32..126) std[code - 32] else 500f)
            return dw * fm
        }

        fun codes(b: ByteArray): List<CodeGlyph> {
            val out = ArrayList<CodeGlyph>(b.size)
            if (!cid) {
                for (x in b) { val c = x.toInt() and 0xFF; out.add(CodeGlyph(c, uni(c, 1), true)) }
                return out
            }
            var i = 0
            while (i < b.size) {
                var len = 2
                if (ranges.isNotEmpty()) {
                    len = 0
                    for (n in 1..4) {
                        if (i + n > b.size) break
                        var v = 0; for (k in 0 until n) v = (v shl 8) or (b[i + k].toInt() and 0xFF)
                        if (ranges.any { it[0] == n && v >= it[1] && v <= it[2] }) { len = n; break }
                    }
                    if (len == 0) len = minOf(2, b.size - i).coerceAtLeast(1)
                }
                len = minOf(len, b.size - i)
                var v = 0; for (k in 0 until len) v = (v shl 8) or (b[i + k].toInt() and 0xFF)
                out.add(CodeGlyph(v, uni(v, len), len == 1))
                i += len
            }
            return out
        }

        private fun uni(c: Int, len: Int): String {
            toUni[c]?.let { return it }
            if (cid) return if (toUni.isEmpty() && c in 32..0xFFFF && len == 2) String(Character.toChars(c)).takeIf { Character.isLetterOrDigit(c) || c in 32..126 } ?: "" else ""
            enc?.getOrNull(c)?.let { return it }
            return if (c in 32..126) c.toChar().toString() else if (c in 160..255) c.toChar().toString() else ""
        }

        companion object {
            private val HELV = intArrayOf(278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584).map { it.toFloat() }.toFloatArray()
            private val TIMES = intArrayOf(250,333,408,500,500,833,778,180,333,333,500,564,250,333,250,278,500,500,500,500,500,500,500,500,500,500,278,278,564,564,564,444,921,722,667,667,722,611,556,722,722,333,389,722,611,889,722,722,556,722,667,556,611,722,722,944,722,722,611,333,278,333,469,500,333,444,500,444,500,444,333,500,500,278,278,500,278,778,500,500,500,500,333,389,278,500,500,722,500,500,444,480,200,480,541).map { it.toFloat() }.toFloatArray()

            fun load(f: PdfFile, d: PDict): Font {
                val sub = d.name("Subtype") ?: "Type1"
                val cid = sub == "Type0"
                val desc = if (cid) f.rdict((f.res(d["DescendantFonts"]) as? List<*>)?.firstOrNull()) else null
                val baseName = (d.name("BaseFont") ?: desc?.name("BaseFont") ?: "Arial")
                val fd = f.rdict((desc ?: d)["FontDescriptor"])
                val info = fontInfo(baseName, fd)
                val widths = HashMap<Int, Float>()
                var dw = 1000f
                var fm = 1f
                if (sub == "Type3") (f.res(d["FontMatrix"]) as? List<*>)?.let { m -> fm = PdfFile.num(f.res(m.getOrNull(0)), 0.001).toFloat() * 1000f }
                if (cid && desc != null) {
                    dw = PdfFile.num(f.res(desc["DW"]), 1000.0).toFloat()
                    val w = f.res(desc["W"]) as? List<*>
                    if (w != null) {
                        var i = 0
                        while (i < w.size) {
                            val c0 = PdfFile.int(f.res(w[i]))
                            val nx = f.res(w.getOrNull(i + 1))
                            if (nx is List<*>) { nx.forEachIndexed { k, v -> widths[c0 + k] = PdfFile.num(f.res(v)).toFloat() }; i += 2 }
                            else { val c1 = PdfFile.int(nx); val v = PdfFile.num(f.res(w.getOrNull(i + 2))).toFloat(); if (c1 - c0 < 70000) for (c in c0..c1) widths[c] = v; i += 3 }
                        }
                    }
                } else {
                    val fc = PdfFile.int(f.res(d["FirstChar"]))
                    (f.res(d["Widths"]) as? List<*>)?.forEachIndexed { k, v -> widths[fc + k] = PdfFile.num(f.res(v)).toFloat() }
                    dw = PdfFile.num(f.res(fd?.get("MissingWidth")), 500.0).toFloat().let { if (it <= 0f) 500f else it }
                }
                // ToUnicode
                val toUni = HashMap<Int, String>()
                val ranges = ArrayList<IntArray>()
                (f.res(d["ToUnicode"]) as? PStream)?.let { s -> try { CMap.parse(f.decode(s, null), toUni, ranges) } catch (_: Exception) {} }
                if (cid) (f.res(d["Encoding"]) as? PStream)?.let { s -> try { val dummy = HashMap<Int, String>(); val rr = ArrayList<IntArray>(); CMap.parse(f.decode(s, null), dummy, rr); if (rr.isNotEmpty()) { ranges.clear(); ranges.addAll(rr) } } catch (_: Exception) {} }
                if (cid && (d.name("Encoding")?.startsWith("Identity") != false)) { ranges.clear(); ranges.add(intArrayOf(2, 0, 0xFFFF)) }
                // pengodean font sederhana
                var enc: Array<String?>? = null
                if (!cid) {
                    val e = f.res(d["Encoding"])
                    val baseEnc = (e as? PName)?.n ?: (e as? PDict)?.name("BaseEncoding")
                    enc = Encodings.base(baseEnc, info.family == "Symbol" || baseName.contains("Symbol") || baseName.contains("Dingbat"))
                    ((e as? PDict)?.get("Differences")?.let { f.res(it) } as? List<*>)?.let { diff ->
                        var code = 0
                        for (x in diff) { val v = f.res(x); if (v is Number) code = v.toInt() else if (v is PName) { if (code in 0..255) enc[code] = Encodings.glyph(v.n); code++ } }
                    }
                }
                val stdKey = baseName.substringAfter('+').lowercase()
                val std = if (!cid && widths.isEmpty()) (if (stdKey.startsWith("times") || stdKey.contains("serif") && !stdKey.contains("sans")) TIMES else HELV) else null
                return Font(info, widths, dw, toUni, enc, cid, ranges, std, std != null && stdKey.startsWith("courier"), fm)
            }

            fun fontInfo(base0: String, fd: PDict?): FontInfo {
                val base = base0.substringAfter('+')
                val lower = base.lowercase()
                val flags = PdfFile.int(fd?.get("Flags"))
                val weight = PdfFile.int(fd?.get("FontWeight"))
                val bold = Regex("bold|black|heavy|semibold|demi|,bd|-bd").containsMatchIn(lower) || weight >= 600 || (flags and (1 shl 18)) != 0
                val italic = Regex("italic|oblique|,it|-it\\b|kursiv").containsMatchIn(lower) || (flags and (1 shl 6)) != 0 || PdfFile.num(fd?.get("ItalicAngle")) < -1
                val mono = lower.contains("courier") || lower.contains("mono") || (flags and 1) != 0
                val known = listOf("timesnewroman" to "Times New Roman", "timesroman" to "Times New Roman", "times" to "Times New Roman",
                    "couriernew" to "Courier New", "courier" to "Courier New", "arialnarrow" to "Arial Narrow", "arialblack" to "Arial Black",
                    "arial" to "Arial", "helvetica" to "Arial", "bookmanoldstyle" to "Bookman Old Style", "centurygothic" to "Century Gothic",
                    "comicsansms" to "Comic Sans MS", "trebuchetms" to "Trebuchet MS", "palatinolinotype" to "Palatino Linotype", "segoeui" to "Segoe UI")
                val flat = base.lowercase().replace(Regex("[^a-z0-9]"), "")
                known.firstOrNull { flat.startsWith(it.first) }?.let { k ->
                    val serifK = k.second.startsWith("Times") || k.second.startsWith("Bookman") || k.second.startsWith("Palatino")
                    return FontInfo(k.second, bold, italic, mono || k.second.startsWith("Courier"), serifK)
                }
                var fam = base.split(',', '-').first().replace(Regex("(PSMT|PS|MT|Std|LT|Pro)$"), "")
                fam = fam.replace(Regex("(Bold|Italic|Oblique|Regular|Roman|Light|Medium|Semibold|Black|Heavy|Book)+$"), "")
                    .replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
                fam = when (fam.lowercase().replace(" ", "")) {
                    "helvetica", "arial", "arialmt", "helveticaneue", "liberationsans", "nimbussans", "nimbussansl" -> "Arial"
                    "times", "timesnewroman", "timesroman", "liberationserif", "nimbusroman", "nimbusromno9l" -> "Times New Roman"
                    "courier", "couriernew", "liberationmono", "nimbusmono" -> "Courier New"
                    "symbol" -> "Symbol"; "zapfdingbats" -> "Wingdings"
                    "" -> "Arial"
                    else -> fam
                }
                val serif = (flags and 2) != 0 || fam.contains("Times") || fam.contains("Serif") && !fam.contains("Sans") || fam.contains("Georgia") || fam.contains("Cambria") || fam.contains("Garamond") || fam.contains("Book")
                return FontInfo(fam, bold, italic, mono, serif)
            }
        }
    }

    /** CMap ToUnicode / pengodean CID: bfchar, bfrange, cidchar, codespacerange. */
    object CMap {
        fun parse(b: ByteArray, map: MutableMap<Int, String>, ranges: MutableList<IntArray>) {
            val lx = PdfLexer(b)
            val stack = ArrayList<Any?>()
            fun code(s: PStr) = s.b.fold(0) { a, x -> (a shl 8) or (x.toInt() and 0xFF) }
            fun str(s: PStr): String = if (s.b.size >= 2 && s.b.size % 2 == 0) String(s.b, Charsets.UTF_16BE) else String(s.b, Charsets.ISO_8859_1)
            while (true) {
                val o = lx.obj(false) ?: break
                if (o !is PdfLexer.Op) { stack.add(o); if (stack.size > 4096) stack.clear(); continue }
                when (o.k) {
                    "begincodespacerange", "beginbfchar", "beginbfrange", "begincidchar", "begincidrange" -> stack.clear()
                    "endcodespacerange" -> { var i = 0; while (i + 1 < stack.size) { val a = stack[i] as? PStr; val z = stack[i + 1] as? PStr; if (a != null && z != null) ranges.add(intArrayOf(a.b.size, code(a), code(z))); i += 2 }; stack.clear() }
                    "endbfchar" -> { var i = 0; while (i + 1 < stack.size) { val a = stack[i] as? PStr; val v = stack[i + 1]; if (a != null) map[code(a)] = when (v) { is PStr -> str(v); is PName -> Encodings.glyph(v.n) ?: ""; else -> "" }; i += 2 }; stack.clear() }
                    "endbfrange" -> {
                        var i = 0
                        while (i + 2 < stack.size) {
                            val a = stack[i] as? PStr; val z = stack[i + 1] as? PStr; val v = stack[i + 2]
                            if (a != null && z != null) {
                                val c0 = code(a); val c1 = code(z)
                                if (c1 - c0 in 0..65535) when (v) {
                                    is PStr -> { val base = str(v); if (base.isNotEmpty()) { val last = base.last(); for (c in c0..c1) map[c] = base.dropLast(1) + (last + (c - c0)) } }
                                    is List<*> -> v.forEachIndexed { k, s -> if (s is PStr) map[c0 + k] = str(s) }
                                }
                            }
                            i += 3
                        }
                        stack.clear()
                    }
                    else -> {}
                }
            }
        }
    }

    // ------------------------------------------------------------------ gambar → JPEG/PNG

    class DecImg(val jpeg: ByteArray?, val png: ByteArray?, val w: Int, val h: Int)

    object ImgDec {
        fun decode(f: PdfFile, st: PStream, fill: Int): DecImg? {
            val d = st.dict
            val w = PdfFile.int(f.res(d["Width"])); val h = PdfFile.int(f.res(d["Height"]))
            if (w <= 0 || h <= 0 || w.toLong() * h > 40_000_000L) return null
            val fl = f.filters(st)
            val smask = f.res(d["SMask"]) as? PStream
            if (fl.contains("JPXDecode")) return null                                // JPEG 2000: Word & Android tidak bisa
            if (fl.contains("DCTDecode")) {
                val jpg = try { f.decode(st, setOf("DCTDecode")) } catch (_: Exception) { return null }
                if (smask != null) {
                    val a = alpha(f, smask, w, h)
                    if (a != null) jpegWithAlpha?.invoke(jpg, a, w, h)?.let { return DecImg(null, it, w, h) }
                }
                return DecImg(jpg, null, w, h)
            }
            if (fl.contains("CCITTFaxDecode") || fl.contains("JBIG2Decode")) return null
            val raw = try { f.decode(st, null) } catch (_: Exception) { return null }
            val mask = d["ImageMask"] == true
            val bpc = if (mask) 1 else PdfFile.int(f.res(d["BitsPerComponent"]), 8)
            val decode = (f.res(d["Decode"]) as? List<*>)?.map { PdfFile.num(f.res(it)).toFloat() }
            val rgba = IntArray(w * h)
            if (mask) {
                val inv = decode != null && decode.size >= 2 && decode[0] > decode[1]
                val row = (w + 7) / 8
                for (y in 0 until h) for (x in 0 until w) {
                    val bit = (raw.getOrElse(y * row + x / 8) { 0 }.toInt() shr (7 - x % 8)) and 1
                    val paint = if (inv) bit == 1 else bit == 0
                    rgba[y * w + x] = if (paint) fill or (0xFF shl 24) else 0
                }
                return DecImg(null, png(rgba, w, h, true), w, h)
            }
            val cs = colorSpace(f, f.res(d["ColorSpace"]))
            val nc = cs.n
            val rowBits = w * nc * bpc
            val row = (rowBits + 7) / 8
            fun sample(y: Int, i: Int): Int {          // komponen ke-i (berurutan) di baris y, 0..(2^bpc-1)
                val bit = i * bpc
                val off = y * row + bit / 8
                return when (bpc) {
                    8 -> raw.getOrElse(off) { 0 }.toInt() and 0xFF
                    16 -> raw.getOrElse(off) { 0 }.toInt() and 0xFF
                    else -> (raw.getOrElse(off) { 0 }.toInt() shr (8 - bpc - bit % 8)) and ((1 shl bpc) - 1)
                }
            }
            val maxV = if (bpc == 16) 255f else ((1 shl bpc) - 1).toFloat()
            for (y in 0 until h) for (x in 0 until w) {
                val comps = FloatArray(nc) { k ->
                    var v = sample(y, x * nc + k) / maxV
                    if (decode != null && decode.size >= 2 * (k + 1) && cs.lookup == null) v = decode[2 * k] + v * (decode[2 * k + 1] - decode[2 * k])
                    v
                }
                rgba[y * w + x] = cs.toRgb(comps, sample(y, x * nc), bpc)
            }
            var hasA = false
            if (smask != null) alpha(f, smask, w, h)?.let { a -> hasA = true; for (i in rgba.indices) rgba[i] = (rgba[i] and 0xFFFFFF) or ((a[i].toInt() and 0xFF) shl 24) }
            return DecImg(null, png(rgba, w, h, hasA), w, h)
        }

        /** Kanal alfa dari SMask, diskalakan ke [w]x[h]. */
        private fun alpha(f: PdfFile, sm: PStream, w: Int, h: Int): ByteArray? {
            val mw = PdfFile.int(f.res(sm.dict["Width"])); val mh = PdfFile.int(f.res(sm.dict["Height"]))
            if (mw <= 0 || mh <= 0) return null
            if (f.filters(sm).any { it == "DCTDecode" || it == "JPXDecode" }) return null
            val b = try { f.decode(sm, null) } catch (_: Exception) { return null }
            val bpc = PdfFile.int(f.res(sm.dict["BitsPerComponent"]), 8)
            val row = (mw * bpc + 7) / 8
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = x * mw / w; val sy = y * mh / h
                val v = if (bpc == 8) b.getOrElse(sy * row + sx) { -1 }.toInt() and 0xFF
                    else ((b.getOrElse(sy * row + sx * bpc / 8) { 0 }.toInt() shr (8 - bpc - (sx * bpc) % 8)) and ((1 shl bpc) - 1)) * 255 / ((1 shl bpc) - 1)
                out[y * w + x] = v.toByte()
            }
            return out
        }

        class CS(val n: Int, val kind: String, val lookup: ByteArray? = null, val baseN: Int = 3) {
            fun toRgb(c: FloatArray, idx: Int, bpc: Int): Int = when {
                lookup != null -> {
                    val o = idx * baseN
                    if (baseN == 4) cmyk((lookup.getOrElse(o) { 0 }.toInt() and 0xFF) / 255f, (lookup.getOrElse(o + 1) { 0 }.toInt() and 0xFF) / 255f, (lookup.getOrElse(o + 2) { 0 }.toInt() and 0xFF) / 255f, (lookup.getOrElse(o + 3) { 0 }.toInt() and 0xFF) / 255f)
                    else if (baseN == 1) gray((lookup.getOrElse(o) { 0 }.toInt() and 0xFF) / 255f)
                    else rgb((lookup.getOrElse(o) { 0 }.toInt() and 0xFF) / 255f, (lookup.getOrElse(o + 1) { 0 }.toInt() and 0xFF) / 255f, (lookup.getOrElse(o + 2) { 0 }.toInt() and 0xFF) / 255f)
                }
                kind == "cmyk" -> cmyk(c[0], c[1], c[2], c[3])
                kind == "sep" -> gray(1f - c[0])
                n == 1 -> gray(c[0])
                else -> rgb(c[0], c.getOrElse(1) { c[0] }, c.getOrElse(2) { c[0] })
            }
        }

        fun colorSpace(f: PdfFile, cs: Any?): CS {
            when (cs) {
                is PName -> return when (cs.n) { "DeviceGray", "CalGray", "G" -> CS(1, "gray"); "DeviceCMYK", "CMYK" -> CS(4, "cmyk"); else -> CS(3, "rgb") }
                is List<*> -> {
                    val k = (f.res(cs.firstOrNull()) as? PName)?.n
                    when (k) {
                        "ICCBased" -> return when (PdfFile.int(f.rdict(cs.getOrNull(1))?.get("N"), 3)) { 1 -> CS(1, "gray"); 4 -> CS(4, "cmyk"); else -> CS(3, "rgb") }
                        "CalRGB", "Lab" -> return CS(3, "rgb")
                        "CalGray" -> return CS(1, "gray")
                        "Separation" -> return CS(1, "sep")
                        "DeviceN" -> return CS((f.res(cs.getOrNull(1)) as? List<*>)?.size ?: 1, "sep")
                        "Indexed", "I" -> {
                            val base = colorSpace(f, f.res(cs.getOrNull(1)))
                            val lk = when (val l = f.res(cs.getOrNull(3))) { is PStr -> l.b; is PStream -> try { f.decode(l, null) } catch (_: Exception) { ByteArray(0) }; else -> ByteArray(0) }
                            return CS(1, "idx", lk, base.n)
                        }
                    }
                }
            }
            return CS(3, "rgb")
        }

        /** Penyandi PNG kecil (RGB / RGBA 8-bit). */
        fun png(px: IntArray, w: Int, h: Int, alpha: Boolean): ByteArray {
            val ch = if (alpha) 4 else 3
            val raw = ByteArray((w * ch + 1) * h)
            var p = 0
            for (y in 0 until h) {
                raw[p++] = 0
                for (x in 0 until w) {
                    val c = px[y * w + x]
                    raw[p++] = (c shr 16).toByte(); raw[p++] = (c shr 8).toByte(); raw[p++] = c.toByte()
                    if (alpha) raw[p++] = (c ushr 24).toByte()
                }
            }
            val def = Deflater(6); def.setInput(raw); def.finish()
            val z = ByteArrayOutputStream(); val buf = ByteArray(65536)
            while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
            def.end()
            val o = ByteArrayOutputStream()
            o.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            fun chunk(t: String, d: ByteArray) {
                o.write(be(d.size)); val tb = t.toByteArray(Charsets.ISO_8859_1); o.write(tb); o.write(d)
                val crc = CRC32(); crc.update(tb); crc.update(d); o.write(be(crc.value.toInt()))
            }
            chunk("IHDR", be(w) + be(h) + byteArrayOf(8, if (alpha) 6 else 2, 0, 0, 0))
            chunk("IDAT", z.toByteArray())
            chunk("IEND", ByteArray(0))
            return o.toByteArray()
        }

        private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    // ------------------------------------------------------------------ pengodean & nama glif

    object Encodings {
        private val WIN_HI = mapOf(128 to "€", 130 to "‚", 131 to "ƒ", 132 to "„", 133 to "…", 134 to "†", 135 to "‡", 136 to "ˆ", 137 to "‰", 138 to "Š",
            139 to "‹", 140 to "Œ", 142 to "Ž", 145 to "‘", 146 to "’", 147 to "“", 148 to "”", 149 to "•", 150 to "–", 151 to "—", 152 to "˜", 153 to "™",
            154 to "š", 155 to "›", 156 to "œ", 158 to "ž", 159 to "Ÿ")

        fun base(name: String?, symbolic: Boolean): Array<String?> {
            val a = arrayOfNulls<String>(256)
            for (c in 32..126) a[c] = c.toChar().toString()
            for (c in 160..255) a[c] = c.toChar().toString()
            when (name) {
                "MacRomanEncoding" -> try {
                    val cs = charset("x-MacRoman")
                    for (c in 128..255) a[c] = String(byteArrayOf(c.toByte()), cs)
                } catch (_: Exception) {}
                "StandardEncoding", null -> if (!symbolic) { a[39] = "’"; a[96] = "‘"; WIN_HI.forEach { (k, v) -> a[k] = v } }
                else -> WIN_HI.forEach { (k, v) -> a[k] = v }
            }
            if (name == "WinAnsiEncoding") { a[39] = "'"; a[96] = "`" }
            return a
        }

        private val NAMES: Map<String, String> by lazy {
            val m = HashMap<String, String>()
            for (c in 'a'..'z') m[c.toString()] = c.toString()
            for (c in 'A'..'Z') m[c.toString()] = c.toString()
            val digits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
            digits.forEachIndexed { i, s -> m[s] = i.toString() }
            val pairs = "space: ,exclam:!,quotedbl:\",numbersign:#,dollar:$,percent:%,ampersand:&,quotesingle:',parenleft:(,parenright:),asterisk:*,plus:+,comma:\\,,hyphen:-,period:.,slash:/,colon::,semicolon:;,less:<,equal:=,greater:>,question:?,at:@,bracketleft:[,backslash:\\\\,bracketright:],asciicircum:^,underscore:_,grave:`,braceleft:{,bar:|,braceright:},asciitilde:~,quoteleft:‘,quoteright:’,quotedblleft:“,quotedblright:”,quotesinglbase:‚,quotedblbase:„,endash:–,emdash:—,bullet:•,ellipsis:…,dagger:†,daggerdbl:‡,perthousand:‰,trademark:™,copyright:©,registered:®,degree:°,plusminus:±,multiply:×,divide:÷,minus:−,section:§,paragraph:¶,periodcentered:·,cent:¢,sterling:£,yen:¥,Euro:€,euro:€,florin:ƒ,guillemotleft:«,guillemotright:»,guilsinglleft:‹,guilsinglright:›,exclamdown:¡,questiondown:¿,fi:fi,fl:fl,ff:ff,ffi:ffi,ffl:ffl,nbspace: ,uni00A0: ,dotlessi:ı,acute:´,dieresis:¨,macron:¯,cedilla:¸,circumflex:ˆ,tilde:˜,ring:˚,caron:ˇ,breve:˘,dotaccent:˙,ordfeminine:ª,ordmasculine:º,onehalf:½,onequarter:¼,threequarters:¾,mu:µ,logicalnot:¬,brokenbar:¦,currency:¤,germandbls:ß,ae:æ,AE:Æ,oe:œ,OE:Œ,oslash:ø,Oslash:Ø,eth:ð,Eth:Ð,thorn:þ,Thorn:Þ,lslash:ł,Lslash:Ł,scaron:š,Scaron:Š,zcaron:ž,Zcaron:Ž,ydieresis:ÿ,Ydieresis:Ÿ,arrowright:→,arrowleft:←,arrowup:↑,arrowdown:↓,check:✓,checkmark:✓,a20:✔"
            var i = 0
            while (i < pairs.length) {
                val c = pairs.indexOf(':', i)
                var j = c + 1
                val sb = StringBuilder()
                while (j < pairs.length && pairs[j] != ',') { if (pairs[j] == '\\' && j + 1 < pairs.length) { sb.append(pairs[j + 1]); j += 2 } else sb.append(pairs[j++]) }
                m[pairs.substring(i, c)] = sb.toString()
                i = j + 1
            }
            // huruf beraksen Latin-1: Aacute, eacute, ...
            val acc = mapOf("grave" to '̀', "acute" to '́', "circumflex" to '̂', "tilde" to '̃', "dieresis" to '̈', "ring" to '̊', "cedilla" to '̧', "caron" to '̌')
            for (base in ('a'..'z') + ('A'..'Z')) for ((nm, mark) in acc) {
                val s = java.text.Normalizer.normalize("$base$mark", java.text.Normalizer.Form.NFC)
                if (s.length == 1) m["$base$nm"] = s
            }
            m
        }

        fun glyph(n: String): String? {
            NAMES[n]?.let { return it }
            val b = n.substringBefore('.').substringBefore('_')
            NAMES[b]?.let { return it }
            if (b.startsWith("uni") && b.length >= 7) return try { b.substring(3).chunked(4).map { it.toInt(16).toChar() }.joinToString("") } catch (_: Exception) { null }
            if (b.startsWith("u") && b.length in 5..7) return try { String(Character.toChars(b.substring(1).toInt(16))) } catch (_: Exception) { null }
            return null
        }
    }
}
