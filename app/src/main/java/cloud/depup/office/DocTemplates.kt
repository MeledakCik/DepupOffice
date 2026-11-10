package cloud.depup.office

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Template dokumen siap pakai untuk keperluan organisasi & administrasi warga (Karang Taruna, RT/RW, panitia).
 * Isian ditandai (dalam kurung) supaya mudah dicari & diganti di editor.
 */
object DocTemplates {
    class Tpl(val id: String, val title: String, val sub: String, val fill: (DocxWriter) -> Unit)

    private val idLocale = Locale("id", "ID")
    private fun today() = SimpleDateFormat("d MMMM yyyy", idLocale).format(Date())
    private fun year() = Calendar.getInstance().get(Calendar.YEAR)

    val all: List<Tpl> = listOf(
        Tpl("izin", "Surat Izin", "Tidak masuk sekolah/kerja", ::suratIzin),
        Tpl("ket_rtrw", "Surat Keterangan RT/RW", "Domisili / pengantar", ::suratKeteranganRtRw),
        Tpl("undangan", "Undangan Rapat", "Rapat pengurus / warga", ::undanganRapat),
        Tpl("notulen", "Notulen Rapat", "Catatan hasil rapat", ::notulen),
        Tpl("hadir", "Daftar Hadir", "Tabel nama & tanda tangan", ::daftarHadir),
        Tpl("proposal", "Proposal Kegiatan", "Latar belakang s.d. anggaran", ::proposal)
    )

    fun byId(id: String?): Tpl? = all.firstOrNull { it.id == id }

    /** Kop surat organisasi: nama, alamat, garis pemisah. */
    private fun kop(w: DocxWriter, org: String, addr: String) {
        w.paragraph(org, bold = true, align = 1)
        w.paragraph(addr, align = 1)
        w.paragraph("______________________________________________________________", align = 1)
    }

    private fun suratIzin(w: DocxWriter) {
        w.paragraph("(Kota), ${today()}", align = 2)
        w.paragraph("Kepada Yth.\n(Bapak/Ibu Wali Kelas / Atasan)\n(Nama Sekolah / Instansi)\ndi Tempat")
        w.paragraph("Perihal: Permohonan Izin", bold = true)
        w.paragraph("Dengan hormat,")
        w.paragraph("Yang bertanda tangan di bawah ini:")
        w.paragraph("Nama\t: (Nama Lengkap)\nKelas / Jabatan\t: (Kelas / Jabatan)\nAlamat\t: (Alamat)")
        w.paragraph("Dengan ini memohon izin tidak dapat mengikuti kegiatan belajar / bekerja pada hari (Hari), tanggal (Tanggal), " +
            "dikarenakan (alasan, misalnya: sakit / keperluan keluarga yang tidak dapat ditinggalkan).", align = 3)
        w.paragraph("Demikian surat izin ini saya sampaikan. Atas perhatian dan izin yang diberikan, saya ucapkan terima kasih.", align = 3)
        w.paragraph("Hormat saya,\n\n\n\n(Nama Lengkap)", align = 2)
    }

    private fun suratKeteranganRtRw(w: DocxWriter) {
        kop(w, "RUKUN TETANGGA (RT) 00X / RUKUN WARGA (RW) 00X", "Kelurahan (Nama) · Kecamatan (Nama) · Kota/Kabupaten (Nama)")
        w.paragraph("SURAT KETERANGAN", bold = true, align = 1)
        w.paragraph("Nomor: (00X)/RT.00X/RW.00X/${romanMonth()}/${year()}", align = 1)
        w.paragraph("Yang bertanda tangan di bawah ini Ketua RT 00X / RW 00X Kelurahan (Nama), Kecamatan (Nama), menerangkan bahwa:", align = 3)
        w.paragraph("Nama\t: (Nama Lengkap)\nNIK\t: (16 digit NIK)\nTempat/Tgl Lahir\t: (Kota, Tanggal)\nJenis Kelamin\t: (Laki-laki / Perempuan)\n" +
            "Agama\t: (Agama)\nPekerjaan\t: (Pekerjaan)\nAlamat\t: (Alamat Lengkap)")
        w.paragraph("Adalah benar warga kami yang berdomisili di alamat tersebut di atas. Surat keterangan ini dibuat untuk keperluan " +
            "(keperluan, misalnya: pengurusan KTP / KK / SKCK / domisili usaha).", align = 3)
        w.paragraph("Demikian surat keterangan ini dibuat dengan sebenarnya untuk dapat dipergunakan sebagaimana mestinya.", align = 3)
        w.paragraph("(Kota), ${today()}", align = 2)
        w.table(signTable("Ketua RW 00X", "Ketua RT 00X"))
    }

    private fun undanganRapat(w: DocxWriter) {
        kop(w, "(NAMA ORGANISASI, misalnya KARANG TARUNA ...)", "Sekretariat: (Alamat) · Telp: (Nomor)")
        w.paragraph("Nomor\t: (00X)/UND/(Singkatan)/${romanMonth()}/${year()}\nLampiran\t: -\nPerihal\t: Undangan Rapat")
        w.paragraph("(Kota), ${today()}", align = 2)
        w.paragraph("Kepada Yth.\nSeluruh Pengurus dan Anggota\n(Nama Organisasi)\ndi Tempat")
        w.paragraph("Dengan hormat,")
        w.paragraph("Sehubungan dengan (maksud rapat, misalnya: persiapan kegiatan peringatan HUT RI), kami mengundang Saudara/i " +
            "untuk hadir dalam rapat yang akan dilaksanakan pada:", align = 3)
        w.paragraph("Hari / Tanggal\t: (Hari, Tanggal)\nWaktu\t: (Jam) WIB s.d. selesai\nTempat\t: (Lokasi)\nAcara\t: 1. (Agenda pertama)\n\t  2. (Agenda kedua)\n\t  3. Lain-lain")
        w.paragraph("Mengingat pentingnya acara tersebut, kami mengharapkan kehadiran Saudara/i tepat waktu. " +
            "Atas perhatian dan kehadirannya, kami ucapkan terima kasih.", align = 3)
        w.table(signTable("Ketua", "Sekretaris"))
    }

    private fun notulen(w: DocxWriter) {
        w.title("Notulen Rapat")
        w.paragraph("(Nama Organisasi)", align = 1)
        w.paragraph("Hari / Tanggal\t: (Hari, Tanggal)\nWaktu\t: (Jam mulai) – (Jam selesai) WIB\nTempat\t: (Lokasi)\n" +
            "Pimpinan Rapat\t: (Nama)\nNotulis\t: (Nama)\nPeserta\t: (Jumlah) orang (daftar hadir terlampir)")
        w.heading("Agenda")
        w.paragraph("1. (Agenda pertama)\n2. (Agenda kedua)\n3. Lain-lain")
        w.heading("Jalannya Rapat")
        w.paragraph("1. Rapat dibuka oleh (Nama) pada pukul (Jam) WIB.\n2. (Ringkasan pembahasan agenda pertama).\n3. (Ringkasan pembahasan agenda kedua).", align = 3)
        w.heading("Keputusan / Kesimpulan")
        w.paragraph("1. (Keputusan pertama)\n2. (Keputusan kedua)")
        w.heading("Tindak Lanjut")
        w.table(simpleTable(listOf("No", "Kegiatan", "Penanggung Jawab", "Batas Waktu"), 4, listOf(1, 5, 3, 3)))
        w.paragraph("Rapat ditutup pada pukul (Jam) WIB.")
        w.paragraph("(Kota), ${today()}", align = 2)
        w.table(signTable("Pimpinan Rapat", "Notulis"))
    }

    private fun daftarHadir(w: DocxWriter) {
        w.title("Daftar Hadir")
        w.paragraph("Kegiatan\t: (Nama Kegiatan / Rapat)\nHari / Tanggal\t: (Hari, Tanggal)\nTempat\t: (Lokasi)")
        w.table(simpleTable(listOf("No", "Nama", "Jabatan / Alamat", "No. HP", "Tanda Tangan"), 20, listOf(1, 5, 4, 3, 3), numbered = true))
        w.paragraph("(Kota), ${today()}", align = 2)
        w.paragraph("Mengetahui,\nKetua\n\n\n\n(Nama Lengkap)", align = 2)
    }

    private fun proposal(w: DocxWriter) {
        w.title("Proposal Kegiatan")
        w.subtitle("(Nama Kegiatan) · (Nama Organisasi) · ${year()}")
        w.heading("I. Latar Belakang")
        w.paragraph("(Jelaskan alasan kegiatan perlu diadakan, kondisi saat ini, dan manfaatnya bagi warga / anggota.)", align = 3)
        w.heading("II. Nama Kegiatan")
        w.paragraph("(Nama lengkap kegiatan)")
        w.heading("III. Tujuan")
        w.paragraph("1. (Tujuan pertama)\n2. (Tujuan kedua)\n3. (Tujuan ketiga)")
        w.heading("IV. Sasaran / Peserta")
        w.paragraph("(Siapa peserta dan perkiraan jumlahnya.)")
        w.heading("V. Waktu dan Tempat")
        w.paragraph("Hari / Tanggal\t: (Hari, Tanggal)\nWaktu\t: (Jam) WIB s.d. selesai\nTempat\t: (Lokasi)")
        w.heading("VI. Susunan Panitia")
        w.table(simpleTable(listOf("No", "Jabatan", "Nama"), 6, listOf(1, 4, 6), numbered = true,
            fill = listOf("Penanggung Jawab", "Ketua Pelaksana", "Sekretaris", "Bendahara", "Sie Acara", "Sie Perlengkapan")))
        w.heading("VII. Rencana Anggaran Biaya")
        w.table(simpleTable(listOf("No", "Uraian", "Jumlah", "Harga Satuan (Rp)", "Total (Rp)"), 5, listOf(1, 5, 2, 3, 3), numbered = true,
            footer = listOf("", "TOTAL", "", "", "(Jumlah)")))
        w.heading("VIII. Penutup")
        w.paragraph("Demikian proposal ini kami susun. Besar harapan kami atas dukungan dan partisipasi Bapak/Ibu demi terlaksananya " +
            "kegiatan ini. Atas perhatian dan bantuannya, kami ucapkan terima kasih.", align = 3)
        w.paragraph("(Kota), ${today()}", align = 2)
        w.table(signTable("Ketua Pelaksana", "Sekretaris"))
        w.paragraph("Mengetahui,\n(Ketua Organisasi / Lurah / Kepala Desa)\n\n\n\n(Nama Lengkap)", align = 1)
    }

    // ------------------------------------------------------------------ bantuan tabel

    private fun cell(r: Int, c: Int, text: String) = TCell(r, c, 1, 1, listOf(listOf(Run(text))))

    /** Tabel bergaris dengan baris judul + [rows] baris isian. */
    private fun simpleTable(
        header: List<String>, rows: Int, colW: List<Int>, numbered: Boolean = false,
        fill: List<String>? = null, footer: List<String>? = null
    ): Table {
        val cells = ArrayList<TCell>()
        header.forEachIndexed { c, h -> cells.add(cell(0, c, h)) }
        for (r in 1..rows) for (c in header.indices) {
            val t = when {
                c == 0 && numbered -> "$r"
                c == 1 && fill != null -> fill.getOrElse(r - 1) { "" }
                else -> ""
            }
            cells.add(cell(r, c, t))
        }
        var total = rows + 1
        if (footer != null) { footer.forEachIndexed { c, t -> cells.add(cell(total, c, t)) }; total++ }
        return Table(total, header.size, cells, true, 0, colW, 1f)
    }

    /** Dua kolom tanda tangan tanpa garis (kiri & kanan). */
    private fun signTable(left: String, right: String): Table {
        fun sign(role: String) = listOf(listOf(Run(role)), listOf(Run("")), listOf(Run("")), listOf(Run("")), listOf(Run("(Nama Lengkap)")))
        return Table(1, 2, listOf(TCell(0, 0, 1, 1, sign(left)), TCell(0, 1, 1, 1, sign(right))), false, 0, listOf(1, 1), 1f)
    }

    private fun romanMonth(): String =
        arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")[Calendar.getInstance().get(Calendar.MONTH)]
}
