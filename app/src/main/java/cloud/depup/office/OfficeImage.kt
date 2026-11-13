package cloud.depup.office

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Gambar di dalam dokumen Office → format yang bisa ditampilkan WebView. Murni Kotlin (diuji di JVM).
 *
 * Dulu gambar hanya dikenali dari EKSTENSI berkas (.png/.jpg/…), sehingga yang tampil kosong:
 *  - EMF / WMF — logo kop surat dari dokumen .doc lama, pratinjau objek OLE (tabel Excel tertanam, rumus Equation /
 *    MathType), clip art, grafik tempelan → diputar ulang menjadi SVG ([Metafile]);
 *  - TIFF (hasil pindai) → PNG ([Tiff]);
 *  - gambar tanpa ekstensi / ekstensi salah, .emz/.wmz (gzip) → dikenali dari isinya ([kind]).
 */
class PreparedImage(val mime: String, val bytes: ByteArray, val raster: Boolean)

/** Piksel ARGB, baris atas → bawah. */
class Raster(val w: Int, val h: Int, val px: IntArray, val alpha: Boolean)

/** Pembaca bilangan biner little/big-endian. */
internal object Bin {
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 255
    fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    fun i16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    fun i32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    fun u16be(b: ByteArray, o: Int): Int = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
    fun i32be(b: ByteArray, o: Int): Int = (u16be(b, o) shl 16) or u16be(b, o + 2)
    fun f32(b: ByteArray, o: Int): Double = java.lang.Float.intBitsToFloat(i32(b, o)).toDouble()
}

object OfficeImage {
    /** Jenis gambar dari tanda tangan ISINYA (bukan nama berkas). null = tidak dikenal. */
    fun kind(b: ByteArray): String? {
        val n = b.size
        if (n < 4) return null
        fun u(i: Int) = if (i < n) b[i].toInt() and 255 else -1
        if (u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47) return "png"
        if (u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return "jpeg"
        if (u(0) == 'G'.code && u(1) == 'I'.code && u(2) == 'F'.code && u(3) == '8'.code) return "gif"
        if (u(0) == 'R'.code && u(1) == 'I'.code && u(2) == 'F'.code && u(3) == 'F'.code && u(8) == 'W'.code && u(9) == 'E'.code && u(10) == 'B'.code && u(11) == 'P'.code) return "webp"
        if ((u(0) == 'I'.code && u(1) == 'I'.code && u(2) == 42 && u(3) == 0) || (u(0) == 'M'.code && u(1) == 'M'.code && u(2) == 0 && u(3) == 42)) return "tiff"
        if (u(0) == 0x1F && u(1) == 0x8B) return "gzip"
        if (u(0) == 'B'.code && u(1) == 'M'.code && n > 18) {
            val hs = Bin.i32(b, 14)
            if (hs == 12 || hs == 40 || hs == 52 || hs == 56 || hs == 64 || hs == 108 || hs == 124) return "bmp"
        }
        if (n >= 44 && Bin.i32(b, 0) == 1 && Bin.i32(b, 40) == 0x464D4520) return "emf"
        if (u(0) == 0xD7 && u(1) == 0xCD && u(2) == 0xC6 && u(3) == 0x9A) return "wmf"
        if (n >= 18 && (Bin.u16(b, 0) == 1 || Bin.u16(b, 0) == 2) && Bin.u16(b, 2) == 9 && (Bin.u16(b, 4) == 0x0300 || Bin.u16(b, 4) == 0x0100)) return "wmf"
        if (u(0) == 0 && u(1) == 0 && u(2) == 1 && u(3) == 0 && n > 22 && Bin.u16(b, 4) in 1..64) return "ico"
        if (n >= 12 && u(4) == 'f'.code && u(5) == 't'.code && u(6) == 'y'.code && u(7) == 'p'.code) {
            val brands = String(b, 8, min(n - 8, 40), Charsets.ISO_8859_1)
            return if (brands.contains("avif") || brands.contains("avis")) "avif" else "heif"
        }
        // SVG (teks XML)
        var s = 0
        if (u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF) s = 3
        while (s < n && s < 64 && (u(s) == ' '.code || u(s) == '\n'.code || u(s) == '\r'.code || u(s) == '\t'.code)) s++
        if (u(s) == '<'.code) {
            val head = String(b, s, min(n - s, 2048), Charsets.ISO_8859_1)
            if (head.contains("<svg")) return "svg"
        }
        return null
    }

    fun mimeOf(kind: String): String = when (kind) {
        "png" -> "image/png"; "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "webp" -> "image/webp"
        "svg" -> "image/svg+xml"; "ico" -> "image/x-icon"; "avif" -> "image/avif"; "heif" -> "image/heif"; "tiff" -> "image/tiff"
        else -> "application/octet-stream"
    }

    /** Format yang bisa ditangani [prepare] (atau dekoder platform untuk heif). */
    fun supported(kind: String?): Boolean = kind != null && kind != "heif"

    /**
     * [raw] → bentuk yang dimengerti WebView. Gambar biasa diteruskan apa adanya; EMF/WMF → SVG; TIFF → PNG;
     * gzip (.emz/.wmz) dibuka dulu. null = tidak bisa (pemanggil boleh mencoba dekoder platform, mis. HEIF di Android).
     */
    fun prepare(raw: ByteArray, maxPx: Int = 2048): PreparedImage? {
        var b = raw
        var k = kind(b) ?: return null
        if (k == "gzip") { b = gunzip(b) ?: return null; k = kind(b) ?: return null; if (k == "gzip") return null }
        return when (k) {
            "png", "jpeg", "bmp", "webp", "ico", "avif" -> PreparedImage(mimeOf(k), b, true)
            "gif" -> PreparedImage("image/gif", b, false)            // bisa beranimasi: jangan diolah ulang
            "svg" -> PreparedImage("image/svg+xml", b, false)
            "emf", "wmf" -> Metafile.toSvg(b, maxPx)?.let { PreparedImage("image/svg+xml", it.toByteArray(Charsets.UTF_8), false) }
            "tiff" -> Tiff.decode(b, maxPx)?.let { PreparedImage("image/png", Png.encode(it), true) }
            else -> null
        }
    }

    private fun gunzip(b: ByteArray, limit: Int = 64 shl 20): ByteArray? = try {
        GZIPInputStream(b.inputStream()).use { ins ->
            val out = ByteArrayOutputStream(b.size * 3)
            val buf = ByteArray(16384); var total = 0
            while (true) { val r = ins.read(buf); if (r < 0) break; total += r; if (total > limit) return null; out.write(buf, 0, r) }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    /** Kotak abu-abu berikon gambar — untuk gambar yang benar-benar tidak bisa dibaca (bukan ikon "rusak" WebView). */
    val PLACEHOLDER: ByteArray = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"150\" viewBox=\"0 0 100 100\" preserveAspectRatio=\"none\">" +
        "<rect width=\"100\" height=\"100\" fill=\"#eef0f3\"/><svg width=\"100\" height=\"100\" viewBox=\"-24 -24 72 72\" preserveAspectRatio=\"xMidYMid meet\">" +
        "<g fill=\"#a9afb9\"><path fill-rule=\"evenodd\" d=\"M3 4h18v16H3z M5 6v12h14V6z\"/><path d=\"M6 17l4-5 3 3.5 2-2.5 3 4z\"/><circle cx=\"15.5\" cy=\"9\" r=\"1.6\"/></g></svg></svg>").toByteArray()
}

// ============================================================================================ PNG
object Png {
    fun encode(r: Raster): ByteArray = encode(r.w, r.h, r.px, r.alpha)

    fun encode(w: Int, h: Int, px: IntArray, alpha: Boolean): ByteArray {
        val bpp = if (alpha) 4 else 3
        val raw = ByteArray((w * bpp + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 1                                         // filter "Sub": sederhana & cukup rapat
            var pr = 0; var pg = 0; var pb = 0; var pa = 0
            var i = y * w
            for (x in 0 until w) {
                val c = px[i++]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
                raw[o] = (r - pr).toByte(); raw[o + 1] = (g - pg).toByte(); raw[o + 2] = (bl - pb).toByte()
                pr = r; pg = g; pb = bl
                if (alpha) { val a = c ushr 24; raw[o + 3] = (a - pa).toByte(); pa = a }
                o += bpp
            }
        }
        val z = ByteArrayOutputStream(max(1024, raw.size / 3))
        val def = Deflater(6)
        try {
            def.setInput(raw); def.finish()
            val buf = ByteArray(32768)
            while (!def.finished()) { val n = def.deflate(buf); if (n <= 0 && def.needsInput()) break; z.write(buf, 0, n) }
        } finally { def.end() }
        val out = ByteArrayOutputStream(z.size() + 64)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        be32(ihdr, 0, w); be32(ihdr, 4, h); ihdr[8] = 8; ihdr[9] = (if (alpha) 6 else 2).toByte()
        chunk(out, "IHDR", ihdr); chunk(out, "IDAT", z.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun be32(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val tmp = ByteArray(4)
        be32(tmp, 0, data.size); out.write(tmp)
        val t = type.toByteArray(Charsets.ISO_8859_1); out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        be32(tmp, 0, crc.value.toInt()); out.write(tmp)
    }
}

// ============================================================================================ DIB (bitmap Windows)
object Dib {
    const val COPY = 0          // salin biasa (byte ke-4 piksel 32-bit diabaikan, seperti GDI)
    const val ALPHA = 1         // AlphaBlend: alfa per piksel (premultiplied)
    const val KEY_BLACK = 2     // SRCPAINT (OR): hitam = tembus
    const val KEY_WHITE = 3     // SRCAND (AND): putih = tembus
    const val INVERT = 4        // NOTSRCCOPY
    const val KEY_COLOR = 5     // TransparentBlt: warna [key] = tembus

    /** [w]/[h] = ukuran bagian bitmap yang diambil (piksel sumber, sebelum diperkecil). [cropped] = [crop] sudah diterapkan. */
    class Out(val img: PreparedImage, val w: Int, val h: Int, val bottomUp: Boolean, val cropped: Boolean = true)

    /** Lebar, tinggi, 1 bila tersimpan atas→bawah. */
    fun dims(b: ByteArray, bmi: Int): IntArray? {
        if (bmi < 0 || bmi + 12 > b.size) return null
        val hs = Bin.i32(b, bmi)
        return if (hs == 12) intArrayOf(Bin.u16(b, bmi + 4), abs(Bin.i16(b, bmi + 6)), if (Bin.i16(b, bmi + 6) < 0) 1 else 0)
        else if (hs >= 40 && bmi + 40 <= b.size) intArrayOf(Bin.i32(b, bmi + 4), abs(Bin.i32(b, bmi + 8)), if (Bin.i32(b, bmi + 8) < 0) 1 else 0)
        else null
    }

    /**
     * DIB di [b]: BITMAPINFO mulai [bmi], piksel mulai [bits0] (−1 = langsung sesudah tabel warna / "packed DIB").
     * [palIdx] = tabel warna berisi indeks palet (DIB_PAL_COLORS) — palet tidak dilacak, jadi dipakai skala abu-abu.
     */
    fun decode(b: ByteArray, bmi: Int, bits0: Int, maxPx: Int, mode: Int = COPY, key: Int = 0, palIdx: Boolean = false, bitsLen: Int = -1, crop: IntArray? = null): Out? {
        if (bmi < 0 || bmi + 12 > b.size) return null
        val hs = Bin.i32(b, bmi)
        val w: Int; val hRaw: Int; val bc: Int; var comp = 0; var clrUsed = 0; val pe: Int; var sizeImage = 0
        if (hs == 12) { w = Bin.u16(b, bmi + 4); hRaw = Bin.i16(b, bmi + 6); bc = Bin.u16(b, bmi + 10); pe = 3 }
        else if (hs >= 40 && bmi + 40 <= b.size) {
            w = Bin.i32(b, bmi + 4); hRaw = Bin.i32(b, bmi + 8); bc = Bin.u16(b, bmi + 14); comp = Bin.i32(b, bmi + 16)
            sizeImage = Bin.i32(b, bmi + 20); clrUsed = Bin.i32(b, bmi + 32); pe = 4
        } else return null
        val h = abs(hRaw); val topDown = hRaw < 0
        if (w <= 0 || h <= 0 || w > 40000 || h > 40000) return null
        var rM = 0; var gM = 0; var bM = 0; var aM = 0
        var palOff = bmi + hs
        if (comp == 3 || comp == 6) {
            if (hs >= 52) { rM = Bin.i32(b, bmi + 40); gM = Bin.i32(b, bmi + 44); bM = Bin.i32(b, bmi + 48); if (hs >= 56) aM = Bin.i32(b, bmi + 52) }
            else { rM = Bin.i32(b, palOff); gM = Bin.i32(b, palOff + 4); bM = Bin.i32(b, palOff + 8); palOff += 12; if (comp == 6) { aM = Bin.i32(b, palOff); palOff += 4 } }
        } else if (bc == 16) { rM = 0x7C00; gM = 0x03E0; bM = 0x001F }
        else if (bc == 32) { rM = 0xFF0000; gM = 0xFF00; bM = 0xFF; aM = 0xFF shl 24 }
        // BI_BITFIELDS 32-bit tanpa topeng alfa: alfa (AlphaBlend) ada di 8 bit sisanya
        if (bc == 32 && aM == 0) { val rest = (rM or gM or bM).inv(); if (Integer.bitCount(rest) == 8) aM = rest }
        val nPal = if (bc <= 8) (if (clrUsed in 1..(1 shl bc)) clrUsed else 1 shl bc) else (if (clrUsed in 1..4096) clrUsed else 0)
        val entry = if (palIdx) 2 else pe
        val pal = IntArray(if (bc <= 8) 1 shl bc else 1) { if (bc == 1 && it == 1) -1 else 0xFF000000.toInt() }
        if (bc <= 8) for (i in 0 until nPal) {
            val o = palOff + i * entry
            if (o + entry > b.size) break
            pal[i] = if (palIdx) { val g = (i * 255 / max(1, nPal - 1)) and 255; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            else (0xFF shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255)
        }
        val bits = if (bits0 >= 0) bits0 else palOff + nPal * entry
        if (bits < 0 || bits > b.size) return null
        if (comp == 4 || comp == 5) {                             // JPEG / PNG utuh di dalam DIB
            val len = if (bitsLen > 0) bitsLen else if (sizeImage > 0) sizeImage else b.size - bits
            val raw = b.copyOfRange(bits, min(b.size, bits + len))
            val k = OfficeImage.kind(raw) ?: return null
            return Out(PreparedImage(OfficeImage.mimeOf(k), raw, true), w, h, !topDown, cropped = crop == null)
        }
        if (comp != 0 && comp != 1 && comp != 2 && comp != 3 && comp != 6) return null
        if (bc != 1 && bc != 4 && bc != 8 && bc != 16 && bc != 24 && bc != 32) return null
        // bagian yang diambil (koordinat atas→bawah); tepi tidak ikut tersampel dari piksel di luar bagian
        var cx = 0; var cy = 0; var cw = w; var chh = h
        if (crop != null) {
            cx = crop[0].coerceIn(0, w - 1); cy = crop[1].coerceIn(0, h - 1)
            cw = crop[2].coerceIn(1, w - cx); chh = crop[3].coerceIn(1, h - cy)
        }
        val step = max(1, ceil(max(cw, chh).toDouble() / max(64, maxPx)).toInt())
        val ow = (cw + step - 1) / step; val oh = (chh + step - 1) / step
        val px = IntArray(ow * oh)
        val stride = ((w * bc + 31) / 32) * 4
        val idx = if (comp == 1 && bc == 8) rle(b, bits, w, h, false) else if (comp == 2 && bc == 4) rle(b, bits, w, h, true) else null
        val useAlpha = mode == ALPHA && (bc == 32 || aM != 0)
        var anyA = false
        for (oy in 0 until oh) {
            val sy = cy + oy * step
            val row = if (topDown) sy else h - 1 - sy
            val ro = bits + row * stride
            for (ox in 0 until ow) {
                val sx = cx + ox * step
                var c: Int
                if (idx != null) c = pal[idx[row * w + sx].toInt() and (pal.size - 1)]
                else c = when (bc) {
                    1 -> { val o = ro + (sx shr 3); if (o < b.size) pal[(b[o].toInt() shr (7 - (sx and 7))) and 1] else 0 }
                    4 -> { val o = ro + (sx shr 1); if (o < b.size) pal[(if (sx and 1 == 0) b[o].toInt() shr 4 else b[o].toInt()) and 15] else 0 }
                    8 -> { val o = ro + sx; if (o < b.size) pal[b[o].toInt() and 255] else 0 }
                    16 -> { val o = ro + sx * 2; if (o + 1 < b.size) { val v = Bin.u16(b, o); (0xFF shl 24) or (ch(v, rM) shl 16) or (ch(v, gM) shl 8) or ch(v, bM) } else 0 }
                    24 -> { val o = ro + sx * 3; if (o + 2 < b.size) (0xFF shl 24) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o].toInt() and 255) else 0 }
                    else -> { val o = ro + sx * 4; if (o + 3 < b.size) { val v = Bin.i32(b, o)
                        val a = if (useAlpha && aM != 0) ch(v, aM) else 255
                        if (a != 255) anyA = true
                        (a shl 24) or (ch(v, rM) shl 16) or (ch(v, gM) shl 8) or ch(v, bM) } else 0 }
                }
                px[oy * ow + ox] = c
            }
        }
        var alpha = false
        when (mode) {
            ALPHA -> if (useAlpha && anyA) {
                alpha = true
                // premultiplied → biasa
                for (i in px.indices) { val c = px[i]; val a = c ushr 24
                    if (a in 1..254) { fun un(v: Int) = min(255, v * 255 / a)
                        px[i] = (a shl 24) or (un((c shr 16) and 255) shl 16) or (un((c shr 8) and 255) shl 8) or un(c and 255) } }
            } else for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
            KEY_BLACK, KEY_WHITE, KEY_COLOR -> {
                val k = when (mode) { KEY_BLACK -> 0; KEY_WHITE -> 0xFFFFFF; else -> key and 0xFFFFFF }
                for (i in px.indices) { val c = px[i] and 0xFFFFFF; if (c == k) { px[i] = 0; alpha = true } else px[i] = c or (0xFF shl 24) }
            }
            INVERT -> for (i in px.indices) px[i] = (px[i].inv() and 0xFFFFFF) or (0xFF shl 24)
            else -> for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        }
        return Out(PreparedImage("image/png", Png.encode(ow, oh, px, alpha), true), cw, chh, !topDown)
    }

    /**
     * Bitmap 1-bit untuk kuas monokrom: proporsi bit 1 (0..1). Bit 0 digambar dengan warna teks, bit 1 dengan warna
     * latar DC (aturan GDI untuk kuas pola monokrom). null bila bukan bitmap 1-bit.
     */
    fun ones(b: ByteArray, bmi: Int, bits0: Int, palIdx: Boolean): Double? {
        val d = dims(b, bmi) ?: return null
        val hs = Bin.i32(b, bmi)
        val bc = if (hs == 12) Bin.u16(b, bmi + 10) else Bin.u16(b, bmi + 14)
        if (bc != 1) return null
        val w = d[0]; val h = d[1]
        if (w <= 0 || h <= 0 || w > 64 || h > 64) return null
        val bits = if (bits0 >= 0) bits0 else bmi + hs + 2 * (if (palIdx) 2 else if (hs == 12) 3 else 4)
        val stride = ((w + 31) / 32) * 4
        var n = 0
        for (y in 0 until h) for (x in 0 until w) { val o = bits + y * stride + (x shr 3); if (o < b.size && (b[o].toInt() shr (7 - (x and 7))) and 1 == 1) n++ }
        return n.toDouble() / (w * h)
    }

    /** Nilai kanal dari [v] sesuai [m] (bitfield), diskalakan ke 0..255. */
    private fun ch(v: Int, m: Int): Int {
        if (m == 0) return 0
        val sh = Integer.numberOfTrailingZeros(m); val n = Integer.bitCount(m)
        val x = (v and m) ushr sh
        return if (n >= 8) (x ushr (n - 8)) and 255 else (x * 255 + ((1 shl n) - 1) / 2) / ((1 shl n) - 1)
    }

    /** RLE8 / RLE4 → indeks palet per piksel (urutan baris seperti di berkas). */
    private fun rle(b: ByteArray, start: Int, w: Int, h: Int, four: Boolean): ByteArray? {
        if (w.toLong() * h > 64L * 1024 * 1024) return null
        val out = ByteArray(w * h)
        var x = 0; var y = 0; var o = start
        fun put(v: Int) { if (x < w && y < h) out[y * w + x] = v.toByte(); x++ }
        while (o + 1 < b.size && y < h) {
            val n = b[o].toInt() and 255; val v = b[o + 1].toInt() and 255; o += 2
            if (n > 0) {
                if (four) for (i in 0 until n) put(if (i and 1 == 0) v shr 4 else v and 15) else for (i in 0 until n) put(v)
            } else when (v) {
                0 -> { x = 0; y++ }
                1 -> return out
                2 -> { if (o + 1 >= b.size) return out; x += b[o].toInt() and 255; y += b[o + 1].toInt() and 255; o += 2 }
                else -> {
                    if (four) { for (i in 0 until v) { val bb = if (o + (i shr 1) < b.size) b[o + (i shr 1)].toInt() and 255 else 0; put(if (i and 1 == 0) bb shr 4 else bb and 15) }
                        val nb = (v + 1) / 2; o += nb + (nb and 1) }
                    else { for (i in 0 until v) put(if (o + i < b.size) b[o + i].toInt() and 255 else 0); o += v + (v and 1) }
                }
            }
        }
        return out
    }
}

// ============================================================================================ TIFF
object Tiff {
    private val WANTED = intArrayOf(256, 257, 258, 259, 262, 273, 277, 278, 279, 284, 317, 320, 322, 323, 324, 325, 338)

    fun decode(b: ByteArray, maxPx: Int): Raster? = try { decode0(b, maxPx) } catch (_: Throwable) { null }

    private fun decode0(b: ByteArray, maxPx: Int): Raster? {
        val le = b[0] == 'I'.code.toByte()
        fun u16(o: Int) = if (le) Bin.u16(b, o) else Bin.u16be(b, o)
        fun u32(o: Int) = if (le) Bin.i32(b, o) else Bin.i32be(b, o)
        val ifd = u32(4)
        if (ifd <= 0 || ifd + 2 > b.size) return null
        val n = u16(ifd)
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            if (e + 12 > b.size) break
            val tag = u16(e)
            if (tag !in WANTED) continue
            val type = u16(e + 2); val cnt = u32(e + 4)
            val sz = when (type) { 1, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; else -> continue }
            if (cnt <= 0 || cnt > (1 shl 22)) continue
            val vo = if (sz * cnt <= 4) e + 8 else u32(e + 8)
            if (vo < 0 || vo + sz * cnt > b.size) continue
            tags[tag] = IntArray(cnt) { k -> when (sz) { 1 -> b[vo + k].toInt() and 255; 2 -> u16(vo + 2 * k); else -> u32(vo + 4 * k) } }
        }
        val w = tags[256]?.get(0) ?: return null; val h = tags[257]?.get(0) ?: return null
        if (w <= 0 || h <= 0 || w > 60000 || h > 60000) return null
        val spp = tags[277]?.get(0) ?: 1
        val bps = tags[258]?.get(0) ?: 1
        val comp = tags[259]?.get(0) ?: 1
        val photo = tags[262]?.get(0) ?: if (spp >= 3) 2 else 1
        if ((tags[284]?.get(0) ?: 1) != 1 && spp > 1) return null
        val pred = tags[317]?.get(0) ?: 1
        if (comp != 1 && comp != 5 && comp != 8 && comp != 32946 && comp != 32773) return null
        if (bps != 1 && bps != 2 && bps != 4 && bps != 8 && bps != 16) return null
        if (photo !in intArrayOf(0, 1, 2, 3, 5)) return null
        val cmap = tags[320]
        val alphaIdx = if (photo == 2 && spp >= 4 && tags[338] != null) 3 else if ((photo == 0 || photo == 1) && spp >= 2 && tags[338] != null) 1 else -1
        val premul = tags[338]?.get(0) == 1
        val step = max(1, ceil(max(w, h).toDouble() / max(64, maxPx)).toInt())
        val ow = (w + step - 1) / step; val oh = (h + step - 1) / step
        val px = IntArray(ow * oh)
        val tiled = tags.containsKey(322)
        val segW = if (tiled) tags[322]!![0] else w
        val segH = if (tiled) tags[323]?.get(0) ?: return null else (tags[278]?.get(0)?.takeIf { it in 1..h } ?: h)
        val offs = (if (tiled) tags[324] else tags[273]) ?: return null
        val cnts = (if (tiled) tags[325] else tags[279])
        val across = if (tiled) (w + segW - 1) / segW else 1
        val rowBytes = (segW * spp * bps + 7) / 8
        var anyAlpha = false
        for (si in offs.indices) {
            val x0 = if (tiled) (si % across) * segW else 0
            val y0 = if (tiled) (si / across) * segH else si * segH
            if (y0 >= h || x0 >= w) continue
            val rows = min(segH, h - y0)
            // ada baris/kolom keluaran di potongan ini?
            val firstOy = (y0 + step - 1) / step
            if (firstOy * step >= y0 + rows) continue
            val off = offs[si]; val len = cnts?.getOrNull(si)?.takeIf { it > 0 } ?: (b.size - off)
            if (off < 0 || off >= b.size) continue
            val need = rowBytes * (if (tiled) segH else rows)
            val buf = when (comp) {
                1 -> b.copyOfRange(off, min(b.size, off + max(len, need)))
                5 -> lzw(b, off, min(len, b.size - off), need)
                32773 -> packBits(b, off, min(len, b.size - off), need)
                else -> inflate(b, off, min(len, b.size - off), need)
            } ?: continue
            if (pred == 2) for (r in 0 until rows) {
                val ro = r * rowBytes
                if (bps == 8) for (i in spp until segW * spp) { val k = ro + i; if (k < buf.size) buf[k] = (buf[k] + buf[k - spp]).toByte() }
                else if (bps == 16) for (i in spp until segW * spp) { val k = ro + i * 2; if (k + 1 < buf.size) {
                    val p = ro + (i - spp) * 2
                    val v = (if (le) Bin.u16(buf, k) + Bin.u16(buf, p) else Bin.u16be(buf, k) + Bin.u16be(buf, p)) and 0xFFFF
                    if (le) { buf[k] = v.toByte(); buf[k + 1] = (v shr 8).toByte() } else { buf[k] = (v shr 8).toByte(); buf[k + 1] = v.toByte() } } }
            }
            fun smp(r: Int, c: Int, s: Int, raw: Boolean = false): Int {
                val bit = (r * rowBytes * 8) + (c * spp + s) * bps
                val o = bit ushr 3
                if (o >= buf.size) return 0
                return when (bps) {
                    8 -> buf[o].toInt() and 255
                    16 -> if (o + 1 < buf.size) (if (le) Bin.u16(buf, o) else Bin.u16be(buf, o)).let { if (raw) it else it ushr 8 } else 0
                    else -> { val v = ((buf[o].toInt() and 255) shr (8 - bps - (bit and 7))) and ((1 shl bps) - 1); if (raw) v else v * 255 / ((1 shl bps) - 1) }
                }
            }
            var oy = firstOy
            while (oy * step < y0 + rows && oy < oh) {
                val r = oy * step - y0
                var ox = (x0 + step - 1) / step
                while (ox * step < x0 + segW && ox < ow) {
                    val c = ox * step - x0
                    if (ox * step >= w) break
                    val argb: Int = when (photo) {
                        0, 1 -> { var g = smp(r, c, 0); if (photo == 0) g = 255 - g
                            val a = if (alphaIdx > 0) smp(r, c, alphaIdx) else 255
                            (a shl 24) or (g shl 16) or (g shl 8) or g }
                        3 -> { val i = smp(r, c, 0, raw = true); val nc = 1 shl bps
                            if (cmap != null && cmap.size >= 3 * nc && i < nc) (0xFF shl 24) or ((cmap[i] ushr 8) shl 16) or ((cmap[nc + i] ushr 8) shl 8) or (cmap[2 * nc + i] ushr 8)
                            else (0xFF shl 24) or (i * 0x010101) }
                        5 -> { val cc = smp(r, c, 0); val m = smp(r, c, 1); val y = smp(r, c, 2); val k = if (spp >= 4) smp(r, c, 3) else 0
                            (0xFF shl 24) or (((255 - cc) * (255 - k) / 255) shl 16) or (((255 - m) * (255 - k) / 255) shl 8) or ((255 - y) * (255 - k) / 255) }
                        else -> { var rr = smp(r, c, 0); var gg = if (spp > 1) smp(r, c, 1) else rr; var bb = if (spp > 2) smp(r, c, 2) else rr
                            val a = if (alphaIdx > 0) smp(r, c, alphaIdx) else 255
                            if (premul && a in 1..254) { rr = min(255, rr * 255 / a); gg = min(255, gg * 255 / a); bb = min(255, bb * 255 / a) }
                            (a shl 24) or (rr shl 16) or (gg shl 8) or bb }
                    }
                    if ((argb ushr 24) != 255) anyAlpha = true
                    px[oy * ow + ox] = argb
                    ox++
                }
                oy++
            }
        }
        return Raster(ow, oh, px, anyAlpha)
    }

    private fun inflate(b: ByteArray, off: Int, len: Int, need: Int): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(b, off, len)
            val out = ByteArray(need); var n = 0
            while (n < need && !inf.finished()) { val r = inf.inflate(out, n, need - n); if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break; n += r }
            out
        } catch (_: Exception) { null } finally { inf.end() }
    }

    private fun packBits(b: ByteArray, off: Int, len: Int, need: Int): ByteArray {
        val out = ByteArray(need); var o = off; var p = 0; val end = off + len
        while (o < end && p < need) {
            val n = b[o++].toInt()
            if (n >= 0) { var k = n + 1; while (k-- > 0 && o < end && p < need) out[p++] = b[o++] }
            else if (n != -128) { if (o >= end) break; val v = b[o++]; var k = -n + 1; while (k-- > 0 && p < need) out[p++] = v }
        }
        return out
    }

    private fun lzw(b: ByteArray, off: Int, len: Int, need: Int): ByteArray {
        val out = ByteArray(need); var op = 0
        val prefix = IntArray(4096); val suffix = ByteArray(4096); val first = ByteArray(4096); val length = IntArray(4096)
        for (i in 0 until 256) { prefix[i] = -1; suffix[i] = i.toByte(); first[i] = i.toByte(); length[i] = 1 }
        var next = 258; var width = 9; var prev = -1
        var bitPos = off.toLong() * 8; val endBit = (off + len).toLong() * 8
        fun read(): Int {
            if (bitPos + width > endBit) return -1
            var v = 0
            for (i in 0 until width) { val p = bitPos + i; v = (v shl 1) or ((b[(p ushr 3).toInt()].toInt() shr (7 - (p and 7).toInt())) and 1) }
            bitPos += width
            return v
        }
        fun emit(code: Int) {
            val l = length[code]
            if (op + l > need) { var c = code; val tmp = ByteArray(l); var k = l - 1; while (c >= 0 && k >= 0) { tmp[k--] = suffix[c]; c = prefix[c] }; var i = 0; while (op < need && i < l) out[op++] = tmp[i++]; return }
            var c = code; var k = op + l - 1
            while (c >= 0 && k >= op) { out[k--] = suffix[c]; c = prefix[c] }
            op += l
        }
        while (op < need) {
            val code = read()
            if (code < 0 || code == 257) break
            if (code == 256) { next = 258; width = 9; prev = -1; continue }
            if (prev < 0) { if (code > 255) break; emit(code); prev = code; continue }
            if (code < next) {
                emit(code)
                if (next < 4096) { prefix[next] = prev; suffix[next] = first[code]; first[next] = first[prev]; length[next] = length[prev] + 1; next++ }
            } else {
                if (next >= 4096) break
                prefix[next] = prev; suffix[next] = first[prev]; first[next] = first[prev]; length[next] = length[prev] + 1; next++
                emit(next - 1)
            }
            prev = code
            if (next >= 2047) width = 12 else if (next >= 1023) width = 11 else if (next >= 511) width = 10
        }
        return out
    }
}

// ============================================================================================ EMF / WMF → SVG
object Metafile {
    fun toSvg(b: ByteArray, maxPx: Int = 2048): String? = try {
        when (OfficeImage.kind(b)) {
            "emf" -> EmfPlayer(b, maxPx).play()
            "wmf" -> WmfPlayer(b, maxPx).play()
            else -> null
        }
    } catch (_: Throwable) { null }

    /** Font Symbol (huruf Yunani & simbol rumus Equation Editor / MathType) → Unicode, kode 0x20..0xFF. */
    private const val SYMBOL =
        " !∀#∃%&∋()∗+,−./" + "0123456789:;<=>?" + "≅ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟ" + "ΠΘΡΣΤΥςΩΞΨΖ[∴]⊥_" +
        "‾αβχδεφγηιϕκλμνο" + "πθρστυϖωξψζ{|}∼ " + "                " + "                " +
        "€ϒ′≤⁄∞ƒ♣♦♥♠↔←↑→↓" + "°±″≥×∝∂•÷≠≡≈…⏐⎯↵" + "ℵℑℜ℘⊗⊕∅∩∪⊃⊇⊄⊂⊆∈∉" + "∠∇®©™∏√⋅¬∧∨⇔⇐⇑⇒⇓" +
        "◊〈®©™∑⎛⎜⎝⎡⎢⎣⎧⎨⎩⎪" + " 〉∫⌠⎮⌡⎞⎟⎠⎤⎥⎦⎫⎬⎭ "

    internal fun symbolText(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            var c = ch.code
            if (c in 0xF020..0xF0FF) c -= 0xF000
            sb.append(if (c in 0x20..0xFF && SYMBOL.length == 224) SYMBOL[c - 0x20] else ch)
        }
        return sb.toString()
    }
}

/** Pemutar GDI → SVG bersama untuk EMF & WMF. Koordinat keluaran = ruang "perangkat" metafile. */
private abstract class GdiSvg(protected val maxPx: Int) {
    protected val body = StringBuilder(16384)
    protected val defs = StringBuilder()
    private var ids = 0
    protected fun nid(): String = "q" + (ids++)
    /** Ukuran 1 piksel layar (96 dpi) dalam satuan perangkat — tebal garis "kosmetik" & minimum. */
    protected var px1 = 1.0

    class Pen(val style: Int, val width: Double, val color: Int, val geometric: Boolean, val dashes: DoubleArray? = null) { val none get() = (style and 0xF) == 5 }
    class Brush(val style: Int, val color: Int, val hatch: Int, val pattern: String? = null, val mono: Double = -1.0)
    class Font(val height: Double, val esc: Double, val weight: Int, val italic: Boolean, val underline: Boolean, val strike: Boolean, val face: String, val charset: Int)

    class DC {
        var pen = Pen(0, 0.0, 0, false); var brush = Brush(0, 0xFFFFFF, 0); var font = Font(-16.0, 0.0, 400, false, false, false, "Arial", 0)
        var textColor = 0; var bkColor = 0xFFFFFF; var bkMode = 2; var fillMode = 1; var textAlign = 0; var rop2 = 13
        var mapMode = 1
        var wox = 0.0; var woy = 0.0; var wex = 1.0; var wey = 1.0
        var vox = 0.0; var voy = 0.0; var vex = 1.0; var vey = 1.0
        var m11 = 1.0; var m12 = 0.0; var m21 = 0.0; var m22 = 1.0; var mdx = 0.0; var mdy = 0.0
        var cpx = 0.0; var cpy = 0.0
        var clip: String? = null
        var arcCw = false
        fun copy(): DC {
            val d = DC()
            d.pen = pen; d.brush = brush; d.font = font; d.textColor = textColor; d.bkColor = bkColor; d.bkMode = bkMode; d.fillMode = fillMode
            d.textAlign = textAlign; d.rop2 = rop2; d.mapMode = mapMode
            d.wox = wox; d.woy = woy; d.wex = wex; d.wey = wey; d.vox = vox; d.voy = voy; d.vex = vex; d.vey = vey
            d.m11 = m11; d.m12 = m12; d.m21 = m21; d.m22 = m22; d.mdx = mdx; d.mdy = mdy
            d.cpx = cpx; d.cpy = cpy; d.clip = clip; d.arcCw = arcCw
            return d
        }
    }

    protected var dc = DC()
    private val saved = ArrayList<DC>()
    protected var sx = 1.0
    protected var sy = 1.0
    protected abstract fun mapping()

    protected fun saveDC() { if (saved.size < 1024) saved.add(dc.copy()) }
    protected fun restoreDC(n: Int) {
        val t = if (n < 0) saved.size + n else n - 1
        if (t < 0 || t >= saved.size) return
        dc = saved[t]
        while (saved.size > t) saved.removeAt(saved.size - 1)
        mapping()
    }

    fun X(x: Double, y: Double) = ((dc.m11 * x + dc.m21 * y + dc.mdx) - dc.wox) * sx + dc.vox
    fun Y(x: Double, y: Double) = ((dc.m12 * x + dc.m22 * y + dc.mdy) - dc.woy) * sy + dc.voy
    protected fun lenScale(): Double = sqrt(abs((dc.m11 * dc.m22 - dc.m12 * dc.m21) * sx * sy))

    // kotak pembatas yang tergambar (untuk WMF tanpa bingkai)
    protected var bx0 = Double.MAX_VALUE; protected var by0 = Double.MAX_VALUE; protected var bx1 = -Double.MAX_VALUE; protected var by1 = -Double.MAX_VALUE
    private fun track(x: Double, y: Double) { if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y }

    protected fun num(sb: StringBuilder, v0: Double) {
        val v = if (v0.isNaN() || v0.isInfinite()) 0.0 else v0
        val r = Math.round(v * 100)
        if (r % 100 == 0L) { sb.append(r / 100); return }
        if (r < 0) sb.append('-')
        val a = abs(r)
        sb.append(a / 100).append('.')
        val f = a % 100
        if (f < 10) sb.append('0')
        if (f % 10 == 0L) sb.append(f / 10) else sb.append(f)
    }
    protected fun pt(sb: StringBuilder, x: Double, y: Double) { val dx = X(x, y); val dy = Y(x, y); track(dx, dy); num(sb, dx); sb.append(' '); num(sb, dy) }
    protected fun bez(sb: StringBuilder, x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        sb.append('C'); pt(sb, x1, y1); sb.append(' '); pt(sb, x2, y2); sb.append(' '); pt(sb, x3, y3)
    }

    protected fun hex(c: Int): String {
        val r = c and 255; val g = (c shr 8) and 255; val b = (c shr 16) and 255
        val v = (r shl 16) or (g shl 8) or b
        val s = Integer.toHexString(v)
        return "#" + "000000".substring(s.length) + s
    }

    // ------------------------------------------------------------------ garis (MoveTo/LineTo) & path
    private var line: StringBuilder? = null
    protected var inPath = false
    protected val path = StringBuilder()
    protected var pathOpen = false

    protected fun moveTo(x: Double, y: Double) {
        dc.cpx = x; dc.cpy = y
        if (inPath) { path.append('M'); pt(path, x, y); pathOpen = true; return }
        line?.let { it.append('M'); pt(it, x, y) }
    }
    protected fun lineTo(x: Double, y: Double) {
        if (inPath) {
            if (!pathOpen) { path.append('M'); pt(path, dc.cpx, dc.cpy); pathOpen = true }
            path.append('L'); pt(path, x, y); dc.cpx = x; dc.cpy = y; return
        }
        val l = line ?: StringBuilder().also { line = it; it.append('M'); pt(it, dc.cpx, dc.cpy) }
        l.append('L'); pt(l, x, y)
        dc.cpx = x; dc.cpy = y
        if (l.length > 100000) flushLine()
    }
    protected fun flushLine() { val l = line ?: return; line = null; emit(l, fill = false, stroke = true) }

    /** Geometri: ke path aktif (BeginPath) atau langsung digambar. [open] = figur tidak ditutup (polyline). */
    protected fun shape(d: StringBuilder, fill: Boolean, stroke: Boolean, open: Boolean = false) {
        if (d.isEmpty()) return
        if (inPath) { path.append(d); pathOpen = open } else emit(d, fill, stroke)
    }

    protected fun emit(d: CharSequence, fill: Boolean, stroke: Boolean, clipOverride: String? = null) {
        if (dc.rop2 == 11) return                                  // R2_NOP
        val f = if (fill) fillPaint() else null
        val s = if (stroke) strokeAttrs() else null
        if (f == null && s == null) return
        body.append("<path d=\"").append(d).append("\" fill=\"").append(f ?: "none").append('"')
        if (f != null && dc.fillMode == 1) body.append(" fill-rule=\"evenodd\"")
        if (s != null) body.append(s)
        (clipOverride ?: dc.clip)?.let { body.append(" clip-path=\"url(#").append(it).append(")\"") }
        body.append("/>")
    }

    private val hatchIds = HashMap<String, String>()
    /** Campuran dua COLORREF (0x00BBGGRR). */
    protected fun mixRef(a: Int, b: Int, t: Double): Int {
        fun ch(c: Int, sh: Int) = (c shr sh) and 255
        fun m(sh: Int) = (ch(a, sh) + (ch(b, sh) - ch(a, sh)) * t).roundToInt().coerceIn(0, 255)
        return m(0) or (m(8) shl 8) or (m(16) shl 16)
    }
    protected var hatchScale = 1.0

    protected fun fillPaint(): String? {
        val br = dc.brush
        if (br.mono >= 0) return hex(mixRef(dc.textColor, dc.bkColor, br.mono))
        return when (br.style) {
            1 -> null
            2 -> hatch(br)
            3, 5, 6 -> br.pattern?.let { "url(#$it)" } ?: hex(br.color)
            else -> when (dc.rop2) { 1 -> "#000000"; 16 -> "#ffffff"; else -> hex(br.color) }
        }
    }

    private fun hatch(br: Brush): String {
        val bk = if (dc.bkMode == 2) dc.bkColor else -1
        val key = "${br.hatch}:${br.color}:$bk"
        hatchIds[key]?.let { return "url(#$it)" }
        val id = nid(); hatchIds[key] = id
        val s = 8 * hatchScale
        val sb = StringBuilder()
        sb.append("<pattern id=\"").append(id).append("\" patternUnits=\"userSpaceOnUse\" width=\""); num(sb, s); sb.append("\" height=\""); num(sb, s); sb.append("\">")
        if (bk >= 0) { sb.append("<rect width=\""); num(sb, s); sb.append("\" height=\""); num(sb, s); sb.append("\" fill=\"").append(hex(bk)).append("\"/>") }
        val q = s / 8
        fun p(x: Double) = Math.round(x * q * 100) / 100.0
        val d = when (br.hatch) {
            0 -> "M0 ${p(4.0)}H${p(8.0)}"; 1 -> "M${p(4.0)} 0V${p(8.0)}"; 2 -> "M0 0L${p(8.0)} ${p(8.0)}"; 3 -> "M0 ${p(8.0)}L${p(8.0)} 0"
            4 -> "M0 ${p(4.0)}H${p(8.0)}M${p(4.0)} 0V${p(8.0)}"; else -> "M0 0L${p(8.0)} ${p(8.0)}M0 ${p(8.0)}L${p(8.0)} 0"
        }
        sb.append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(hex(br.color)).append("\" stroke-width=\""); num(sb, q); sb.append("\"/></pattern>")
        defs.append(sb)
        return "url(#$id)"
    }

    protected fun strokeAttrs(): String? {
        val p = dc.pen
        if (p.none) return null
        val sb = StringBuilder(" stroke=\"")
        sb.append(when (dc.rop2) { 1 -> "#000000"; 16 -> "#ffffff"; else -> hex(p.color) }).append("\" stroke-width=\"")
        val w = if (p.geometric && p.width > 0) max(p.width * lenScale(), px1) else px1
        num(sb, w); sb.append('"')
        val cap = when (p.style and 0xF00) { 0x100 -> "square"; 0x200 -> "butt"; else -> if (p.geometric && w > px1 * 1.5) "round" else "butt" }
        val join = when (p.style and 0xF000) { 0x1000 -> "bevel"; 0x2000 -> "miter"; else -> if (p.geometric && w > px1 * 1.5) "round" else "miter" }
        if (cap != "butt") sb.append(" stroke-linecap=\"").append(cap).append('"')
        if (join != "miter") sb.append(" stroke-linejoin=\"").append(join).append('"')
        val unit = if (p.geometric && w > px1 * 1.5) w else px1
        val dash: DoubleArray? = when (p.style and 0xF) {
            1 -> if (unit == px1) doubleArrayOf(18.0, 6.0) else doubleArrayOf(3.0, 1.0)
            2 -> if (unit == px1) doubleArrayOf(3.0, 3.0) else doubleArrayOf(1.0, 1.0)
            3 -> if (unit == px1) doubleArrayOf(9.0, 6.0, 3.0, 6.0) else doubleArrayOf(3.0, 1.0, 1.0, 1.0)
            4 -> if (unit == px1) doubleArrayOf(9.0, 3.0, 3.0, 3.0, 3.0, 3.0) else doubleArrayOf(3.0, 1.0, 1.0, 1.0, 1.0, 1.0)
            7 -> p.dashes?.map { it * (if (p.geometric) lenScale() / unit else 1.0) }?.toDoubleArray()
            8 -> doubleArrayOf(1.0, 1.0)
            else -> null
        }
        if (dash != null && dash.isNotEmpty() && dash.any { it > 0 }) {
            sb.append(" stroke-dasharray=\"")
            dash.forEachIndexed { i, d -> if (i > 0) sb.append(' '); num(sb, max(0.0, d * unit)) }
            sb.append('"')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ bentuk dasar (koordinat logis)
    protected fun rectPath(sb: StringBuilder, l: Double, t: Double, r: Double, b: Double) {
        sb.append('M'); pt(sb, l, t); sb.append('L'); pt(sb, r, t); sb.append('L'); pt(sb, r, b); sb.append('L'); pt(sb, l, b); sb.append('Z')
    }
    protected fun ellipsePath(sb: StringBuilder, l: Double, t: Double, r: Double, b: Double) {
        val cx = (l + r) / 2; val cy = (t + b) / 2; val rx = (r - l) / 2; val ry = (b - t) / 2
        val k = 0.5522847498
        sb.append('M'); pt(sb, cx + rx, cy)
        bez(sb, cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        bez(sb, cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        bez(sb, cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        bez(sb, cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        sb.append('Z')
    }
    protected fun roundRectPath(sb: StringBuilder, l0: Double, t0: Double, r0: Double, b0: Double, cw: Double, ch: Double) {
        val l = min(l0, r0); val r = max(l0, r0); val t = min(t0, b0); val b = max(t0, b0)
        val rx = min(abs(cw) / 2, (r - l) / 2); val ry = min(abs(ch) / 2, (b - t) / 2)
        if (rx <= 0 || ry <= 0) { rectPath(sb, l, t, r, b); return }
        val k = 0.5522847498
        sb.append('M'); pt(sb, l + rx, t); sb.append('L'); pt(sb, r - rx, t)
        bez(sb, r - rx + rx * k, t, r, t + ry - ry * k, r, t + ry)
        sb.append('L'); pt(sb, r, b - ry)
        bez(sb, r, b - ry + ry * k, r - rx + rx * k, b, r - rx, b)
        sb.append('L'); pt(sb, l + rx, b)
        bez(sb, l + rx - rx * k, b, l, b - ry + ry * k, l, b - ry)
        sb.append('L'); pt(sb, l, t + ry)
        bez(sb, l, t + ry - ry * k, l + rx - rx * k, t, l + rx, t)
        sb.append('Z')
    }

    protected var arcEndX = 0.0
    protected var arcEndY = 0.0

    /** [kind]: 0 = busur, 1 = chord, 2 = pie, 3 = ArcTo (bersambung dari titik sekarang). */
    protected fun arcPath(sb: StringBuilder, l: Double, t: Double, r: Double, b: Double, xs: Double, ys: Double, xe: Double, ye: Double, kind: Int) {
        val cx = (l + r) / 2; val cy = (t + b) / 2; val rx = abs(r - l) / 2; val ry = abs(b - t) / 2
        if (rx <= 0 || ry <= 0) return
        val a0 = atan2((ys - cy) / ry, (xs - cx) / rx); val a1 = atan2((ye - cy) / ry, (xe - cx) / rx)
        // GDI bawaan berlawanan arah jarum jam di layar (sumbu y ke bawah) = sudut atan2 mengecil
        var sweep = a1 - a0
        if (!dc.arcCw) { while (sweep >= 0) sweep -= 2 * PI } else { while (sweep <= 0) sweep += 2 * PI }
        val sx0 = cx + rx * cos(a0); val sy0 = cy + ry * sin(a0)
        when (kind) {
            2 -> { sb.append('M'); pt(sb, cx, cy); sb.append('L'); pt(sb, sx0, sy0) }
            3 -> { sb.append('L'); pt(sb, sx0, sy0) }
            else -> { sb.append('M'); pt(sb, sx0, sy0) }
        }
        val segs = max(1, ceil(abs(sweep) / (PI / 2) - 1e-9).toInt())
        val da = sweep / segs
        val k = 4.0 / 3 * tan(da / 4)
        var th = a0
        for (i in 0 until segs) {
            val th2 = th + da
            bez(sb, cx + rx * (cos(th) - k * sin(th)), cy + ry * (sin(th) + k * cos(th)),
                cx + rx * (cos(th2) + k * sin(th2)), cy + ry * (sin(th2) - k * cos(th2)),
                cx + rx * cos(th2), cy + ry * sin(th2))
            th = th2
        }
        if (kind == 1 || kind == 2) sb.append('Z')
        arcEndX = cx + rx * cos(a0 + sweep); arcEndY = cy + ry * sin(a0 + sweep)
    }

    // ------------------------------------------------------------------ kliping
    /** clipPath baru = [d] DIIRIS dengan kliping sekarang. */
    protected fun clipWith(d: CharSequence, evenOdd: Boolean = false, parent: String? = dc.clip): String {
        val id = nid()
        defs.append("<clipPath id=\"").append(id).append('"')
        if (parent != null) defs.append(" clip-path=\"url(#").append(parent).append(")\"")
        defs.append("><path d=\"").append(d).append('"')
        if (evenOdd) defs.append(" clip-rule=\"evenodd\"")
        defs.append("/></clipPath>")
        return id
    }
    protected fun intersectClip(l: Double, t: Double, r: Double, b: Double) { val sb = StringBuilder(); rectPath(sb, l, t, r, b); dc.clip = clipWith(sb) }
    protected fun excludeClip(l: Double, t: Double, r: Double, b: Double) {
        val sb = StringBuilder("M-1e6 -1e6H1e6V1e6H-1e6Z")
        rectPath(sb, l, t, r, b)
        dc.clip = clipWith(sb, evenOdd = true)
    }

    // ------------------------------------------------------------------ teks
    protected fun family(face: String): String {
        val low = face.lowercase()
        val generic = when {
            low.contains("courier") || low.contains("consolas") || low.contains("mono") || low.contains("lucida console") -> "monospace"
            low.contains("symbol") || low.contains("times") || low.contains("roman") || low.contains("cambria") || low.contains("georgia") ||
                low.contains("garamond") || low.contains("book") || low.contains("palatino") || low.contains("century") || low.contains("mt extra") ||
                (low.contains("serif") && !low.contains("sans")) -> "serif"
            else -> "sans-serif"
        }
        val clean = face.filter { it != '"' && it != '\'' && it != '<' && it != '>' && it != '&' && it >= ' ' }.trim()
        return if (clean.isEmpty() || low.contains("symbol")) generic else "'$clean',$generic"
    }

    private fun esc(s: String, sb: StringBuilder) {
        for (c in s) when {
            c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;")
            c < ' ' || c == '￿' || c == '￾' -> sb.append(' ')
            else -> sb.append(c)
        }
    }

    /**
     * Teks di titik logis ([x],[y]). [dx] = jarak antarhuruf (satuan logis, dari metafile) — huruf ditempatkan persis
     * seperti aslinya walau fontnya pengganti. [rect] = kotak ETO (logis). [advanced] = GM_ADVANCED (ikut transformasi).
     */
    protected fun text(x: Double, y: Double, s0: String, dx: IntArray?, opts: Int, rect: DoubleArray?, advanced: Boolean) {
        if (s0.isEmpty() || dc.rop2 == 11) return
        val f = dc.font
        val faceLow = f.face.lowercase()
        val s = if (faceLow.contains("symbol") || (f.charset == 2 && !faceLow.contains("wingding") && !faceLow.contains("webding"))) Metafile.symbolText(s0)
            else if (faceLow.contains("wingding")) buildString { for (c in s0) append(OxUtil.symbolChar(c, f.face)) } else s0
        var rx = x; var ry = y
        val upd = (dc.textAlign and 1) != 0
        if (upd) { rx = dc.cpx; ry = dc.cpy }
        val ox = X(rx, ry); val oy = Y(rx, ry)
        val ux = X(rx + 1, ry) - ox; val uy = Y(rx + 1, ry) - oy
        val vxv = X(rx, ry + 1) - ox; val vyv = Y(rx, ry + 1) - oy
        val hs = hypot(ux, uy); val vs = hypot(vxv, vyv)
        val emLog = when { f.height < 0 -> -f.height; f.height > 0 -> f.height / 1.12; else -> 12.0 }
        val em = emLog * vs
        if (!(em > px1 * 0.25) || em > 1e6) return
        var ang = -f.esc / 10.0
        if (advanced) ang += Math.toDegrees(atan2(uy, ux))
        val n = s.length
        var pos: DoubleArray? = null
        var total = 0.0
        if (dx != null && dx.size >= n && hs > 0) { pos = DoubleArray(n); for (i in 0 until n) { pos[i] = total; total += dx[i] * hs } }
        val ha = dc.textAlign and 6
        var start = 0.0; var anchor: String? = null
        if (pos != null) start = when (ha) { 2 -> -total; 6 -> -total / 2; else -> 0.0 } else anchor = when (ha) { 2 -> "end"; 6 -> "middle"; else -> null }
        val base = when (dc.textAlign and 24) { 24 -> 0.0; 8 -> -0.22 * em; else -> 0.9 * em }
        // latar: ETO_OPAQUE mengisi kotak; mode OPAQUE mengisi kotak teks
        if ((opts and 2) != 0 && rect != null) {
            val sb = StringBuilder(); rectPath(sb, rect[0], rect[1], rect[2], rect[3])
            body.append("<path d=\"").append(sb).append("\" fill=\"").append(hex(dc.bkColor)).append('"')
            dc.clip?.let { body.append(" clip-path=\"url(#").append(it).append(")\"") }
            body.append("/>")
        } else if (dc.bkMode == 2 && pos != null && total > 0) {
            val g = StringBuilder()
            g.append("<rect x=\""); num(g, start); g.append("\" y=\""); num(g, base - 0.9 * em); g.append("\" width=\""); num(g, total)
            g.append("\" height=\""); num(g, 1.12 * em); g.append("\" fill=\"").append(hex(dc.bkColor)).append('"')
            g.append(" transform=\"translate("); num(g, ox); g.append(' '); num(g, oy); g.append(')')
            if (ang != 0.0) { g.append(" rotate("); num(g, ang); g.append(')') }
            g.append("\"/>")
            if (dc.clip != null) body.append("<g clip-path=\"url(#").append(dc.clip).append(")\">").append(g).append("</g>") else body.append(g)
        }
        var clip = dc.clip
        if ((opts and 4) != 0 && rect != null) { val sb = StringBuilder(); rectPath(sb, rect[0], rect[1], rect[2], rect[3]); clip = clipWith(sb) }
        if (clip != null) body.append("<g clip-path=\"url(#").append(clip).append(")\">")
        body.append("<text transform=\"translate("); num(body, ox); body.append(' '); num(body, oy); body.append(')')
        if (ang != 0.0) { body.append(" rotate("); num(body, ang); body.append(')') }
        body.append("\" x=\"")
        if (pos != null) { for (i in 0 until n) { if (i > 0) body.append(' '); num(body, start + pos[i]) } } else body.append('0')
        body.append("\" y=\""); num(body, base); body.append("\" font-family=\"").append(family(f.face)).append("\" font-size=\""); num(body, em); body.append('"')
        if (f.weight >= 600) body.append(" font-weight=\"bold\"")
        if (f.italic) body.append(" font-style=\"italic\"")
        if (f.underline || f.strike) body.append(" text-decoration=\"").append(if (f.underline) "underline" else "").append(if (f.underline && f.strike) " " else "").append(if (f.strike) "line-through" else "").append('"')
        if (anchor != null) body.append(" text-anchor=\"").append(anchor).append('"')
        body.append(" fill=\"").append(hex(dc.textColor)).append("\" xml:space=\"preserve\">")
        esc(s, body)
        body.append("</text>")
        if (clip != null) body.append("</g>")
        if (upd && dx != null && n > 0) { var adv = 0.0; for (i in 0 until min(n, dx.size)) adv += dx[i]; dc.cpx = rx + adv }
    }

    // ------------------------------------------------------------------ bitmap
    private val imageIds = HashMap<Long, String>()

    /**
     * Gambar [img] ke kotak logis tujuan. Bila bitmap belum dipotong ([Dib.Out.cropped] = false, mis. JPEG di dalam DIB),
     * bagian sumber [sx0],[sy0],[sw0]×[sh0] (piksel, asal kiri-atas) dipotong lewat viewBox.
     */
    protected fun image(img: Dib.Out, dxL: Double, dyL: Double, dwL: Double, dhL: Double, sx0: Double = 0.0, sy0: Double = 0.0, sw0: Double = 0.0, sh0: Double = 0.0) {
        if (dc.rop2 == 11 || dwL == 0.0 || dhL == 0.0) return
        if (body.length + defs.length > 30_000_000) return
        var sxp = 0.0; var syp = 0.0; var sw = img.w.toDouble(); var sh = img.h.toDouble()
        if (!img.cropped && sw0 > 0 && sh0 > 0 && sx0 >= 0 && sy0 >= 0 && sx0 + sw0 <= img.w + 0.5 && sy0 + sh0 <= img.h + 0.5) { sxp = sx0; syp = sy0; sw = sw0; sh = sh0 }
        val p0x = X(dxL, dyL); val p0y = Y(dxL, dyL)
        val pxx = X(dxL + dwL, dyL); val pxy = Y(dxL + dwL, dyL)
        val pyx = X(dxL, dyL + dhL); val pyy = Y(dxL, dyL + dhL)
        track(p0x, p0y); track(pxx + pyx - p0x, pxy + pyy - p0y)
        val key = (img.img.bytes.size.toLong() shl 32) xor img.img.bytes.contentHashCode().toLong()
        val id = imageIds.getOrPut(key) {
            val nid = nid()
            defs.append("<image id=\"").append(nid).append("\" width=\"").append(img.w).append("\" height=\"").append(img.h)
                .append("\" preserveAspectRatio=\"none\" href=\"data:").append(img.img.mime).append(";base64,")
                .append(java.util.Base64.getEncoder().encodeToString(img.img.bytes)).append("\"/>")
            nid
        }
        val clip = dc.clip
        if (clip != null) body.append("<g clip-path=\"url(#").append(clip).append(")\">")
        body.append("<g transform=\"matrix(")
        num(body, (pxx - p0x) / 1000); body.append(' '); num(body, (pxy - p0y) / 1000); body.append(' ')
        num(body, (pyx - p0x) / 1000); body.append(' '); num(body, (pyy - p0y) / 1000); body.append(' ')
        num(body, p0x); body.append(' '); num(body, p0y)
        body.append(")\"><svg width=\"1000\" height=\"1000\" viewBox=\"")
        num(body, sxp); body.append(' '); num(body, syp); body.append(' '); num(body, sw); body.append(' '); num(body, sh)
        body.append("\" preserveAspectRatio=\"none\"><use href=\"#").append(id).append("\"/></svg></g>")
        if (clip != null) body.append("</g>")
    }

    /** Bagian sumber blit (piksel, asal kiri-atas) dari koordinat GDI; [fromBottom] = asal kiri-bawah (fungsi DIB, bitmap bawah→atas). */
    protected fun srcRect(dims: IntArray, x: Double, y: Double, w: Double, h: Double, fromBottom: Boolean): IntArray? {
        if (!(w >= 1 && h >= 1)) return null
        val yy = if (fromBottom && dims[2] == 0) dims[1] - y - h else y
        val r = intArrayOf(x.roundToInt(), yy.roundToInt(), w.roundToInt(), h.roundToInt())
        return if (r[0] == 0 && r[1] == 0 && r[2] >= dims[0] && r[3] >= dims[1]) null else r
    }

    /** Pola bitmap untuk kuas (pattern brush), satuan piksel perangkat. */
    protected fun patternOf(img: Dib.Out): String {
        val id = nid()
        defs.append("<pattern id=\"").append(id).append("\" patternUnits=\"userSpaceOnUse\" width=\"").append(img.w).append("\" height=\"").append(img.h)
            .append("\"><image width=\"").append(img.w).append("\" height=\"").append(img.h).append("\" preserveAspectRatio=\"none\" href=\"data:")
            .append(img.img.mime).append(";base64,").append(java.util.Base64.getEncoder().encodeToString(img.img.bytes)).append("\"/></pattern>")
        return id
    }

    protected fun svg(vx: Double, vy: Double, vw: Double, vh: Double): String {
        val sb = StringBuilder(body.length + defs.length + 256)
        val nw = max(1, (vw / px1).roundToInt()); val nh = max(1, (vh / px1).roundToInt())
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(nw).append("\" height=\"").append(nh).append("\" viewBox=\"")
        num(sb, vx); sb.append(' '); num(sb, vy); sb.append(' '); num(sb, vw); sb.append(' '); num(sb, vh)
        sb.append("\" preserveAspectRatio=\"none\" overflow=\"hidden\">")
        if (defs.isNotEmpty()) sb.append("<defs>").append(defs).append("</defs>")
        sb.append(body).append("</svg>")
        return sb.toString()
    }

    companion object {
        val STOCK_BRUSH = arrayOf(Brush(0, 0xFFFFFF, 0), Brush(0, 0xC0C0C0, 0), Brush(0, 0x808080, 0), Brush(0, 0x404040, 0), Brush(0, 0, 0), Brush(1, 0, 0))
        val WHITE_PEN = Pen(0, 0.0, 0xFFFFFF, false); val BLACK_PEN = Pen(0, 0.0, 0, false); val NULL_PEN = Pen(5, 0.0, 0, false)
        val DEFAULT_FONT = Font(-16.0, 0.0, 400, false, false, false, "Arial", 0)
    }

    protected fun selectStock(i: Int) {
        when (i) {
            in 0..5 -> dc.brush = STOCK_BRUSH[i]
            6 -> dc.pen = WHITE_PEN; 7 -> dc.pen = BLACK_PEN; 8 -> dc.pen = NULL_PEN
            10, 11, 12, 13, 14, 16, 17 -> dc.font = DEFAULT_FONT
            18 -> dc.brush = STOCK_BRUSH[0]; 19 -> dc.pen = BLACK_PEN
        }
    }
}

// -------------------------------------------------------------------------------------------- EMF
private class EmfPlayer(private val b: ByteArray, maxPx: Int) : GdiSvg(maxPx) {
    private var ppmx = 96 / 25.4
    private var ppmy = 96 / 25.4
    private val objs = HashMap<Int, Any>()
    private var plus = 0
    private var drew = false

    override fun mapping() {
        val d = dc
        when (d.mapMode) {
            2 -> { sx = ppmx * 0.1; sy = -ppmy * 0.1 }
            3 -> { sx = ppmx * 0.01; sy = -ppmy * 0.01 }
            4 -> { sx = ppmx * 0.254; sy = -ppmy * 0.254 }
            5 -> { sx = ppmx * 0.0254; sy = -ppmy * 0.0254 }
            6 -> { sx = ppmx * 25.4 / 1440; sy = -ppmy * 25.4 / 1440 }
            7, 8 -> {
                var ax = if (d.wex != 0.0) d.vex / d.wex else 1.0; var ay = if (d.wey != 0.0) d.vey / d.wey else 1.0
                if (d.mapMode == 7) { val s = min(abs(ax), abs(ay)); ax = if (ax < 0) -s else s; ay = if (ay < 0) -s else s }
                sx = ax; sy = ay
            }
            else -> { sx = 1.0; sy = 1.0 }
        }
    }

    fun play(): String? {
        val n = b.size
        if (n < 88) return null
        val hdr = Bin.i32(b, 4)
        val bL = Bin.i32(b, 8); val bT = Bin.i32(b, 12); val bR = Bin.i32(b, 16); val bB = Bin.i32(b, 20)
        val fL = Bin.i32(b, 24); val fT = Bin.i32(b, 28); val fR = Bin.i32(b, 32); val fB = Bin.i32(b, 36)
        val devX = Bin.i32(b, 72); val devY = Bin.i32(b, 76); val mmX = Bin.i32(b, 80); val mmY = Bin.i32(b, 84)
        if (devX > 0 && mmX > 0) ppmx = devX.toDouble() / mmX
        if (devY > 0 && mmY > 0) ppmy = devY.toDouble() / mmY
        if (hdr >= 108 && n >= 108) {
            // szlMicrometers (lebih presisi) — hanya bila sungguh ada: ukuran rekaman kepala bisa memuat teks deskripsi
            val umX = Bin.i32(b, 100); val umY = Bin.i32(b, 104)
            if (devX > 0 && mmX > 0 && abs(umX - mmX * 1000.0) <= mmX * 120.0 + 1000) ppmx = devX * 1000.0 / umX
            if (devY > 0 && mmY > 0 && abs(umY - mmY * 1000.0) <= mmY * 120.0 + 1000) ppmy = devY * 1000.0 / umY
        }
        if (!(ppmx > 0.01 && ppmx < 1000)) ppmx = 96 / 25.4
        if (!(ppmy > 0.01 && ppmy < 1000)) ppmy = 96 / 25.4
        var vx = fL * 0.01 * ppmx; var vy = fT * 0.01 * ppmy
        var vw = (fR - fL) * 0.01 * ppmx; var vh = (fB - fT) * 0.01 * ppmy
        if (!(vw > 0.5 && vh > 0.5)) { vx = bL.toDouble(); vy = bT.toDouble(); vw = (bR - bL + 1).toDouble(); vh = (bB - bT + 1).toDouble() }
        // gambar kosong (bingkai & batas nol): Office pun tidak menampilkan apa-apa
        if (!(vw > 0 && vh > 0)) return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        px1 = ppmx * 25.4 / 96
        hatchScale = max(1.0, px1)
        mapping()
        var o = 0; var count = 0
        while (o + 8 <= n && count++ < 400000) {
            val type = Bin.i32(b, o); val size = Bin.i32(b, o + 4)
            if (size < 8 || o.toLong() + size > n) break
            if (type == 14) break
            if (type != 27 && type != 54) flushLine()
            try { record(type, o, size) } catch (_: RuntimeException) {}
            o += size
            if (body.length > 28_000_000) break
        }
        flushLine()
        if (body.isEmpty() && plus > 0) return null                // hanya EMF+ (tidak didukung) → tampilkan pengganti
        return svg(vx, vy, vw, vh)
    }

    private fun i(o: Int) = Bin.i32(b, o)
    private fun d(o: Int) = Bin.i32(b, o).toDouble()

    private fun points(o: Int, cnt: Int, small: Boolean): DoubleArray {
        val c = min(cnt, (b.size - o) / (if (small) 4 else 8)).coerceAtLeast(0)
        val a = DoubleArray(c * 2)
        for (k in 0 until c) {
            if (small) { a[2 * k] = Bin.i16(b, o + 4 * k).toDouble(); a[2 * k + 1] = Bin.i16(b, o + 4 * k + 2).toDouble() }
            else { a[2 * k] = d(o + 8 * k); a[2 * k + 1] = d(o + 8 * k + 4) }
        }
        return a
    }

    private fun poly(type: Int, o: Int) {
        val small = type >= 85
        val cnt = i(o + 24)
        val p = points(o + 28, cnt, small)
        if (p.size < 2) return
        val base = if (small) type - 83 else type   // 85..89 → 2..6
        val sb = StringBuilder()
        when (base) {
            2 -> { // PolyBezier
                sb.append('M'); pt(sb, p[0], p[1])
                var k = 2; while (k + 5 < p.size) { bez(sb, p[k], p[k + 1], p[k + 2], p[k + 3], p[k + 4], p[k + 5]); k += 6 }
                shape(sb, fill = false, stroke = true, open = true)
            }
            3 -> { sb.append('M'); pt(sb, p[0], p[1]); var k = 2; while (k + 1 < p.size) { sb.append('L'); pt(sb, p[k], p[k + 1]); k += 2 }; sb.append('Z'); shape(sb, true, true) }
            4 -> { sb.append('M'); pt(sb, p[0], p[1]); var k = 2; while (k + 1 < p.size) { sb.append('L'); pt(sb, p[k], p[k + 1]); k += 2 }; shape(sb, false, true, open = true) }
            5 -> { // PolyBezierTo dari titik sekarang
                if (inPath) { if (!pathOpen) { path.append('M'); pt(path, dc.cpx, dc.cpy); pathOpen = true } }
                else { sb.append('M'); pt(sb, dc.cpx, dc.cpy) }
                val tgt = if (inPath) path else sb
                var k = 0; while (k + 5 < p.size) { bez(tgt, p[k], p[k + 1], p[k + 2], p[k + 3], p[k + 4], p[k + 5]); dc.cpx = p[k + 4]; dc.cpy = p[k + 5]; k += 6 }
                if (!inPath) emit(sb, false, true)
            }
            6 -> { // PolyLineTo
                if (inPath) { var k = 0; while (k + 1 < p.size) { lineTo(p[k], p[k + 1]); k += 2 } }
                else { sb.append('M'); pt(sb, dc.cpx, dc.cpy); var k = 0; while (k + 1 < p.size) { sb.append('L'); pt(sb, p[k], p[k + 1]); dc.cpx = p[k]; dc.cpy = p[k + 1]; k += 2 }; emit(sb, false, true) }
            }
        }
    }

    private fun polyPoly(type: Int, o: Int) {
        val small = type >= 90
        val nPolys = i(o + 24); val cnt = i(o + 28)
        if (nPolys <= 0 || nPolys > 100000) return
        val counts = IntArray(nPolys) { i(o + 32 + 4 * it) }
        val p = points(o + 32 + 4 * nPolys, cnt, small)
        val closed = type == 8 || type == 91
        val sb = StringBuilder()
        var k = 0
        for (c in counts) {
            if (c <= 0) continue
            if (k + 1 >= p.size) break
            sb.append('M'); pt(sb, p[k], p[k + 1]); k += 2
            for (j in 1 until c) { if (k + 1 >= p.size) break; sb.append('L'); pt(sb, p[k], p[k + 1]); k += 2 }
            if (closed) sb.append('Z')
        }
        shape(sb, closed, true, open = !closed)
    }

    private fun polyDraw(type: Int, o: Int) {
        val small = type == 92
        val cnt = i(o + 24)
        val p = points(o + 28, cnt, small)
        val tOff = o + 28 + cnt * (if (small) 4 else 8)
        val sb = if (inPath) path else StringBuilder().also { it.append('M'); pt(it, dc.cpx, dc.cpy) }
        var k = 0
        while (k < cnt && 2 * k + 1 < p.size && tOff + k < b.size) {
            val t = b[tOff + k].toInt() and 255
            var last = k
            when (t and 6) {
                6 -> { sb.append('M'); pt(sb, p[2 * k], p[2 * k + 1]); k++ }
                2 -> { sb.append('L'); pt(sb, p[2 * k], p[2 * k + 1]); k++ }
                4 -> { if (2 * k + 5 < p.size) { bez(sb, p[2 * k], p[2 * k + 1], p[2 * k + 2], p[2 * k + 3], p[2 * k + 4], p[2 * k + 5]); last = k + 2 }; k += 3 }
                else -> k++
            }
            if ((t and 1) != 0 || (tOff + last < b.size && (b[tOff + last].toInt() and 1) != 0)) sb.append('Z')
            if (2 * last + 1 < p.size) { dc.cpx = p[2 * last]; dc.cpy = p[2 * last + 1] }
        }
        if (inPath) pathOpen = true else emit(sb, false, true)
    }

    private fun rgnRects(o: Int, len: Int): List<DoubleArray> {
        if (len < 32 || o + 32 > b.size) return emptyList()
        val hs = max(32, i(o)); val cnt = i(o + 8)
        val out = ArrayList<DoubleArray>()
        var k = 0
        while (k < cnt && o + hs + 16 * k + 16 <= min(b.size, o + len)) {
            val r = o + hs + 16 * k
            out.add(doubleArrayOf(d(r), d(r + 4), d(r + 8), d(r + 12))); k++
        }
        return out
    }

    private fun dib(o: Int, offBmi: Int, cbBmi: Int, offBits: Int, cbBits: Int, usage: Int, mode: Int, key: Int = 0,
                    sx: Double = 0.0, sy: Double = 0.0, sw: Double = 0.0, sh: Double = 0.0, fromBottom: Boolean = false): Dib.Out? {
        if (cbBmi <= 0 || offBmi <= 0) return null
        val dims = Dib.dims(b, o + offBmi) ?: return null
        return Dib.decode(b, o + offBmi, if (offBits > 0) o + offBits else -1, maxPx, mode, key, usage == 1, cbBits, srcRect(dims, sx, sy, sw, sh, fromBottom))
    }

    private fun ropMode(rop: Int): Int = when (rop) {
        // ikon OLE klasik: topeng (AND) lalu gambar (XOR/OR) — warna hitam di gambar = tembus
        0x008800C6 -> Dib.KEY_WHITE; 0x00EE0086, 0x00660046 -> Dib.KEY_BLACK; 0x00330008 -> Dib.INVERT
        else -> Dib.COPY
    }

    /** Blit tanpa bitmap sumber: isi dengan kuas / hitam / putih. */
    private fun patBlt(rop: Int, x: Double, y: Double, w: Double, h: Double) {
        val sb = StringBuilder(); rectPath(sb, x, y, x + w, y + h)
        when (rop) {
            0x00F00021 -> { val save = dc.pen; dc.pen = NULL_PEN; emit(sb, true, false); dc.pen = save }
            0x00000042 -> body.append("<path d=\"").append(sb).append("\" fill=\"#000000\"").append(dc.clip?.let { " clip-path=\"url(#$it)\"" } ?: "").append("/>")
            0x00FF0062 -> body.append("<path d=\"").append(sb).append("\" fill=\"#ffffff\"").append(dc.clip?.let { " clip-path=\"url(#$it)\"" } ?: "").append("/>")
        }
    }

    private fun record(type: Int, o: Int, size: Int) {
        when (type) {
            9 -> { dc.wex = d(o + 8); dc.wey = d(o + 12); mapping() }
            10 -> { dc.wox = d(o + 8); dc.woy = d(o + 12) }
            11 -> { dc.vex = d(o + 8); dc.vey = d(o + 12); mapping() }
            12 -> { dc.vox = d(o + 8); dc.voy = d(o + 12) }
            31 -> { val xn = d(o + 8); val xd = d(o + 12); val yn = d(o + 16); val yd = d(o + 20); if (xd != 0.0 && yd != 0.0) { dc.vex = dc.vex * xn / xd; dc.vey = dc.vey * yn / yd; mapping() } }
            32 -> { val xn = d(o + 8); val xd = d(o + 12); val yn = d(o + 16); val yd = d(o + 20); if (xd != 0.0 && yd != 0.0) { dc.wex = dc.wex * xn / xd; dc.wey = dc.wey * yn / yd; mapping() } }
            17 -> { dc.mapMode = i(o + 8); mapping() }
            18 -> dc.bkMode = i(o + 8)
            19 -> dc.fillMode = i(o + 8)
            20 -> dc.rop2 = i(o + 8)
            22 -> dc.textAlign = i(o + 8)
            24 -> dc.textColor = i(o + 8)
            25 -> dc.bkColor = i(o + 8)
            57 -> dc.arcCw = i(o + 8) == 2
            27 -> moveTo(d(o + 8), d(o + 12))
            54 -> lineTo(d(o + 8), d(o + 12))
            33 -> saveDC()
            34 -> restoreDC(i(o + 8))
            35 -> { dc.m11 = Bin.f32(b, o + 8); dc.m12 = Bin.f32(b, o + 12); dc.m21 = Bin.f32(b, o + 16); dc.m22 = Bin.f32(b, o + 20); dc.mdx = Bin.f32(b, o + 24); dc.mdy = Bin.f32(b, o + 28) }
            36 -> {
                val a11 = Bin.f32(b, o + 8); val a12 = Bin.f32(b, o + 12); val a21 = Bin.f32(b, o + 16); val a22 = Bin.f32(b, o + 20); val adx = Bin.f32(b, o + 24); val ady = Bin.f32(b, o + 28)
                when (i(o + 32)) {
                    1 -> { dc.m11 = 1.0; dc.m12 = 0.0; dc.m21 = 0.0; dc.m22 = 1.0; dc.mdx = 0.0; dc.mdy = 0.0 }
                    2 -> mul(a11, a12, a21, a22, adx, ady, dc.m11, dc.m12, dc.m21, dc.m22, dc.mdx, dc.mdy)
                    3 -> mul(dc.m11, dc.m12, dc.m21, dc.m22, dc.mdx, dc.mdy, a11, a12, a21, a22, adx, ady)
                    4 -> { dc.m11 = a11; dc.m12 = a12; dc.m21 = a21; dc.m22 = a22; dc.mdx = adx; dc.mdy = ady }
                }
            }
            37 -> { val h = i(o + 8); if (h < 0) selectStock(h and 0x7FFFFFFF) else when (val ob = objs[h]) { is Pen -> dc.pen = ob; is Brush -> dc.brush = ob; is Font -> dc.font = ob } }
            40 -> objs.remove(i(o + 8))
            38 -> { val w = d(o + 16); objs[i(o + 8)] = Pen(i(o + 12), w, i(o + 24), w > 0) }
            95 -> {
                val style = i(o + 28); val w = d(o + 32); val bs = i(o + 36)
                val nE = i(o + 48)
                val dashes = if ((style and 0xF) == 7 && nE in 1..64) DoubleArray(nE) { d(o + 52 + 4 * it) } else null
                objs[i(o + 8)] = Pen(if (bs == 1) 5 else style, w, i(o + 40), (style and 0xF0000) == 0x10000, dashes)
            }
            39 -> objs[i(o + 8)] = Brush(i(o + 12), i(o + 16), i(o + 20))
            93, 94 -> {
                val usage = i(o + 12); val offBmi = i(o + 16); val offBits = i(o + 24)
                val mono = if (type == 93 || usage == 2) Dib.ones(b, o + offBmi, if (offBits > 0) o + offBits else -1, usage == 1) else null
                objs[i(o + 8)] = if (mono != null) Brush(3, 0, 0, null, mono) else {
                    val img = dib(o, offBmi, i(o + 20), offBits, i(o + 28), usage, Dib.COPY)
                    if (img != null) Brush(5, 0x808080, 0, patternOf(img)) else Brush(0, 0x808080, 0)
                }
            }
            82 -> {
                val face = StringBuilder()
                for (k in 0 until 32) { val c = Bin.u16(b, o + 40 + 2 * k); if (c == 0) break; face.append(c.toChar()) }
                objs[i(o + 8)] = Font(d(o + 12), d(o + 20), i(o + 28), b[o + 32].toInt() != 0, b[o + 33].toInt() != 0, b[o + 34].toInt() != 0, face.toString(), b[o + 35].toInt() and 255)
            }
            49 -> objs[i(o + 8)] = Any()
            2, 3, 4, 5, 6, 85, 86, 87, 88, 89 -> { drew = true; poly(type, o) }
            7, 8, 90, 91 -> { drew = true; polyPoly(type, o) }
            56, 92 -> { drew = true; polyDraw(type, o) }
            42 -> { val sb = StringBuilder(); ellipsePath(sb, d(o + 8), d(o + 12), d(o + 16), d(o + 20)); shape(sb, true, true) }
            43 -> { val sb = StringBuilder(); rectPath(sb, d(o + 8), d(o + 12), d(o + 16), d(o + 20)); shape(sb, true, true) }
            44 -> { val sb = StringBuilder(); roundRectPath(sb, d(o + 8), d(o + 12), d(o + 16), d(o + 20), d(o + 24), d(o + 28)); shape(sb, true, true) }
            45, 46, 47, 55 -> {
                val kind = when (type) { 45 -> 0; 46 -> 1; 47 -> 2; else -> 3 }
                if (kind == 3) {
                    val tgt = if (inPath) path else StringBuilder().also { it.append('M'); pt(it, dc.cpx, dc.cpy) }
                    if (inPath && !pathOpen) { path.append('M'); pt(path, dc.cpx, dc.cpy) }
                    arcPath(tgt, d(o + 8), d(o + 12), d(o + 16), d(o + 20), d(o + 24), d(o + 28), d(o + 32), d(o + 36), 3)
                    dc.cpx = arcEndX; dc.cpy = arcEndY
                    if (inPath) pathOpen = true else emit(tgt, false, true)
                } else {
                    val sb = StringBuilder(); arcPath(sb, d(o + 8), d(o + 12), d(o + 16), d(o + 20), d(o + 24), d(o + 28), d(o + 32), d(o + 36), kind)
                    shape(sb, kind != 0, true, open = kind == 0)
                }
            }
            41 -> { // AngleArc
                val cx = d(o + 8); val cy = d(o + 12); val r = Bin.i32(b, o + 16).toDouble()
                val a0 = Math.toRadians(Bin.f32(b, o + 20)); val sw = Math.toRadians(Bin.f32(b, o + 24))
                val xs = cx + r * cos(a0); val ys = cy - r * sin(a0); val xe = cx + r * cos(a0 + sw); val ye = cy - r * sin(a0 + sw)
                lineTo(xs, ys); flushLine()
                val save = dc.arcCw; dc.arcCw = sw < 0
                val sb = StringBuilder(); arcPath(sb, cx - r, cy - r, cx + r, cy + r, xs, ys, xe, ye, 0); dc.arcCw = save
                shape(sb, false, true, open = true); dc.cpx = xe; dc.cpy = ye
            }
            59 -> { inPath = true; path.setLength(0); pathOpen = false }
            60 -> inPath = false
            61 -> if (inPath) { path.append('Z'); pathOpen = false }
            68 -> { inPath = false; path.setLength(0) }
            62, 63, 64 -> { inPath = false; if (path.isNotEmpty()) emit(path.toString(), type != 64, type != 62); path.setLength(0) }
            67 -> {
                inPath = false
                if (path.isNotEmpty()) {
                    val mode = i(o + 8)
                    dc.clip = when (mode) { 5 -> clipWith(path.toString(), dc.fillMode == 1, parent = null); 4 -> { val sb = StringBuilder("M-1e6 -1e6H1e6V1e6H-1e6Z").append(path); clipWith(sb, true) }; else -> clipWith(path.toString(), dc.fillMode == 1) }
                }
                path.setLength(0)
            }
            30 -> intersectClip(d(o + 8), d(o + 12), d(o + 16), d(o + 20))
            29 -> excludeClip(d(o + 8), d(o + 12), d(o + 16), d(o + 20))
            75 -> {
                val cb = i(o + 8); val mode = i(o + 12)
                if (cb <= 0) { if (mode == 5) dc.clip = null }
                else {
                    val rects = rgnRects(o + 16, cb)
                    val sb = StringBuilder()
                    for (r in rects) { sb.append('M'); num(sb, r[0]); sb.append(' '); num(sb, r[1]); sb.append('H'); num(sb, r[2]); sb.append('V'); num(sb, r[3]); sb.append('H'); num(sb, r[0]); sb.append('Z') }
                    if (rects.isEmpty()) { if (mode == 5) dc.clip = null }
                    else dc.clip = when (mode) { 5, 2 -> clipWith(sb, parent = null); 4 -> clipWith(StringBuilder("M-1e6 -1e6H1e6V1e6H-1e6Z").append(sb), true); else -> clipWith(sb) }
                }
            }
            71, 74 -> { // FillRgn / PaintRgn (satuan logis)
                val cb = i(o + 24)
                val rects = rgnRects(o + if (type == 71) 32 else 28, cb)
                val save = dc.brush
                if (type == 71) (objs[i(o + 28)] as? Brush)?.let { dc.brush = it } ?: run { val h = i(o + 28); if (h < 0) selectStock(h and 0x7FFFFFFF) }
                val sb = StringBuilder(); for (r in rects) rectPath(sb, r[0], r[1], r[2], r[3])
                val sp = dc.pen; dc.pen = NULL_PEN; emit(sb, true, false); dc.pen = sp; dc.brush = save
            }
            72 -> { // FrameRgn
                val cb = i(o + 24); val br = objs[i(o + 28)] as? Brush
                val rects = rgnRects(o + 40, cb)
                val sb = StringBuilder(); for (r in rects) rectPath(sb, r[0], r[1], r[2], r[3])
                val sp = dc.pen; dc.pen = Pen(0, 0.0, br?.color ?: 0, false); emit(sb, false, true); dc.pen = sp
            }
            15 -> { val x = d(o + 8); val y = d(o + 12); val c = i(o + 16); val sb = StringBuilder(); rectPath(sb, x, y, x + 1, y + 1); body.append("<path d=\"").append(sb).append("\" fill=\"").append(hex(c)).append("\"/>") }
            76, 77, 114, 116 -> {
                val x = d(o + 24); val y = d(o + 28); val w = d(o + 32); val h = d(o + 36); val rop = i(o + 40)
                val xSrc = d(o + 44); val ySrc = d(o + 48)
                val s11 = Bin.f32(b, o + 52); val s22 = Bin.f32(b, o + 64); val sdx = Bin.f32(b, o + 68); val sdy = Bin.f32(b, o + 72)
                val usage = i(o + 80); val offBmi = i(o + 84); val cbBmi = i(o + 88); val offBits = i(o + 92); val cbBits = i(o + 96)
                if (cbBmi <= 0) { patBlt(rop, x, y, w, h); return }
                val mode = when (type) { 114 -> if (((rop ushr 24) and 255) and 1 != 0) Dib.ALPHA else Dib.COPY; 116 -> Dib.KEY_COLOR; else -> ropMode(rop) }
                val cxs = if (type == 76 || size < 108) w else d(o + 100); val cys = if (type == 76 || size < 108) h else d(o + 104)
                val ssx = if (s11 != 0.0) s11 else 1.0; val ssy = if (s22 != 0.0) s22 else 1.0
                val srcX = xSrc * ssx + sdx; val srcY = ySrc * ssy + sdy
                val img = dib(o, offBmi, cbBmi, offBits, cbBits, usage, mode, rop, srcX, srcY, abs(cxs * ssx), abs(cys * ssy)) ?: return
                image(img, x, y, w, h, srcX, srcY, abs(cxs * ssx), abs(cys * ssy))
                if (type == 114) { val ca = (rop ushr 16) and 255; if (ca in 0..254) { /* alfa konstan: tidak umum di dokumen */ } }
                drew = true
            }
            81 -> { // StretchDIBits
                val x = d(o + 24); val y = d(o + 28); val xSrc = d(o + 32); val ySrc = d(o + 36); val cxSrc = d(o + 40); val cySrc = d(o + 44)
                val offBmi = i(o + 48); val cbBmi = i(o + 52); val offBits = i(o + 56); val cbBits = i(o + 60); val usage = i(o + 64); val rop = i(o + 68)
                val w = d(o + 72); val h = d(o + 76)
                if (cbBmi <= 0) { patBlt(rop, x, y, w, h); return }
                val img = dib(o, offBmi, cbBmi, offBits, cbBits, usage, ropMode(rop), 0, xSrc, ySrc, cxSrc, cySrc, fromBottom = true) ?: return
                image(img, x, y, w, h); drew = true
            }
            80 -> { // SetDIBitsToDevice
                val x = d(o + 24); val y = d(o + 28); val xSrc = d(o + 32); val ySrc = d(o + 36); val cx = d(o + 40); val cy = d(o + 44)
                val img = dib(o, i(o + 48), i(o + 52), i(o + 56), i(o + 60), i(o + 64), Dib.COPY, 0, xSrc, ySrc, cx, cy, fromBottom = true) ?: return
                image(img, x, y, cx, cy); drew = true
            }
            83, 84 -> {
                val gm = i(o + 24)
                val rx = d(o + 36); val ry = d(o + 40); val nCh = i(o + 44); val offStr = i(o + 48); val opts = i(o + 52)
                val rect = doubleArrayOf(d(o + 56), d(o + 60), d(o + 64), d(o + 68))
                val offDx = i(o + 72)
                if (nCh <= 0 || nCh > 8192 || offStr <= 0) {
                    if ((opts and 2) != 0) text(rx, ry, " ", null, opts, rect, gm == 2)
                    return
                }
                if ((opts and 0x10) != 0) return                    // ETO_GLYPH_INDEX: indeks glif, bukan karakter
                val cs = metaCharset(dc.font.charset, dc.font.face)
                val s = if (type == 84) { val sb = StringBuilder(nCh); for (k in 0 until nCh) { val p = o + offStr + 2 * k; if (p + 1 >= b.size) break; sb.append(Bin.u16(b, p).toChar()) }; sb.toString() }
                        else String(b, o + offStr, min(nCh, b.size - o - offStr), cs)
                val pdy = (opts and 0x2000) != 0
                val dx = if (offDx > 0 && o + offDx + 4 * nCh * (if (pdy) 2 else 1) <= b.size) IntArray(nCh) { k -> i(o + offDx + 4 * (if (pdy) 2 * k else k)) }.let { if (type == 83) dxPerChar(s, cs, it) else it } else null
                text(rx, ry, s, dx, opts, rect, gm == 2); drew = true
            }
            118 -> { // GradientFill
                val nV = i(o + 24); val nT = i(o + 28); val mode = i(o + 32)
                if (nV !in 1..10000 || nT !in 1..10000) return
                val vx = DoubleArray(nV) { d(o + 36 + 16 * it) }; val vy = DoubleArray(nV) { d(o + 40 + 16 * it) }
                val col = IntArray(nV) { val p = o + 44 + 16 * it; ((Bin.u16(b, p) shr 8) shl 16) or ((Bin.u16(b, p + 2) shr 8) shl 8) or (Bin.u16(b, p + 4) shr 8) }
                val mo = o + 36 + 16 * nV
                fun rgb(c: Int) = "#" + Integer.toHexString(c or 0x1000000).substring(1)
                for (t in 0 until nT) {
                    if (mode == 2) {
                        val a = i(mo + 12 * t); val bb = i(mo + 12 * t + 4); val c = i(mo + 12 * t + 8)
                        if (a !in 0 until nV || bb !in 0 until nV || c !in 0 until nV) continue
                        val avg = OxUtil.mix(OxUtil.mix(col[a], col[bb], 0.5f), col[c], 1f / 3)
                        val sb = StringBuilder("M"); pt(sb, vx[a], vy[a]); sb.append('L'); pt(sb, vx[bb], vy[bb]); sb.append('L'); pt(sb, vx[c], vy[c]); sb.append('Z')
                        body.append("<path d=\"").append(sb).append("\" fill=\"").append(rgb(avg)).append('"').append(dc.clip?.let { " clip-path=\"url(#$it)\"" } ?: "").append("/>")
                    } else {
                        val a = i(mo + 8 * t); val bb = i(mo + 8 * t + 4)
                        if (a !in 0 until nV || bb !in 0 until nV) continue
                        val id = nid()
                        val g = StringBuilder("<linearGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" x1=\"")
                        num(g, X(vx[a], vy[a])); g.append("\" y1=\""); num(g, Y(vx[a], vy[a])); g.append("\" x2=\"")
                        if (mode == 0) { num(g, X(vx[bb], vy[a])); g.append("\" y2=\""); num(g, Y(vx[bb], vy[a])) } else { num(g, X(vx[a], vy[bb])); g.append("\" y2=\""); num(g, Y(vx[a], vy[bb])) }
                        g.append("\"><stop offset=\"0\" stop-color=\"").append(rgb(col[a])).append("\"/><stop offset=\"1\" stop-color=\"").append(rgb(col[bb])).append("\"/></linearGradient>")
                        defs.append(g)
                        val sb = StringBuilder(); rectPath(sb, vx[a], vy[a], vx[bb], vy[bb])
                        body.append("<path d=\"").append(sb).append("\" fill=\"url(#").append(id).append(")\"").append(dc.clip?.let { " clip-path=\"url(#$it)\"" } ?: "").append("/>")
                    }
                }
                drew = true
            }
            70 -> if (size >= 16 && i(o + 12) == 0x2B464D45) plus++   // komentar "EMF+"
        }
    }

    private fun mul(a11: Double, a12: Double, a21: Double, a22: Double, adx: Double, ady: Double, b11: Double, b12: Double, b21: Double, b22: Double, bdx: Double, bdy: Double) {
        dc.m11 = a11 * b11 + a12 * b21; dc.m12 = a11 * b12 + a12 * b22
        dc.m21 = a21 * b11 + a22 * b21; dc.m22 = a21 * b12 + a22 * b22
        dc.mdx = adx * b11 + ady * b21 + bdx; dc.mdy = adx * b12 + ady * b22 + bdy
    }

}

/** Charset teks ANSI metafile; DEFAULT_CHARSET (1) ditebak dari nama font (dokumen CJK). */
internal fun metaCharset(cs0: Int, face: String = ""): java.nio.charset.Charset {
    var cs = cs0
    if (cs == 1 || cs == 0) {
        val f = face.lowercase()
        if (cs == 1 || f.any { it.code > 0x7F }) cs = when {
            listOf("yahei", "simsun", "simhei", "nsimsun", "fangsong", "kaiti", "dengxian", "宋体", "黑体").any { f.contains(it) } -> 134
            listOf("mingliu", "jhenghei", "pmingliu").any { f.contains(it) } -> 136
            listOf("ms gothic", "ms mincho", "ms pgothic", "meiryo", "yu gothic", "ms ui gothic").any { f.contains(it) } -> 128
            listOf("gulim", "batang", "dotum", "malgun", "gungsuh").any { f.contains(it) } -> 129
            else -> cs0
        }
    }
    return metaCharsetOf(cs)
}

/** Jarak antarhuruf per BYTE (teks ANSI multibyte: GBK, Shift_JIS…) → per karakter hasil dekode. */
internal fun dxPerChar(s: String, cs: java.nio.charset.Charset, raw: IntArray): IntArray {
    if (raw.size == s.length) return raw
    val out = IntArray(s.length); var k = 0
    for (i in s.indices) { val n = s[i].toString().toByteArray(cs).size.coerceAtLeast(1); var sum = 0; repeat(n) { if (k < raw.size) sum += raw[k++] }; out[i] = sum }
    return out
}

private fun metaCharsetOf(cs: Int): java.nio.charset.Charset = try {
    java.nio.charset.Charset.forName(when (cs) { 128 -> "Shift_JIS"; 129 -> "EUC-KR"; 134 -> "GBK"; 136 -> "Big5"; 161 -> "windows-1253"; 162 -> "windows-1254"
        177 -> "windows-1255"; 178 -> "windows-1256"; 186 -> "windows-1257"; 204 -> "windows-1251"; 222 -> "x-windows-874"; 238 -> "windows-1250"; else -> "windows-1252" })
} catch (_: Exception) { Charsets.ISO_8859_1 }

// -------------------------------------------------------------------------------------------- WMF
private class WmfPlayer(private val b: ByteArray, maxPx: Int) : GdiSvg(maxPx) {
    private var fx = 0.0; private var fy = 0.0; private var fw = 1.0; private var fh = 1.0
    private var framed = false
    private var objs = arrayOfNulls<Any>(32)

    override fun mapping() {
        sx = if (dc.wex != 0.0) fw / dc.wex else 1.0
        sy = if (dc.wey != 0.0) fh / dc.wey else 1.0
        dc.vox = fx; dc.voy = fy
    }

    fun play(): String? {
        val n = b.size
        var o = 0
        var inch = 0
        if (Bin.i32(b, 0) == 0x9AC6CDD7.toInt()) {
            val l = Bin.i16(b, 6); val t = Bin.i16(b, 8); val r = Bin.i16(b, 10); val bt = Bin.i16(b, 12)
            inch = Bin.u16(b, 14)
            fx = min(l, r).toDouble(); fy = min(t, bt).toDouble(); fw = abs(r - l).toDouble(); fh = abs(bt - t).toDouble()
            framed = fw > 0 && fh > 0
            o = 22
        }
        if (o + 18 > n) return null
        val hdrWords = Bin.u16(b, o + 2)
        val nObj = Bin.u16(b, o + 10)
        objs = arrayOfNulls(max(32, min(nObj, 65535)))
        o += max(9, hdrWords) * 2
        // pra-pindai: EMF tertanam (Office menyimpan salinan EMF di komentar), jendela pertama
        var wOrg: DoubleArray? = null; var wExt: DoubleArray? = null
        val emf = ByteArrayOutputStream()
        var q = o
        while (q + 6 <= n) {
            val sz = Bin.i32(b, q); val fn = Bin.u16(b, q + 4)
            if (sz < 3 || q.toLong() + sz.toLong() * 2 > n) break
            if (fn == 0) break
            val p = q + 6
            when (fn) {
                0x020B -> if (wOrg == null) wOrg = doubleArrayOf(Bin.i16(b, p + 2).toDouble(), Bin.i16(b, p).toDouble())
                0x020C -> if (wExt == null) wExt = doubleArrayOf(Bin.i16(b, p + 2).toDouble(), Bin.i16(b, p).toDouble())
                // META_ESCAPE_ENHANCED_METAFILE: potongan EMF asli (CurrentRecordSize @26, data @38)
                0x0626 -> if (Bin.u16(b, p) == 0x000F && sz * 2 >= 6 + 38 && Bin.i32(b, p + 4) == 0x43464D57 && Bin.i32(b, p + 8) == 1) {
                    val cur = Bin.i32(b, p + 26)
                    val start = p + 38
                    if (cur > 0 && start + cur <= q + sz * 2) emf.write(b, start, cur)
                }
            }
            q += sz * 2
        }
        if (emf.size() > 88) {
            val e = emf.toByteArray()
            if (OfficeImage.kind(e) == "emf") EmfPlayer(e, maxPx).play()?.let { return it }
        }
        if (!framed && wExt != null && wExt[0] != 0.0 && wExt[1] != 0.0) {
            val ox = wOrg?.get(0) ?: 0.0; val oy = wOrg?.get(1) ?: 0.0
            fx = min(ox, ox + wExt[0]); fy = min(oy, oy + wExt[1]); fw = abs(wExt[0]); fh = abs(wExt[1]); framed = true
        }
        if (framed) { dc.wox = fx; dc.woy = fy; dc.wex = fw; dc.wey = fh } else { fw = 1.0; fh = 1.0 }
        px1 = if (inch > 0) inch / 96.0 else if (framed) max(fw, fh) / 700.0 else 1.0
        hatchScale = max(1.0, px1)
        mapping()
        var count = 0
        while (o + 6 <= n && count++ < 400000) {
            val sz = Bin.i32(b, o); val fn = Bin.u16(b, o + 4)
            if (sz < 3 || o.toLong() + sz.toLong() * 2 > n) break
            if (fn == 0) break
            if (fn != 0x0214 && fn != 0x0213) flushLine()
            try { record(fn, o, sz) } catch (_: RuntimeException) {}
            o += sz * 2
            if (body.length > 28_000_000) break
        }
        flushLine()
        if (!framed) {
            if (bx0 > bx1 || by0 > by1) return null
            val pad = max(bx1 - bx0, by1 - by0) * 0.01
            return svg(bx0 - pad, by0 - pad, bx1 - bx0 + 2 * pad, by1 - by0 + 2 * pad)
        }
        return svg(fx, fy, fw, fh)
    }

    private fun put(ob: Any) {
        var k = objs.indexOfFirst { it == null }
        if (k < 0) { k = objs.size; objs = objs.copyOf(objs.size * 2) }
        objs[k] = ob
    }

    private fun record(fn: Int, o: Int, sz: Int) {
        val p = o + 6
        fun w(k: Int) = Bin.i16(b, p + 2 * k).toDouble()
        fun uw(k: Int) = Bin.u16(b, p + 2 * k)
        fun color(k: Int) = Bin.i32(b, p + 2 * k)
        when (fn) {
            0x001E -> saveDC()
            0x0127 -> restoreDC(Bin.i16(b, p))
            0x0201 -> dc.bkColor = color(0)
            0x0102 -> dc.bkMode = uw(0)
            0x0103 -> dc.mapMode = uw(0)
            0x0104 -> dc.rop2 = uw(0)
            0x0106 -> dc.fillMode = uw(0)
            0x0209 -> dc.textColor = color(0)
            0x012E -> dc.textAlign = uw(0)
            0x020B -> { dc.woy = w(0); dc.wox = w(1) }
            0x020C -> { dc.wey = w(0); dc.wex = w(1); mapping() }
            0x020F -> { dc.woy += w(0); dc.wox += w(1) }
            0x0410 -> { val yd = w(0); val yn = w(1); val xd = w(2); val xn = w(3); if (xd != 0.0 && yd != 0.0) { dc.wex = dc.wex * xn / xd; dc.wey = dc.wey * yn / yd; mapping() } }
            0x0214 -> moveTo(w(1), w(0))
            0x0213 -> lineTo(w(1), w(0))
            0x0324, 0x0325 -> {
                val cnt = min(uw(0), (sz * 2 - 8) / 4)
                if (cnt < 1) return
                val sb = StringBuilder()
                for (k in 0 until cnt) { sb.append(if (k == 0) 'M' else 'L'); pt(sb, w(1 + 2 * k), w(2 + 2 * k)) }
                if (fn == 0x0324) { sb.append('Z'); shape(sb, true, true) } else shape(sb, false, true, open = true)
            }
            0x0538 -> {
                val np = uw(0); if (np <= 0 || np > 10000) return
                var k = 1 + np; val sb = StringBuilder()
                for (j in 0 until np) {
                    val c = uw(1 + j)
                    for (m in 0 until c) { if (p + 2 * (k + 1) + 1 >= o + sz * 2) break; sb.append(if (m == 0) 'M' else 'L'); pt(sb, w(k), w(k + 1)); k += 2 }
                    sb.append('Z')
                }
                shape(sb, true, true)
            }
            0x041B -> { val sb = StringBuilder(); rectPath(sb, w(3), w(2), w(1), w(0)); shape(sb, true, true) }
            0x0418 -> { val sb = StringBuilder(); ellipsePath(sb, w(3), w(2), w(1), w(0)); shape(sb, true, true) }
            0x061C -> { val sb = StringBuilder(); roundRectPath(sb, w(5), w(4), w(3), w(2), w(1), w(0)); shape(sb, true, true) }
            0x0817, 0x081A, 0x0830 -> {
                val kind = when (fn) { 0x0817 -> 0; 0x081A -> 2; else -> 1 }
                val sb = StringBuilder(); arcPath(sb, w(7), w(6), w(5), w(4), w(3), w(2), w(1), w(0), kind)
                shape(sb, kind != 0, true, open = kind == 0)
            }
            0x041F -> { val x = w(3); val y = w(2); val sb = StringBuilder(); rectPath(sb, x, y, x + 1, y + 1); body.append("<path d=\"").append(sb).append("\" fill=\"").append(hex(color(0))).append("\"/>") }
            0x061D -> patBlt(Bin.i32(b, p), w(5), w(4), w(3), w(2))
            0x0521 -> {
                val cnt = uw(0); if (cnt <= 0) return
                val strOff = p + 2
                val s = String(b, strOff, min(cnt, b.size - strOff), metaCharset(dc.font.charset, dc.font.face))
                val after = strOff + ((cnt + 1) and 1.inv())
                text(Bin.i16(b, after + 2).toDouble(), Bin.i16(b, after).toDouble(), s, null, 0, null, false)
            }
            0x0A32 -> {
                val y = w(0); val x = w(1); val cnt = uw(2); val opts = uw(3)
                var q = p + 8
                var rect: DoubleArray? = null
                if ((opts and 6) != 0) { rect = doubleArrayOf(Bin.i16(b, q).toDouble(), Bin.i16(b, q + 2).toDouble(), Bin.i16(b, q + 4).toDouble(), Bin.i16(b, q + 6).toDouble()); q += 8 }
                if (cnt <= 0) { if (rect != null && (opts and 2) != 0) text(x, y, " ", null, opts, rect, false); return }
                val end = o + sz * 2
                val cs = metaCharset(dc.font.charset, dc.font.face)
                val s = String(b, q, max(0, min(cnt, end - q)), cs)
                q += (cnt + 1) and 1.inv()
                val dx = if (q + 2 * cnt <= end) dxPerChar(s, cs, IntArray(cnt) { k -> Bin.i16(b, q + 2 * k) }) else null
                text(x, y, s, dx, opts, rect, false)
            }
            0x0B41, 0x0F43, 0x0940, 0x0D33 -> blit(fn, o, sz)
            0x0922, 0x0B23 -> { // BitBlt/StretchBlt lama (bitmap DDB) — hanya bentuk tanpa bitmap
                if (sz == (fn shr 8) + 3) {
                    if (fn == 0x0922) patBlt(Bin.i32(b, p), w(8), w(7), w(6), w(5)) else patBlt(Bin.i32(b, p), w(10), w(9), w(8), w(7))
                }
            }
            0x02FA -> put(Pen(uw(0), w(1), color(3), w(1) > 0))
            0x02FC -> put(Brush(uw(0), color(1), uw(3)))
            0x02FB -> {
                val face = StringBuilder()
                for (k in 0 until 32) { val q = p + 18 + k; if (q >= o + sz * 2) break; val c = b[q].toInt() and 255; if (c == 0) break; face.append(c.toChar()) }
                put(Font(w(0), w(2), uw(4), b[p + 10].toInt() != 0, b[p + 11].toInt() != 0, b[p + 12].toInt() != 0, face.toString(), b[p + 13].toInt() and 255))
            }
            0x0142 -> {
                val mono = if (uw(0) == 3) Dib.ones(b, p + 4, -1, uw(1) == 1) else null
                if (mono != null) put(Brush(3, 0, 0, null, mono))
                else { val img = Dib.decode(b, p + 4, -1, maxPx, Dib.COPY, 0, uw(1) == 1); put(if (img != null) Brush(5, 0x808080, 0, patternOf(img)) else Brush(0, 0x808080, 0)) }
            }
            0x01F9, 0x00F7, 0x06FF -> put(if (fn == 0x01F9) Brush(0, 0x808080, 0) else Any())
            0x012D -> { val k = uw(0); when (val ob = objs.getOrNull(k)) { is Pen -> dc.pen = ob; is Brush -> dc.brush = ob; is Font -> dc.font = ob } }
            0x01F0 -> { val k = uw(0); if (k < objs.size) objs[k] = null }
            0x0416 -> intersectClip(w(3), w(2), w(1), w(0))
            0x0415 -> excludeClip(w(3), w(2), w(1), w(0))
            0x012C -> dc.clip = null
        }
    }

    private fun blit(fn: Int, o: Int, sz: Int) {
        val p = o + 6
        fun w(k: Int) = Bin.i16(b, p + 2 * k).toDouble()
        val noBmp = sz == (fn shr 8) + 3
        when (fn) {
            0x0B41 -> { // DIBSTRETCHBLT
                val rop = Bin.i32(b, p)
                if (noBmp) { patBlt(rop, w(10), w(9), w(8), w(7)); return }
                val dims = Dib.dims(b, p + 20) ?: return
                val img = Dib.decode(b, p + 20, -1, maxPx, rop(rop), crop = srcRect(dims, w(5), w(4), w(3), w(2), false)) ?: return
                image(img, w(9), w(8), w(7), w(6))
            }
            0x0F43 -> { // STRETCHDIB
                val rop = Bin.i32(b, p); val usage = Bin.u16(b, p + 4)
                val dims = Dib.dims(b, p + 22) ?: return
                val img = Dib.decode(b, p + 22, -1, maxPx, rop(rop), 0, usage == 1, crop = srcRect(dims, w(6), w(5), w(4), w(3), true)) ?: return
                image(img, w(10), w(9), w(8), w(7))
            }
            0x0940 -> { // DIBBITBLT
                val rop = Bin.i32(b, p)
                if (noBmp) { patBlt(rop, w(8), w(7), w(6), w(5)); return }
                val dims = Dib.dims(b, p + 16) ?: return
                val img = Dib.decode(b, p + 16, -1, maxPx, rop(rop), crop = srcRect(dims, w(3), w(2), w(5), w(4), false)) ?: return
                image(img, w(7), w(6), w(5), w(4))
            }
            0x0D33 -> { // SETDIBTODEV
                val usage = Bin.u16(b, p)
                val dims = Dib.dims(b, p + 18) ?: return
                val img = Dib.decode(b, p + 18, -1, maxPx, Dib.COPY, 0, usage == 1, crop = srcRect(dims, w(4), w(3), w(6), w(5), true)) ?: return
                image(img, w(8), w(7), w(6), w(5))
            }
        }
    }

    private fun rop(r: Int): Int = when (r) { 0x008800C6 -> Dib.KEY_WHITE; 0x00EE0086, 0x00660046 -> Dib.KEY_BLACK; 0x00330008 -> Dib.INVERT; else -> Dib.COPY }

    private fun patBlt(rop: Int, x: Double, y: Double, w: Double, h: Double) {
        val sb = StringBuilder(); rectPath(sb, x, y, x + w, y + h)
        when (rop) {
            0x00F00021 -> { val save = dc.pen; dc.pen = NULL_PEN; emit(sb, true, false); dc.pen = save }
            0x00000042 -> body.append("<path d=\"").append(sb).append("\" fill=\"#000000\"/>")
            0x00FF0062 -> body.append("<path d=\"").append(sb).append("\" fill=\"#ffffff\"/>")
        }
    }
}
