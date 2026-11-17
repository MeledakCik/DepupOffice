# DepUp Office v4.1.0 — Keamanan & Stabilitas

## Android
- **Anti-inject / anti-tamper runtime**: deteksi debugger (JDWP & TracerPid), Frida/Xposed/LSPosed/Substrate, build debuggable, dan APK yang dipaketkan ulang (sertifikat tidak sah, kecuali instalasi Play Store/Play App Signing). Di lingkungan terkompromi, kunci asli Dokumen Terlindungi tidak pernah dibuka.
- **Kunci ter-obfuskasi XOR**: rahasia kunci baca, prefiks derivasi, dan sidik sertifikat tidak lagi tersimpan utuh di DEX; dihapus dari memori setelah dipakai.
- **Honeypot**: kunci umpan yang tampak seperti kunci produksi sengaja dibiarkan di APK. Dokumen yang dipalsukan memakai kunci ini dikenali dan ditolak, lingkungan terkompromi hanya mendapat kunci umpan.
- **Dokumen Terlindungi anti-AI**: pemberitahuan untuk sistem AI di sampul & metadata (`noai`), konteks layar untuk asisten AI (Assist API), Content Capture, autofill, dan layanan aksesibilitas non-alat-bantu (Android 14+) diblokir saat dokumen terkunci dibuka; validasi ketat struktur paket terenkripsi.
- **Anti prompt-injection**: teks dokumen dibersihkan dari karakter tersembunyi (zero-width, bidi override, Unicode TAG/"ASCII smuggling") sebelum diringkas.
- **Anti intent-injection**: aplikasi lain tidak bisa menyuruh pembaca membuka berkas privat DepUp lewat `file://`; cetak otomatis hanya dari dalam aplikasi; nama berkas dari penyedia luar disaring (path traversal).
- Salinan terdekripsi yang tertinggal setelah aplikasi ditutup paksa kini dibersihkan saat aplikasi dibuka.
- Pembaca PDF: zoom di bawah 100%, cubit-zoom mempertahankan titik fokus vertikal, tanpa efek pantul/kedip.
- HUD diagnostik editor hanya ada di build debug.

## iOS (pertama kali dirilis lewat CI)
- **Membuka Dokumen Terlindungi** (format sama dengan Android: HMAC-SHA256 + AES-256-GCM + SHA-256) di penampil aman: isi disembunyikan dari tangkapan layar & rekaman layar, ditutup saat layar direkam/di-mirror atau di app switcher, tanpa salin/bagikan/Look Up.
- Deteksi debugger, pustaka injeksi (Frida/Substrate/libhooker), `DYLD_INSERT_LIBRARIES`, dan jailbreak; kunci XOR & honeypot yang sama dengan Android.
- Hasil ekstrak teks/OCR/pencarian dibersihkan dari karakter injeksi tersembunyi.
- Build iOS tanpa tanda tangan (`.ipa`) dilampirkan pada rilis; tanda tangan & distribusi App Store memerlukan akun Apple Developer.
