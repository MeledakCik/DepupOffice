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
