# Rencana migrasi iOS DepUp Office

Android dan iOS kini berada dalam satu repo:

- Android: `app/` (Kotlin + Android SDK)
- iOS: `iosApp/` (SwiftUI + framework Apple)

Antarmuka dan integrasi perangkat dibuat native per platform. `TabView`, `NavigationStack`, dan toolbar sistem dipakai agar navigasi iOS mendapat Liquid Glass bawaan iOS 26 tanpa meniru efeknya secara manual. Target minimum iOS 18 menjaga jangkauan perangkat yang lebih luas; versi sebelumnya memakai gaya sistem native yang tersedia.

## Status parity fitur

Porting sedang berjalan; build iOS belum menyamai seluruh fitur Android. Fondasi dan fitur yang sudah tersedia di iOS:

- Navigasi utama, impor banyak berkas, pustaka dokumen lokal, preview berkas melalui Quick Look, dan scan multi-halaman menjadi PDF.
- Gabung/pisah PDF, rotasi halaman, nomor halaman, watermark PDF, kompresi dengan rasterisasi, ekspor PDF ke JPG/RTF, ekstraksi teks, pencarian teks PDF, OCR ke TXT, dan gambar ke PDF.
- QR generator, pemindaian QR/barcode dari gambar, kompresi foto, watermark foto, dan konversi foto massal ke PDF.
- Berbagi berkas melalui lembar berbagi iOS, editor teks sederhana yang menyimpan RTF, editor tabel sederhana yang menyimpan CSV, PDF berkata sandi, dan brankas AES-GCM dengan kunci di Keychain serta autentikasi perangkat.
- Susun & putar halaman PDF (urutkan, hapus, putar per halaman, sisipkan halaman kosong), tanda tangan PDF (anotasi tinta vektor), dan pindai QR/barcode langsung dari kamera.
- **Dokumen Terlindungi setara Android**: membuat dan membuka format v3 (kode akses, PBKDF2-SHA256 600.000 iterasi → HKDF → AES-256-GCM + HMAC-SHA256), membuka format v1 (DepUp 4.1.0) dengan honeypot, kode akses diingat di Keychain, ekspor PDF asli dengan password guru, penampil aman (anti tangkapan/rekam layar, tanpa salin/Look Up), watermark ID dokumen per menit, PT_DENY_ATTACH, serta deteksi debugger/injeksi/jailbreak/aplikasi ditandatangani ulang. Format v2 lama ("khusus perangkat Android") memang tidak bisa dibuka di perangkat lain.

Sejak 4.2.0 seluruh menu Android tersedia di iOS: alat PDF (termasuk coret-coret, susun halaman, OCR tertanam, kompres presisi, EXE ke PDF), alat foto (pembersih bayangan, pasfoto, kartu nama, TTD transparan), Excel (editor berumus, tabel/struk ke Excel, grafik), Word (editor dengan tabel, template, Word/Excel ke PDF), pembuat nota/katalog/jadwal/label harga, ringkasan, pembaca suara, presentasi, pencarian di semua berkas, catatan + widget layar utama, dan pustaka tanda tangan.

### Widget & App Group

Widget "Catatan DepUp" (`iosApp/NotesWidget`, target `NotesWidgetExtension`) membaca catatan dari App Group `group.cloud.depup.office.ios`. Saat aplikasi ditandatangani dengan akun Apple Developer, aktifkan capability **App Groups** untuk target `DepupOffice` dan `NotesWidgetExtension` dengan grup yang sama (Xcode → Signing & Capabilities; penandatanganan otomatis mendaftarkannya). Tanpa App Group, aplikasi tetap berjalan, tetapi widget tidak bisa melihat catatan.

### Uji otomatis

- iOS: `DepupOfficeTests` (24 uji, dijalankan CI di Simulator lewat `ci/ios-build.sh`).
- Android: `./gradlew testDebugUnitTest`, termasuk `CrossPlatformTest` (membuka `app/src/test/resources/ios_protected_v3.pdf` buatan penulis iOS) dan `ExeToPdfTest`.
- CI iOS berjalan di `macos-latest` (arm64). Bila runner itu penuh dan job gagal dimulai, job cadangan `macos-15-intel` berjalan otomatis. Runner bisa dipaksa lewat variabel repositori `IOS_RUNNER`.

## Format Dokumen Terlindungi

Spesifikasi byte ada di `app/src/main/java/cloud/depup/office/SecurePackage.kt` (Android) dan `SecurePackage` di `iosApp/DepupOffice/SecurityCore.swift` (iOS). Keduanya harus diubah bersamaan. Vektor uji v3 di `SecurePackageTest` (SHA-256 paket `d3fd862f…a843`) dihasilkan identik oleh Kotlin dan referensi Python independen.

Bundle ID iOS diperiksa (`cloud.depup.office.ios`): bila diganti untuk akun Apple Developer, perbarui juga `expectedBundleId` di `RuntimeIntegrity`.

Semua kemampuan iOS di atas menggunakan implementasi native Apple. Logika bisnis murni dapat dievaluasi untuk dibagikan melalui Kotlin Multiplatform setelah batas platform dan API masing-masing fitur jelas.

Modul KMP bersama belum ditambahkan. Proyek Android saat ini menggunakan AGP 9.5.1 dan Kotlin 2.2.10; tabel kompatibilitas resmi Kotlin belum mencantumkan kombinasi tersebut sebagai kompatibel. Menambahkan plugin KMP sebelum versi toolchain disejajarkan berisiko mengganggu sinkronisasi/build Android, jadi kedua aplikasi sudah satu repo tetapi masih memakai source native masing-masing.

## Build iOS

Buka `iosApp/DepupOffice.xcodeproj` dengan Xcode, pilih scheme `DepupOffice`, lalu jalankan di simulator atau perangkat iOS. Build Debug berhasil diverifikasi dengan Xcode 27.2 untuk target iOS Simulator dan perangkat setelah penambahan fitur native. Artefaknya ada di `build/ios/simulator/DepupOffice.app` dan `build/ios/device/DepupOffice.app`. Build perangkat belum ditandatangani, dan iOS Simulator runtime belum terpasang di Mac ini, jadi keduanya belum dijalankan di simulator atau perangkat fisik. Minimum deployment target adalah iOS 18.0. Orientasi potret dan lanskap diaktifkan pada iPhone serta iPad.

## CI

Workflow `.github/workflows/android-build.yml` kini juga membangun iOS di runner macOS (simulator + perangkat tanpa tanda tangan) dan melampirkan `.ipa` tanpa tanda tangan ke GitHub Release saat tag `v*` didorong.
