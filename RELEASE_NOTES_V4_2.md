# DepUp Office 4.2.0

## Dokumen Terlindungi bisa dibagikan (Android & iOS)
- Format baru v3: isi dienkripsi AES-256-GCM dengan kunci dari **kode akses** guru (PBKDF2-SHA256 600.000 iterasi → HKDF → AES-GCM + HMAC-SHA256). Tidak ada kunci di dalam aplikasi, sehingga membongkar APK/IPA tidak membuka dokumen.
- Dokumen bisa dibuka di DepUp Office mana pun (Android maupun iPhone) yang punya kode akses; di aplikasi lain hanya tampil halaman sampul.
- Kode akses bisa diingat di perangkat (Android Keystore / iOS Keychain). Ekspor PDF asli tetap memerlukan password guru.
- Dokumen format v1 (4.1.0) tetap terbaca, lengkap dengan deteksi dokumen palsu (honeypot).

## Perbaikan keamanan Android
- Pemeriksaan tanda tangan APK tidak lagi menolak semua dokumen bila variabel build kosong.
- Thread `gmain` biasa tidak lagi dianggap Frida.

## iOS: setara aplikasi Android
- Alat PDF: susun/putar/hapus halaman, hapus halaman kosong, kompres ke target ukuran, ambil gambar, PDF bisa dicari (OCR), coret-coret (pena, stabilo, catatan), tanda tangan + pustaka TTD, EXE ke PDF, presentasi.
- Foto: pembersih bayangan, TTD transparan, pasfoto 2x3/3x4/4x6 + lembar A4, kartu nama ke kontak (.vcf).
- Excel: editor dengan rumus, tabel/struk ke Excel, grafik batang/lingkaran/garis, template.
- Word: editor .docx/.rtf, 6 template surat & proposal, Word/Excel ke PDF.
- Lainnya: nota/invoice, katalog, perencana jadwal, label harga & barcode, ringkas dokumen, bacakan suara, cari teks di semua berkas, catatan, cetak, pindai QR dari kamera.
- Folder DepUp Office terlihat di aplikasi Files.
