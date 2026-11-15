import cloud.depup.office.*
import java.io.File
fun main(args: Array<String>) {
    val dir = File(args[0]); val out = File(dir, "out").apply { mkdirs() }
    for (f in dir.listFiles()!!.filter { it.name.endsWith(".pdf") }.sortedBy { it.name }) {
        val pw = if (f.name.contains("_pw")) "rahasia" else ""
        try {
            val t0 = System.nanoTime()
            val p = PdfFile.open(f.readBytes(), pw)
            val pages = p.pages()
            val clen = pages.sumOf { p.contents(it).size }
            val b = f.nameWithoutExtension
            File(out, "${b}_plain.pdf").outputStream().use { PdfSave.write(p, it) }
            File(out, "${b}_lock.pdf").outputStream().use { PdfSave.write(PdfFile.open(f.readBytes(), pw), it, PdfSave.Lock("uji123", "pemilik2", PdfSave.Perms(print = true, copy = false, modify = false))) }
            File(out, "${b}_ai.pdf").outputStream().use { PdfSave.write(PdfFile.open(f.readBytes(), pw), it) { c -> PdfAiMark.apply(c, "Budi") } }
            val ai = PdfFile.open(File(out, "${b}_ai.pdf").readBytes())
            val marked = PdfAiMark.isMarked(ai)
            File(out, "${b}_noai.pdf").outputStream().use { PdfSave.write(ai, it) { c -> PdfAiMark.remove(c) } }
            val un = PdfAiMark.isMarked(PdfFile.open(File(out, "${b}_noai.pdf").readBytes()))
            // kunci → buka lagi dengan pembaca sendiri
            val re = PdfFile.open(File(out, "${b}_lock.pdf").readBytes(), "uji123")
            val reLen = re.pages().sumOf { re.contents(it).size }
            val wrong = try { PdfFile.open(File(out, "${b}_lock.pdf").readBytes(), "salah"); "TERBUKA?!" } catch (e: PdfPasswordException) { "ditolak" }
            println("%-16s hal=%d isi=%d enc=%s | ai=%s hapus=%s | kunci: isi=%s pw-salah=%s  %.0fms".format(f.name, pages.size, clen, p.encrypted, marked, !un, if (reLen == clen) "sama" else "BEDA $reLen", wrong, (System.nanoTime() - t0) / 1e6))
        } catch (e: Exception) { println("%-16s GAGAL %s".format(f.name, e)); e.printStackTrace() }
    }
}
