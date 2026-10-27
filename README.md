# 🚀 DepupOffice_v2.2.0

**DepupOffice_v2.2.0** is a 100% offline, privacy-first, lightweight Android mobile office, document editor, spreadsheet manager, smart camera scanner, direct WiFi/Bluetooth printer, and voice reader. Powered by on-device Google ML Kit OCR and Android native APIs, Depup Office delivers fast PDF processing, Word document editing, Excel spreadsheet management, QR code scanning/generation, and security tools **without sending your private files to cloud servers**.

<br>

<div align="left">
  <a href="https://github.com/MeledakCik/DepupOffice/releases/tag/DepupOffice_v2.2.0">
    <img src="https://img.shields.io/badge/Download-APK%20v2.2.0-00E095?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
  </a>
  <a href="https://github.com/MeledakCik/DepupOffice/releases/tag/DepupOffice_v2.2.0">
    <img src="https://img.shields.io/badge/Download-AAB%20v2.2.0-FFB800?style=for-the-badge&logo=googleplay&logoColor=white" alt="Download AAB">
  </a>
  <img src="https://img.shields.io/badge/Privacy-100%25%20Offline-1E60D5?style=for-the-badge&logo=lock&logoColor=white" alt="100% Offline">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B-purple?style=for-the-badge&logo=android&logoColor=white" alt="Platform">
</div>

---

## 🌟 What's New in v2.2.0

### 🖨️ 1. Direct WiFi & Bluetooth Printing
* **1-Click Direct Print**: Print PDFs, Word `.docx` documents, Excel `.xlsx` spreadsheets, and photo scans (KTP 2-sided layouts, receipts, stamps) directly to any WiFi or Bluetooth printer using native Android `PrintManager`.

### 🔊 2. Offline Document Voice Reader (Text-To-Speech)
* **100% Offline Read Aloud**: Reads PDF text, Word documents, Excel tables, and OCR-scanned text out loud using native Android `TextToSpeech`.
* **Interactive Control Dialog**:
  * ⏯️ **Play, Pause, Resume, Stop** controls.
  * ⚡ **Speed Selector** (`0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`).
  * 🇮🇩 / 🇬🇧 **Language Switcher** (Indonesian `id_ID` & English `en_US`).
  * Real-time paragraph progress and preview.

### 📝 3. Native Word & Document Editor (`.docx`, `.txt`)
* **WYSIWYG Rich-Text Editing**: Text formatting (Bold, Italic, Underline, Strikethrough, Colors), alignment, list support, image & table insertion, and offline `.docx` export.

### 📊 4. Native Excel & Spreadsheet Editor (`.xlsx`, `.csv`)
* **Full Grid Spreadsheet Interface**: Multi-column, multi-row editor with formula evaluation (`SUM`, `AVERAGE`, `MIN`, `MAX`, `COUNT`), cell styling, and export to `.xlsx` and `.csv`.

### 📱 5. QR Code & Barcode Manager
* **Scanner & Generator**: High-speed QR Code/Barcode scanning and generator with direct PDF stamping.

### 👁️ 6. Eye-Care Paper Reading Themes
* **4 Reader Modes**: Day, Night Dark (`#0F172A`), Sepia Parchment (`#FBF0D9`), and Anti-Glare Soft Green (`#E2F0D9`).

### 📷 7. Smart Camera Scanner & OCR
* **KTP 2-Sided Layout**: Guided capture that places Front & Back ID photos on 1 clean A4 page. Receipt scanner, OCR translator, and smart summary.

### 🔒 8. Privacy, Security & Biometric Vault
* **AES-256 Vault**: Encrypted storage protected by Fingerprint / Biometric / Screen Lock. Permanent blackout redaction & watermarking.

---

## 🛡️ 100% Offline & Privacy Guarantee

DepupOffice_v2.2.0 operates **completely offline**.
- 🚫 **No Internet Permission**: `android.permission.INTERNET` is excluded from `AndroidManifest.xml`.
- 🔐 **Zero Cloud Uploads**: All processing, OCR, encryption, text-to-speech, and printing happen 100% locally on your device.

---

## 📦 Build Artifacts

| File Name | Description | Target Platform |
| :--- | :--- | :--- |
| `DepupOffice_v2.2.0.apk` | Universal Signed APK (~64.7 MB) | Direct Installation |
| `DepupOffice_v2.2.0.aab` | Signed Android App Bundle (~30.0 MB) | Google Play Console |

---

## 💻 Building from Source

```bash
# Clone the repository
git clone https://github.com/MeledakCik/DepupOffice.git
cd DepupOffice

# Build Debug APK
./gradlew assembleDebug

# Build Release APK & Bundle
./gradlew assembleRelease bundleRelease
```

The compiled release APK will be located at:
`app/build/outputs/apk/release/DepupOffice_v2.2.0.apk`
