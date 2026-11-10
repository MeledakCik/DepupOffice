package cloud.depup.office

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Segi-empat bebas untuk potong dokumen: 8 angka pecahan 0..1 (x0,y0, x1,y1, x2,y2, x3,y3) dengan urutan
 * KiriAtas, KananAtas, KananBawah, KiriBawah pada gambar SETELAH putar/balik/luruskan.
 * Saat diekspor, area ini "ditegakkan" (koreksi perspektif) menjadi persegi panjang bersudut 90°.
 * Murni Kotlin (tanpa Android) supaya bisa diuji di JVM.
 */
object Quad {
    fun full() = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    fun ofRect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    /** Sudah kotak sejajar bingkai (cukup dipotong biasa, tanpa koreksi perspektif). */
    fun isRect(q: FloatArray, eps: Float = 0.004f): Boolean =
        abs(q[0] - q[6]) < eps && abs(q[2] - q[4]) < eps && abs(q[1] - q[3]) < eps && abs(q[5] - q[7]) < eps

    fun isFull(q: FloatArray, eps: Float = 0.004f): Boolean = isRect(q, eps) && q[0] < eps && q[1] < eps && q[4] > 1 - eps && q[5] > 1 - eps

    /** Kotak pembatas [l, t, r, b]. */
    fun bbox(q: FloatArray): FloatArray = floatArrayOf(
        minOf(q[0], q[2], q[4], q[6]), minOf(q[1], q[3], q[5], q[7]),
        maxOf(q[0], q[2], q[4], q[6]), maxOf(q[1], q[3], q[5], q[7])
    )

    /** Urutkan 4 titik menjadi KiriAtas, KananAtas, KananBawah, KiriBawah. */
    fun order(q: FloatArray): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val idx = (0 until 4).sortedBy { atan2((q[2 * it + 1] - cy).toDouble(), (q[2 * it] - cx).toDouble()) }
        val start = idx.indices.minBy { q[2 * idx[it]] + q[2 * idx[it] + 1] }
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val i = idx[(start + k) % 4]
            out[2 * k] = q[2 * i]; out[2 * k + 1] = q[2 * i + 1]
        }
        return out
    }

    private fun cross(q: FloatArray, a: Int, b: Int, c: Int): Float {
        val abx = q[2 * b] - q[2 * a]; val aby = q[2 * b + 1] - q[2 * a + 1]
        val bcx = q[2 * c] - q[2 * b]; val bcy = q[2 * c + 1] - q[2 * b + 1]
        return abx * bcy - aby * bcx
    }

    /** Cembung, berurutan searah jarum jam (layar), dan tidak terlalu kecil. */
    fun isValid(q: FloatArray, minSide: Float = 0.04f): Boolean {
        for (k in 0 until 4) if (cross(q, k, (k + 1) % 4, (k + 2) % 4) <= 0f) return false
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            val dx = q[2 * j] - q[2 * k]; val dy = q[2 * j + 1] - q[2 * k + 1]
            if (dx * dx + dy * dy < minSide * minSide) return false
        }
        return true
    }

    fun clamp(q: FloatArray): FloatArray = FloatArray(8) { q[it].coerceIn(0f, 1f) }

    /** Putar 90° searah jarum jam (sama dengan tombol "Putar 90°"). */
    fun rot90(q: FloatArray): FloatArray = order(FloatArray(8) { i -> if (i % 2 == 0) 1f - q[i + 1] else q[i - 1] })

    /** Balik horizontal. */
    fun flipH(q: FloatArray): FloatArray = order(FloatArray(8) { i -> if (i % 2 == 0) 1f - q[i] else q[i] })

    /**
     * Ikut putaran halus gambar sebesar [deg] derajat (positif = searah jarum jam, sama dengan Canvas.rotate)
     * terhadap pusat gambar berukuran [w]x[h].
     */
    fun rotate(q: FloatArray, deg: Float, w: Int, h: Int): FloatArray {
        if (abs(deg) < 0.01f) return q.copyOf()
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val cx = w / 2f; val cy = h / 2f
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val x = q[2 * k] * w - cx; val y = q[2 * k + 1] * h - cy
            out[2 * k] = ((cx + x * c - y * s) / w)
            out[2 * k + 1] = ((cy + x * s + y * c) / h)
        }
        return order(clamp(out))
    }

    /** Luas (pecahan dari seluruh gambar). */
    fun area(q: FloatArray): Float {
        var a = 0f
        for (k in 0 until 4) { val j = (k + 1) % 4; a += q[2 * k] * q[2 * j + 1] - q[2 * j] * q[2 * k + 1] }
        return abs(a) / 2f
    }

    /**
     * Ukuran hasil (lebar, tinggi) dalam piksel untuk segi-empat [px] (koordinat piksel): sisi terpanjang dari tiap
     * pasangan sisi berhadapan; bila rasionya hampir A4 / F4 / kartu ID-1 (KTP), dipaskan persis.
     */
    fun outputSize(px: FloatArray): Pair<Float, Float> {
        fun dist(i: Int, j: Int): Float {
            val dx = px[2 * i] - px[2 * j]; val dy = px[2 * i + 1] - px[2 * j + 1]
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        var ow = maxOf(dist(0, 1), dist(3, 2))
        var oh = maxOf(dist(0, 3), dist(1, 2))
        if (ow < 1f || oh < 1f) return ow to oh
        val ratio = maxOf(ow, oh) / minOf(ow, oh)
        for (std in floatArrayOf(297f / 210f, 330f / 215f, 85.6f / 54f)) {
            if (abs(ratio / std - 1f) < 0.04f) {
                if (ow >= oh) ow = oh * std else oh = ow * std
                break
            }
        }
        return ow to oh
    }
}
