package cloud.depup.office

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Mesin rumus gaya Excel — murni (tanpa Android), diuji JVM di SheetFormulaTest.
 *
 * Nilai: Double, String, Boolean, [FErr], atau null (sel kosong). Rumus diurai SEKALI menjadi pohon (disimpan per
 * teks rumus), lalu dievaluasi — IF/IFERROR hanya menghitung cabang yang dipakai, seperti Excel. Hasil per sel
 * disimpan sampai [clear] (isi berubah). Rujukan melingkar = #SIKLUS!; rantai rumus sangat panjang dihitung bertahap
 * dari atas, tidak menghabiskan stack.
 */
class FormulaEngine(private val src: Source) {

    interface Source {
        fun raw(r: Int, c: Int): String?
        fun rowCount(): Int
        fun colCount(): Int
        /** Angka dari teks sel bergaya lokal ("Rp 15.000", "1.234,5"); null bila bukan angka. */
        fun number(text: String): Double?
    }

    class FErr(val code: String) { override fun toString() = code }

    private class ErrEx(val err: FErr) : RuntimeException(null, null, false, false)
    private class DeepEx : RuntimeException(null, null, false, false)

    companion object {
        val DIV0 = FErr("#DIV/0!"); val VALUE = FErr("#VALUE!"); val REF = FErr("#REF!"); val NAME = FErr("#NAME?")
        val NA = FErr("#N/A"); val NUM = FErr("#NUM!"); val CIRC = FErr("#SIKLUS!"); val ERROR = FErr("#ERROR!")
        private val NULL = Any()
        private const val MAX_DEPTH = 1500

        /** Fungsi yang dikenali (dipakai juga saat mengimpor: rumus dengan fungsi lain diimpor sebagai nilai). */
        val FUNCTIONS = setOf(
            "SUM", "AVERAGE", "AVG", "MIN", "MAX", "COUNT", "COUNTA", "COUNTBLANK", "ABS", "ROUND", "ROUNDUP",
            "ROUNDDOWN", "INT", "MOD", "POWER", "SQRT", "PRODUCT", "SUMIF", "COUNTIF", "AVERAGEIF", "IF", "AND", "OR",
            "NOT", "IFERROR", "TRUE", "FALSE", "CONCAT", "CONCATENATE", "LEN", "UPPER", "LOWER", "PROPER", "TRIM",
            "LEFT", "RIGHT", "MID", "VALUE", "VLOOKUP", "HLOOKUP", "INDEX", "MATCH", "TODAY", "NOW", "DATE", "YEAR",
            "MONTH", "DAY", "ISBLANK", "ISNUMBER", "ISTEXT", "ISERROR", "PI"
        )

        /** Nomor seri tanggal Excel (1900) ↔ tanggal. */
        fun serial(y: Int, m: Int, d: Int): Double {
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }
            val base = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(1899, 11, 30) }
            return ((cal.timeInMillis - base.timeInMillis) / 86_400_000L).toDouble()
        }

        fun dateParts(serial: Double): IntArray {
            val base = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(1899, 11, 30) }
            base.add(Calendar.DAY_OF_MONTH, floor(serial).toInt())
            return intArrayOf(base.get(Calendar.YEAR), base.get(Calendar.MONTH) + 1, base.get(Calendar.DAY_OF_MONTH))
        }

        /** Rumus hanya memakai fungsi yang dikenali & rujukan dalam lembar ini. */
        fun supported(formula: String): Boolean {
            val f = formula.removePrefix("=")
            if (f.contains('!') || f.contains('[') || f.contains('{')) return false
            var inStr = false
            var i = 0
            while (i < f.length) {
                val ch = f[i]
                if (ch == '"') { inStr = !inStr; i++; continue }
                if (!inStr && ch.isLetter()) {
                    var j = i
                    while (j < f.length && (f[j].isLetterOrDigit() || f[j] == '.' || f[j] == '_')) j++
                    var k = j; while (k < f.length && f[k] == ' ') k++
                    if (k < f.length && f[k] == '(' && f.substring(i, j).uppercase() !in FUNCTIONS) return false
                    i = j; continue
                }
                i++
            }
            return !inStr
        }
    }

    // ---------- pohon rumus ----------

    private sealed class Node
    private class Num(val v: Double) : Node()
    private class Str(val v: String) : Node()
    private class Bool(val v: Boolean) : Node()
    private class ErrN(val e: FErr) : Node()
    private class RefN(val r: Int, val c: Int) : Node()
    private class RangeN(val r1: Int, val c1: Int, val r2: Int, val c2: Int) : Node()
    private class Un(val op: Char, val a: Node) : Node()
    private class Bin(val op: String, val a: Node, val b: Node) : Node()
    private class Call(val name: String, val args: List<Node>) : Node()

    /** Rentang sebagai nilai argumen fungsi. */
    private class RangeV(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    private val trees = HashMap<String, Node>()
    private val cache = HashMap<Long, Any>()
    private val visiting = HashSet<Long>()
    private var depth = 0

    fun clear() { cache.clear() }

    private fun key(r: Int, c: Int) = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    /** Nilai sel (rumus dihitung). */
    fun value(r: Int, c: Int): Any? {
        return try { cellValue(r, c) } catch (_: DeepEx) {
            // rantai rumus sangat panjang: hitung rumus-rumus di atasnya dulu (berurutan), lalu coba lagi
            visiting.clear(); depth = 0
            for (rr in 0..r) {
                val cols = src.colCount()
                for (cc in 0 until cols) {
                    if (rr == r && cc >= c) break
                    if (src.raw(rr, cc)?.startsWith("=") == true) try { cellValue(rr, cc) } catch (_: DeepEx) { visiting.clear(); depth = 0 }
                }
            }
            try { cellValue(r, c) } catch (_: DeepEx) { visiting.clear(); depth = 0; ERROR }
        }
    }

    private fun cellValue(r: Int, c: Int): Any? {
        if (r < 0 || c < 0) return REF
        val k = key(r, c)
        cache[k]?.let { return if (it === NULL) null else it }
        val raw = src.raw(r, c)
        val v: Any? = when {
            raw.isNullOrEmpty() -> null
            !raw.startsWith("=") || raw.length == 1 -> literal(raw)
            else -> {
                if (!visiting.add(k)) return CIRC
                if (++depth > MAX_DEPTH) { depth--; visiting.remove(k); throw DeepEx() }
                try { evalFormula(raw) } finally { depth--; visiting.remove(k) }
            }
        }
        cache[k] = v ?: NULL
        return v
    }

    private fun literal(raw: String): Any {
        src.number(raw)?.let { return it }
        return when (raw.trim().uppercase()) { "TRUE" -> true; "FALSE" -> false; else -> raw }
    }

    /** Hitung teks rumus (tanpa sel asal) — mis. untuk pratinjau. */
    fun evalFormula(raw: String): Any? {
        val tree = trees.getOrPut(raw) {
            try { Parser(raw.substring(1)).parseAll() } catch (_: Exception) { ErrN(ERROR) }
        }
        return try {
            val v = eval(tree)
            if (v is RangeV) scalarOf(v) else v
        } catch (e: ErrEx) { e.err } catch (e: DeepEx) { throw e } catch (_: StackOverflowError) { throw DeepEx() } catch (_: Exception) { ERROR }
    }

    // ---------- pengurai ----------

    private inner class Parser(private val s: String) {
        private var p = 0

        fun parseAll(): Node {
            val n = compare()
            ws()
            if (p < s.length) throw IllegalArgumentException("sisa: ${s.substring(p)}")
            return n
        }

        private fun ws() { while (p < s.length && s[p].isWhitespace()) p++ }
        private fun peek(t: String): Boolean { ws(); return s.startsWith(t, p) }
        private fun eat(t: String): Boolean { if (peek(t)) { p += t.length; return true }; return false }

        private fun compare(): Node {
            var a = concat()
            while (true) {
                val op = listOf("<>", "<=", ">=", "=", "<", ">").firstOrNull { peek(it) } ?: return a
                p += op.length
                a = Bin(op, a, concat())
            }
        }

        private fun concat(): Node {
            var a = add()
            while (eat("&")) a = Bin("&", a, add())
            return a
        }

        private fun add(): Node {
            var a = mul()
            while (true) {
                a = when {
                    eat("+") -> Bin("+", a, mul())
                    eat("-") -> Bin("-", a, mul())
                    else -> return a
                }
            }
        }

        private fun mul(): Node {
            var a = pow()
            while (true) {
                a = when {
                    eat("*") -> Bin("*", a, pow())
                    eat("/") -> Bin("/", a, pow())
                    else -> return a
                }
            }
        }

        private fun pow(): Node {
            var a = unary()
            while (eat("^")) a = Bin("^", a, unary())
            return a
        }

        private fun unary(): Node = when {
            eat("-") -> Un('-', unary())
            eat("+") -> unary()
            else -> {
                var a = primary()
                while (eat("%")) a = Un('%', a)
                a
            }
        }

        private fun primary(): Node {
            ws()
            if (p >= s.length) throw IllegalArgumentException("kosong")
            val ch = s[p]
            if (ch == '(') { p++; val n = compare(); if (!eat(")")) throw IllegalArgumentException(")"); return n }
            if (ch == '"') {
                p++
                val sb = StringBuilder()
                while (p < s.length) {
                    if (s[p] == '"') { if (p + 1 < s.length && s[p + 1] == '"') { sb.append('"'); p += 2; continue }; p++; return Str(sb.toString()) }
                    sb.append(s[p]); p++
                }
                throw IllegalArgumentException("kutip")
            }
            if (ch.isDigit() || ch == '.') {
                val st = p
                while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
                if (p < s.length && (s[p] == 'E' || s[p] == 'e') && p + 1 < s.length && (s[p + 1].isDigit() || s[p + 1] == '-' || s[p + 1] == '+')) {
                    p += 2; while (p < s.length && s[p].isDigit()) p++
                }
                return Num(s.substring(st, p).toDoubleOrNull() ?: throw IllegalArgumentException("angka"))
            }
            if (ch == '#') {
                val code = listOf("#REF!", "#DIV/0!", "#VALUE!", "#NAME?", "#N/A", "#NUM!").firstOrNull { s.startsWith(it, p, true) }
                    ?: throw IllegalArgumentException("#")
                p += code.length
                return ErrN(FErr(code))
            }
            if (ch == '$' || ch.isLetter()) {
                val st = p
                ref()?.let { a ->
                    if (eat(":")) {
                        val b = ref() ?: throw IllegalArgumentException("rentang")
                        return RangeN(a.first, a.second, b.first, b.second)
                    }
                    return RefN(a.first, a.second)
                }
                p = st
                while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '.' || s[p] == '_')) p++
                val name = s.substring(st, p).uppercase()
                if (eat("(")) {
                    val args = ArrayList<Node>()
                    if (!eat(")")) {
                        while (true) {
                            ws()
                            // argumen kosong (mis. VLOOKUP(a,b,2,)) = FALSE/0
                            args.add(if (peek(",") || peek(")")) Bool(false) else compare())
                            if (eat(",") || eat(";")) continue
                            if (eat(")")) break
                            throw IllegalArgumentException("argumen")
                        }
                    }
                    return Call(name, args)
                }
                return when (name) { "TRUE" -> Bool(true); "FALSE" -> Bool(false); else -> ErrN(NAME) }
            }
            throw IllegalArgumentException("karakter $ch")
        }

        /** Rujukan sel A1 / $A$1 pada posisi sekarang; null bila bukan. */
        private fun ref(): Pair<Int, Int>? {
            ws()
            val st = p
            if (p < s.length && s[p] == '$') p++
            val ls = p
            while (p < s.length && s[p].isLetter()) p++
            val letters = s.substring(ls, p)
            if (letters.isEmpty() || letters.length > 3) { p = st; return null }
            if (p < s.length && s[p] == '$') p++
            val ds = p
            while (p < s.length && s[p].isDigit()) p++
            if (p == ds || (p < s.length && (s[p] == '(' || s[p].isLetter() || s[p] == '_'))) { p = st; return null }
            var col = 0
            for (c in letters) col = col * 26 + (c.uppercaseChar() - 'A' + 1)
            val row = s.substring(ds, p).toIntOrNull() ?: run { p = st; return null }
            if (row < 1) { p = st; return null }
            return (row - 1) to (col - 1)
        }
    }

    // ---------- evaluasi ----------

    private fun eval(n: Node): Any? = when (n) {
        is Num -> n.v
        is Str -> n.v
        is Bool -> n.v
        is ErrN -> throw ErrEx(n.e)
        is RefN -> { val v = cellValue(n.r, n.c); if (v is FErr) throw ErrEx(v); v }
        is RangeN -> RangeV(minOf(n.r1, n.r2), minOf(n.c1, n.c2), maxOf(n.r1, n.r2), maxOf(n.c1, n.c2))
        is Un -> if (n.op == '-') -num(eval(n.a)) else num(eval(n.a)) / 100.0
        is Bin -> bin(n)
        is Call -> call(n.name, n.args)
    }

    private fun bin(n: Bin): Any {
        val a = scalar(eval(n.a)); val b = scalar(eval(n.b))
        return when (n.op) {
            "+" -> num(a) + num(b)
            "-" -> num(a) - num(b)
            "*" -> num(a) * num(b)
            "/" -> { val d = num(b); if (d == 0.0) throw ErrEx(DIV0); num(a) / d }
            "^" -> { val v = num(a).pow(num(b)); if (v.isNaN() || v.isInfinite()) throw ErrEx(NUM); v }
            "&" -> str(a) + str(b)
            else -> {
                val cmp = compareValues(a, b)
                when (n.op) { "=" -> cmp == 0; "<>" -> cmp != 0; "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; else -> cmp >= 0 }
            }
        }
    }

    /** Urutan Excel: angka < teks < logika; teks tanpa membedakan huruf besar/kecil; kosong = 0 / "". */
    private fun compareValues(a: Any?, b: Any?): Int {
        val x = a ?: (if (b is String) "" else 0.0)
        val y = b ?: (if (a is String) "" else 0.0)
        fun rank(v: Any) = when (v) { is Double -> 0; is String -> 1; else -> 2 }
        val ra = rank(x); val rb = rank(y)
        if (ra != rb) return ra.compareTo(rb)
        return when (x) {
            is Double -> x.compareTo(y as Double)
            is String -> x.lowercase(Locale.ROOT).compareTo((y as String).lowercase(Locale.ROOT))
            else -> (x as Boolean).compareTo(y as Boolean)
        }
    }

    private fun scalarOf(r: RangeV): Any? =
        if (r.r1 == r.r2 && r.c1 == r.c2) cellValue(r.r1, r.c1).also { if (it is FErr) throw ErrEx(it) } else throw ErrEx(VALUE)

    private fun scalar(v: Any?): Any? = if (v is RangeV) scalarOf(v) else v

    private fun num(v: Any?): Double = when (val x = scalar(v)) {
        null -> 0.0
        is Double -> x
        is Boolean -> if (x) 1.0 else 0.0
        is String -> if (x.isBlank()) 0.0 else src.number(x) ?: throw ErrEx(VALUE)
        is FErr -> throw ErrEx(x)
        else -> throw ErrEx(VALUE)
    }

    private fun str(v: Any?): String = when (val x = scalar(v)) {
        null -> ""
        is Double -> fmtGeneral(x)
        is Boolean -> if (x) "TRUE" else "FALSE"
        is FErr -> throw ErrEx(x)
        else -> x.toString()
    }

    private fun bool(v: Any?): Boolean = when (val x = scalar(v)) {
        null -> false
        is Boolean -> x
        is Double -> x != 0.0
        is String -> when (x.uppercase()) { "TRUE" -> true; "FALSE" -> false; else -> throw ErrEx(VALUE) }
        is FErr -> throw ErrEx(x)
        else -> throw ErrEx(VALUE)
    }

    /** Seperti Excel: paling banyak 15 angka bermakna (0,1+0,2 = 0,3, bukan 0,30000000000000004). */
    private fun fmtGeneral(d: Double): String =
        if (d == floor(d) && abs(d) < 1e15) d.toLong().toString()
        else java.math.BigDecimal(d).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    /** Rentang dipotong ke area yang berisi (A1:A1000000 tidak menelusuri sejuta sel kosong). */
    private inline fun forEachCell(r: RangeV, f: (Int, Int, Any?) -> Unit) {
        val bottom = minOf(r.r2, src.rowCount() - 1); val right = minOf(r.c2, src.colCount() - 1)
        for (rr in r.r1..bottom) for (cc in r.c1..right) f(rr, cc, cellValue(rr, cc))
    }

    /** Angka dari argumen agregat: di dalam rentang hanya sel angka; argumen langsung dikonversi. */
    private fun numbers(args: List<Node>, out: ArrayList<Double> = ArrayList()): ArrayList<Double> {
        for (a in args) {
            // rujukan satu sel diperlakukan seperti rentang: teks di dalamnya diabaikan (SUM(A1) dengan A1 teks = 0)
            if (a is RefN) { when (val x = cellValue(a.r, a.c)) { is Double -> out.add(x); is FErr -> throw ErrEx(x) }; continue }
            when (val v = eval(a)) {
                is RangeV -> forEachCell(v) { _, _, x -> when (x) { is Double -> out.add(x); is FErr -> throw ErrEx(x) } }
                null -> {}
                else -> out.add(num(v))
            }
        }
        return out
    }

    private fun argNum(args: List<Node>, i: Int, def: Double? = null): Double =
        if (i < args.size) num(eval(args[i])) else def ?: throw ErrEx(VALUE)

    private fun argStr(args: List<Node>, i: Int): String = if (i < args.size) str(eval(args[i])) else throw ErrEx(VALUE)

    private fun range(n: Node): RangeV = when (val v = eval(n)) {
        is RangeV -> v
        else -> throw ErrEx(VALUE)
    }

    private fun roundTo(v: Double, digits: Int, mode: Int): Double {
        val f = 10.0.pow(digits)
        val x = v * f
        val r = when (mode) {
            0 -> Math.round(abs(x)).toDouble() * kotlin.math.sign(x)
            1 -> kotlin.math.ceil(abs(x) - 1e-9) * kotlin.math.sign(x)
            else -> floor(abs(x) + 1e-9) * kotlin.math.sign(x)
        }
        return r / f
    }

    /** Kriteria SUMIF/COUNTIF: ">100", "<>x", "=teks", "teks*" (wildcard), atau nilai langsung. */
    private fun criteria(c: Any?): (Any?) -> Boolean {
        if (c is Double) return { v -> v is Double && v == c }
        if (c is Boolean) return { v -> v == c }
        val s = c?.toString() ?: ""
        val op = listOf("<>", "<=", ">=", "=", "<", ">").firstOrNull { s.startsWith(it) } ?: ""
        val rest = s.substring(op.length)
        val n = src.number(rest)
        if (n != null) return { v ->
            val x = when (v) { is Double -> v; is String -> src.number(v); else -> null }
            when (op) {
                "", "=" -> x == n
                "<>" -> x != n
                "<" -> x != null && x < n
                ">" -> x != null && x > n
                "<=" -> x != null && x <= n
                else -> x != null && x >= n
            }
        }
        val rx = Regex(wildcard(rest), RegexOption.IGNORE_CASE)
        return { v ->
            val t = when (v) { null -> ""; is Double -> fmtGeneral(v); is Boolean -> if (v) "TRUE" else "FALSE"; else -> v.toString() }
            when (op) {
                "<>" -> !rx.matches(t)
                "", "=" -> if (rest.isEmpty() && op == "=") t.isEmpty() else rx.matches(t)
                else -> { val cmp = t.lowercase().compareTo(rest.lowercase()); when (op) { "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; else -> cmp >= 0 } }
            }
        }
    }

    /** Pola Excel (* = apa saja, ? = satu huruf, ~ = karakter berikutnya apa adanya) → regex. */
    private fun wildcard(p: String): String {
        val sb = StringBuilder("^")
        var i = 0
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '~' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i++ }
                ch == '*' -> sb.append(".*")
                ch == '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return sb.append('$').toString()
    }

    private fun call(name: String, a: List<Node>): Any? {
        when (name) {
            "IF" -> return if (bool(eval(a.getOrElse(0) { throw ErrEx(VALUE) }))) (if (a.size > 1) scalar(eval(a[1])) else true)
                else (if (a.size > 2) scalar(eval(a[2])) else false)
            "IFERROR" -> return try { scalar(eval(a[0])).also { if (it is FErr) throw ErrEx(it) } } catch (_: ErrEx) { if (a.size > 1) scalar(eval(a[1])) else "" }
            "ISERROR" -> {
                val isErr = try { scalar(eval(a[0])) is FErr } catch (_: ErrEx) { true }
                return isErr
            }
        }
        return when (name) {
            "SUM" -> numbers(a).sum()
            "AVERAGE", "AVG" -> numbers(a).let { if (it.isEmpty()) throw ErrEx(DIV0) else it.sum() / it.size }
            "MIN" -> numbers(a).minOrNull() ?: 0.0
            "MAX" -> numbers(a).maxOrNull() ?: 0.0
            "PRODUCT" -> numbers(a).fold(1.0) { x, y -> x * y }
            "COUNT" -> {
                var n = 0
                for (x in a) when (val v = try { eval(x) } catch (_: ErrEx) { null }) {
                    is RangeV -> forEachCell(v) { _, _, y -> if (y is Double) n++ }
                    is Double -> n++
                    is String -> if (src.number(v) != null) n++
                }
                n.toDouble()
            }
            "COUNTA" -> {
                var n = 0
                for (x in a) when (val v = eval(x)) { is RangeV -> forEachCell(v) { _, _, y -> if (y != null && y != "") n++ }; null -> {}; else -> n++ }
                n.toDouble()
            }
            "COUNTBLANK" -> {
                val r = range(a[0]); var filled = 0
                forEachCell(r) { _, _, y -> if (y != null && y != "") filled++ }
                ((r.r2 - r.r1 + 1).toDouble() * (r.c2 - r.c1 + 1)) - filled
            }
            "ABS" -> abs(argNum(a, 0))
            "ROUND" -> roundTo(argNum(a, 0), argNum(a, 1, 0.0).toInt(), 0)
            "ROUNDUP" -> roundTo(argNum(a, 0), argNum(a, 1, 0.0).toInt(), 1)
            "ROUNDDOWN" -> roundTo(argNum(a, 0), argNum(a, 1, 0.0).toInt(), 2)
            "INT" -> floor(argNum(a, 0))
            "MOD" -> { val d = argNum(a, 1); if (d == 0.0) throw ErrEx(DIV0); val x = argNum(a, 0); x - d * floor(x / d) }
            "POWER" -> argNum(a, 0).pow(argNum(a, 1))
            "SQRT" -> { val x = argNum(a, 0); if (x < 0) throw ErrEx(NUM); kotlin.math.sqrt(x) }
            "PI" -> Math.PI
            "SUMIF", "AVERAGEIF", "COUNTIF" -> {
                val r = range(a[0]); val test = criteria(scalar(eval(a[1])))
                val sumR = if (name != "COUNTIF" && a.size > 2) range(a[2]) else r
                var total = 0.0; var n = 0
                forEachCell(r) { rr, cc, v ->
                    if (test(v)) {
                        n++
                        if (name != "COUNTIF") { val t = cellValue(sumR.r1 + (rr - r.r1), sumR.c1 + (cc - r.c1)); if (t is Double) total += t }
                    }
                }
                when (name) { "COUNTIF" -> n.toDouble(); "SUMIF" -> total; else -> if (n == 0) throw ErrEx(DIV0) else total / n }
            }
            "AND" -> { var all = true; for (x in a) { when (val v = eval(x)) { is RangeV -> forEachCell(v) { _, _, y -> if (y is Boolean && !y || y is Double && y == 0.0) all = false }; else -> if (!bool(v)) all = false } }; all }
            "OR" -> { var any = false; for (x in a) { when (val v = eval(x)) { is RangeV -> forEachCell(v) { _, _, y -> if (y is Boolean && y || y is Double && y != 0.0) any = true }; else -> if (bool(v)) any = true } }; any }
            "NOT" -> !bool(eval(a[0]))
            "TRUE" -> true
            "FALSE" -> false
            "CONCAT", "CONCATENATE" -> buildString {
                for (x in a) when (val v = eval(x)) { is RangeV -> forEachCell(v) { _, _, y -> if (y is FErr) throw ErrEx(y); append(str(y)) }; else -> append(str(v)) }
            }
            "LEN" -> argStr(a, 0).length.toDouble()
            "UPPER" -> argStr(a, 0).uppercase()
            "LOWER" -> argStr(a, 0).lowercase()
            "PROPER" -> argStr(a, 0).lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
            "TRIM" -> argStr(a, 0).trim().replace(Regex(" +"), " ")
            "LEFT" -> { val s = argStr(a, 0); s.take(argNum(a, 1, 1.0).toInt().coerceAtLeast(0)) }
            "RIGHT" -> { val s = argStr(a, 0); s.takeLast(argNum(a, 1, 1.0).toInt().coerceAtLeast(0)) }
            "MID" -> { val s = argStr(a, 0); val st = argNum(a, 1).toInt(); val n = argNum(a, 2).toInt()
                if (st < 1 || n < 0) throw ErrEx(VALUE); if (st > s.length) "" else s.substring(st - 1, minOf(s.length, st - 1 + n)) }
            "VALUE" -> num(eval(a[0]))
            "VLOOKUP", "HLOOKUP" -> {
                val key = scalar(eval(a[0])); val t = range(a[1]); val idx = argNum(a, 2).toInt()
                val approx = if (a.size > 3) bool(eval(a[3])) else true
                val vert = name == "VLOOKUP"
                val n = if (vert) minOf(t.r2, src.rowCount() - 1) - t.r1 + 1 else minOf(t.c2, src.colCount() - 1) - t.c1 + 1
                if (idx < 1 || idx > (if (vert) t.c2 - t.c1 + 1 else t.r2 - t.r1 + 1)) throw ErrEx(REF)
                var hit = -1
                for (i in 0 until n) {
                    val v = if (vert) cellValue(t.r1 + i, t.c1) else cellValue(t.r1, t.c1 + i)
                    val cmp = if (v == null) 1 else compareValues(v, key)
                    if (cmp == 0) { hit = i; break }
                    if (approx) { if (cmp < 0 && v != null) hit = i else if (cmp > 0 && v != null) break }
                }
                if (hit < 0) throw ErrEx(NA)
                if (vert) cellValue(t.r1 + hit, t.c1 + idx - 1) else cellValue(t.r1 + idx - 1, t.c1 + hit)
            }
            "INDEX" -> {
                val t = range(a[0]); val r = argNum(a, 1).toInt(); val c = argNum(a, 2, 1.0).toInt()
                val rr = if (t.r1 == t.r2 && a.size == 2) 1 else r
                val cc = if (t.r1 == t.r2 && a.size == 2) r else c
                if (rr < 1 || cc < 1 || t.r1 + rr - 1 > t.r2 || t.c1 + cc - 1 > t.c2) throw ErrEx(REF)
                cellValue(t.r1 + rr - 1, t.c1 + cc - 1)
            }
            "MATCH" -> {
                val key = scalar(eval(a[0])); val t = range(a[1]); val type = argNum(a, 2, 1.0).toInt()
                val vert = t.c1 == t.c2
                val n = if (vert) minOf(t.r2, src.rowCount() - 1) - t.r1 + 1 else minOf(t.c2, src.colCount() - 1) - t.c1 + 1
                var hit = -1
                for (i in 0 until n) {
                    val v = if (vert) cellValue(t.r1 + i, t.c1) else cellValue(t.r1, t.c1 + i)
                    if (v == null) continue
                    val cmp = compareValues(v, key)
                    when {
                        type == 0 -> if (cmp == 0) { hit = i; break }
                        type > 0 -> if (cmp <= 0) hit = i else break
                        else -> if (cmp >= 0) hit = i else break
                    }
                }
                if (hit < 0) throw ErrEx(NA)
                (hit + 1).toDouble()
            }
            "TODAY" -> Calendar.getInstance().let { serial(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) }
            "NOW" -> Calendar.getInstance().let {
                serial(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)) +
                    (it.get(Calendar.HOUR_OF_DAY) * 3600 + it.get(Calendar.MINUTE) * 60 + it.get(Calendar.SECOND)) / 86400.0
            }
            "DATE" -> serial(argNum(a, 0).toInt(), argNum(a, 1).toInt(), argNum(a, 2).toInt())
            "YEAR" -> dateParts(argNum(a, 0))[0].toDouble()
            "MONTH" -> dateParts(argNum(a, 0))[1].toDouble()
            "DAY" -> dateParts(argNum(a, 0))[2].toDouble()
            "ISBLANK" -> eval(a[0]).let { scalar(it) == null }
            "ISNUMBER" -> try { scalar(eval(a[0])) is Double } catch (_: ErrEx) { false }
            "ISTEXT" -> try { scalar(eval(a[0])) is String } catch (_: ErrEx) { false }
            else -> throw ErrEx(NAME)
        }
    }
}
