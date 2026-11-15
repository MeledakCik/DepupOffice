import cloud.depup.office.*
import java.io.File
fun main(args: Array<String>) {
    for (a in args) {
        val f = File(a)
        val out = File(f.parentFile, "out/" + f.nameWithoutExtension + ".docx")
        val t0 = System.nanoTime()
        PdfToDocx(out).convert(PdfFile.open(f.readBytes(), if (f.name.contains("_pw")) "rahasia" else ""), null)
        println("${f.name} -> ${out.name} ${out.length()} B  %.0fms".format((System.nanoTime() - t0) / 1e6))
    }
}
