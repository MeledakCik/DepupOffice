# Uji mesin PDF (PdfParse / PdfSave / PdfLayout / PdfToDocx)

Alat pengembang — tidak ikut di-build ke APK. Semua mesinnya murni Kotlin, jadi diuji di JVM.

- `doc.html`, `hard.html` — sumber PDF uji (cetak dengan Chrome headless:
  `Google Chrome --headless=new --no-pdf-header-footer --print-to-pdf=chrome.pdf doc.html`). `hard.html` berisi pita
  judul, bullet & daftar bernomor, dua kolom rata kanan-kiri, tabel dengan sel gabungan baris, teks diputar, pangkat/
  indeks, garis bawah, dan halaman kedua berisi foto. Perlu `foto.jpg` & `logo.png` di folder yang sama.
- Varian lain dibuat dengan Python (reportlab + pikepdf): font standar + tabel garis, object stream, terenkripsi RC4 /
  AES-128 / AES-256 dengan & tanpa password ("rahasia"), PDF hasil scan (gambar saja), 200 halaman.
- `PdfTest.kt` — untuk tiap PDF di folder: baca, tulis ulang, kunci AES-256 (izin salin/ubah dicabut); cek isi halaman tetap sama dan password salah ditolak. Hasil di `out/`.
- `validate.py` — validasi silang hasil `out/` dengan qpdf (pikepdf) & pypdf: teks identik dengan aslinya, enkripsi
  R6/256-bit, izin, tanpa password ditolak.
- `DocxTest.kt` — PDF → `out/<nama>.docx`. Pratinjau cepat di macOS: `qlmanage -t -s 1400 -o out out/x.docx`
  (catatan: Quick Look mengabaikan pemisah halaman, sel gabungan, rotasi kotak teks, inden/rata tabel, dan lebar kolom
  tetap — periksa hal-hal itu di XML atau Word).
- `LayTest.kt` — cetak isi halaman yang terbaca: baris (font, ukuran, warna), foto, bentuk.

Kompilasi: `kotlinc <app>/PdfParse.kt PdfSave.kt PdfLayout.kt PdfToDocx.kt OcrLayout.kt *.kt -d p.jar`
lalu `java -cp p.jar PdfTestKt <folder-pdf>` dan `python3 validate.py` di folder yang sama.
