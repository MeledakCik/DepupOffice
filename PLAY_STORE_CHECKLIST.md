# Depup Office v2.2.1 - Play Store Checklist

## Build
    cp keystore.properties.example keystore.properties   # isi kredensial upload key
    ./gradlew clean bundleRelease
    -> app/build/outputs/bundle/release/DepupOffice_v2.2.1-release.aab
Pastikan versionCode (4) > versionCode terakhir yang pernah di-upload.

## Data Safety (isi di Play Console)
- Does your app collect or share any required user data types? -> **No**
- Data collected: none | Data shared: none
- Data encrypted in transit: n/a (tidak ada transmisi)
- Deletion request: n/a
- Permission: hanya CAMERA (scan dokumen/QR, diproses on-device). INTERNET di-strip via tools:node="remove".
- Privacy policy URL: WAJIB isi URL publik (policy in-app di assets/privacy.html saja tidak cukup).

## Store listing
- App icon 512x512 PNG: ic_launcher_playstore_512.png (full-square, Play yang kasih mask)
- Feature graphic 1024x500: feature_graphic_1024x500.png (placeholder simpel, ganti kalau mau ada teks)
- Screenshot: min 2 (phone), disarankan 4-8; tablet 7"/10" kalau mau tampil di tablet
- Target audience/content rating questionnaire, Ads declaration: No ads
- Pakai Play App Signing (default untuk AAB)

## Policy flags (cek manual)
- Link donasi Saweria di MainActivity: cek Payments policy; aman kalau cuma tip/donasi sukarela tanpa unlock fitur.
- ViewerActivity intent-filter `*/*` + BROWSABLE: boleh, tapi review kalau Play bertanya.

## Library native (16 KB page size, wajib untuk target API 35+)
- com.google.mlkit:text-recognition:16.0.1  -> sudah 16 KB
- com.google.mlkit:barcode-scanning:17.3.0  -> sudah 16 KB (17.2.0 sebelumnya TIDAK)
- Verifikasi di Android Studio: Build > Analyze APK > lib/arm64-v8a/*.so (alignment 16 KB), atau lihat warning saat build.

## Uji manual sebelum upload (belum bisa diuji dari sisi AI)
- Scan dokumen > Auto-crop AI / Luruskan / Teks (OCR) pada foto asli (meja gelap, meja terang, bayangan)
- Ubah nama: (a) PDF dibuka dari file manager, (b) file hasil scan, (c) JPG di Pictures/Depup Office; cek nama di file manager berubah
- Layar Kamera/Editor di Android 15/16 (edge-to-edge): konten tidak tertimpa status bar/nav bar
