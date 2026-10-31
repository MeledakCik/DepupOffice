package cloud.depup.office

/**
 * Mengenali jenis dokumen & menyarankan nama berkas dari teks hasil OCR. Murni Kotlin, offline, tanpa model.
 * Heuristik kata kunci (fokus dokumen Indonesia) — sengaja konservatif: lebih baik tidak menebak daripada salah menamai.
 */
object SmartDoc {

    private class Rule(val label: String, val groups: List<List<String>>, val need: Int)

    // Tiap grup = salah satu kata kunci cukup; `need` = berapa grup yang harus terpenuhi.
    private val rules = listOf(
        Rule("KTP", listOf(listOf("nik"), listOf("provinsi", "kabupaten", "kota"), listOf("berlaku hingga", "gol. darah", "gol darah", "kewarganegaraan", "agama")), 3),
        Rule("Kartu_Keluarga", listOf(listOf("kartu keluarga"), listOf("no. kk", "no kk", "kepala keluarga")), 1),
        Rule("NPWP", listOf(listOf("npwp", "nomor pokok wajib pajak")), 1),
        Rule("SIM", listOf(listOf("surat izin mengemudi"), listOf("gol. sim", "sim a", "sim c", "sim b")), 1),
        Rule("STNK", listOf(listOf("stnk", "surat tanda nomor kendaraan"), listOf("nomor rangka", "no. rangka", "nomor mesin")), 1),
        Rule("Paspor", listOf(listOf("paspor", "passport", "republik indonesia"), listOf("tempat lahir", "place of birth", "date of expiry")), 2),
        Rule("Ijazah", listOf(listOf("ijazah", "sttb", "surat tanda tamat belajar"), listOf("dengan ini menyatakan", "telah lulus", "lulus dari")), 1),
        Rule("Transkrip_Nilai", listOf(listOf("transkrip", "kartu hasil studi", "indeks prestasi", "ipk", "khs"), listOf("sks", "mata kuliah", "semester")), 2),
        Rule("Sertifikat", listOf(listOf("sertifikat", "certificate"), listOf("diberikan kepada", "awarded to", "presented to", "diberikan atas")), 2),
        Rule("Slip_Gaji", listOf(listOf("slip gaji", "payslip", "pay slip"), listOf("gaji pokok", "tunjangan", "potongan", "basic salary")), 1),
        Rule("Kwitansi", listOf(listOf("kwitansi", "kuitansi", "telah terima dari", "sudah terima dari")), 1),
        Rule("Invoice", listOf(listOf("invoice", "faktur", "tagihan"), listOf("jatuh tempo", "due date", "subtotal", "ppn", "total")), 2),
        Rule("Struk", listOf(listOf("total", "jumlah", "subtotal"), listOf("tunai", "kembali", "kembalian", "cash", "change", "debit", "qris"), listOf("terima kasih", "thank you", "struk", "kasir", "ppn")), 3),
        Rule("Kontrak", listOf(listOf("perjanjian", "kontrak", "agreement"), listOf("pihak pertama", "pihak kedua", "para pihak", "first party")), 2),
        Rule("Surat", listOf(listOf("perihal", "hal :", "hal:", "lampiran"), listOf("yth", "kepada yth", "dengan hormat", "hormat kami", "hormat saya")), 2),
    )

    /** Jenis dokumen (label siap jadi nama berkas) atau null bila tidak yakin. */
    fun classify(text: String): String? {
        if (text.isBlank()) return null
        val t = text.lowercase()
        var best: String? = null; var bestHit = 0
        for (r in rules) {
            var hit = 0
            for (g in r.groups) if (g.any { t.contains(it) }) hit++
            if (hit >= r.need && hit > bestHit) { best = r.label; bestHit = hit }
        }
        return best
    }

    /** Baris pertama yang masuk akal sebagai judul (bukan angka/noise), dibersihkan untuk nama berkas. */
    fun titleOf(text: String): String? {
        for (raw in text.lineSequence().take(12)) {
            val line = raw.trim()
            if (line.length < 4 || line.length > 70) continue
            val letters = line.count { it.isLetter() }
            if (letters < 4 || letters * 10 < line.length * 6) continue
            val clean = line.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
            if (clean.length < 4) continue
            return clean.take(36).trim('_')
        }
        return null
    }

    /**
     * Nama berkas (tanpa ekstensi) yang disarankan, mis. "KTP_20261031_1455"; null bila tak ada petunjuk.
     * [stamp] = penanda waktu, supaya nama tetap unik.
     */
    fun suggestName(text: String, stamp: String): String? {
        classify(text)?.let { return "${it}_$stamp" }
        return titleOf(text)?.let { "${it}_$stamp" }
    }
}
