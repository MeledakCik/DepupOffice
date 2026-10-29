# 🚀 DepupOffice_v2.3.0

**DepupOffice_v2.3.0** is a 100% offline, privacy-first, lightweight Android mobile office, document editor, spreadsheet manager, CamScanner-style AI document scanner (OpenCV + Jetpack Compose), direct WiFi/Bluetooth printer, and voice reader. Powered by OpenCV Android SDK, Google ML Kit OCR, and Android native APIs, Depup Office delivers fast PDF processing, Word document editing, Excel spreadsheet management, QR code scanning/generation, and security tools **without sending your private files to cloud servers**.

<br>

<div align="left">
  <a href="https://github.com/MeledakCik/DepupOffice/releases/tag/DepupOffice_v2.3.0">
    <img src="https://img.shields.io/badge/Download-APK%20v2.3.0-00E095?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
  </a>
  <a href="https://github.com/MeledakCik/DepupOffice/releases/tag/DepupOffice_v2.3.0">
    <img src="https://img.shields.io/badge/Download-AAB%20v2.3.0-FFB800?style=for-the-badge&logo=googleplay&logoColor=white" alt="Download AAB">
  </a>
  <img src="https://img.shields.io/badge/Privacy-100%25%20Offline-1E60D5?style=for-the-badge&logo=lock&logoColor=white" alt="100% Offline">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B-purple?style=for-the-badge&logo=android&logoColor=white" alt="Platform">
</div>

---

## 🌟 What's New in v2.3.0

### 📷 1. CamScanner Auto-Crop Document Scanner (OpenCV + Jetpack Compose)
* **Auto-Detect Document Contours**: OpenCV Canny edge & contour analysis detects 4 corners of paper/documents on cluttered tables.
* **Interactive 4-Corner Draggable Points**: Fine-tune crop bounds manually with 4 draggable corner handles on Compose Canvas.
* **Perspective Correction (Warp)**: Flattens skewed photos into a perfectly straight rectangular document.
* **3 Document Filters**:
  * 🖼️ **Original** (Cropped photo)
  * ✨ **Magic Color / Enhanced** (Boosted contrast & paper brightness)
  * 🖤 **Black & White** (Adaptive threshold photocopy effect)
* **Multi-Page PDF Export**: Saves merged PDF documents directly into `/Documents/DepupOffice/`.

### 🖨️ 2. Direct WiFi & Bluetooth Printing
* **1-Click Direct Print**: Print PDFs, Word `.docx`, Excel `.xlsx`, and photo scans directly to any WiFi or Bluetooth printer using `PrintManager`.

### 🔊 3. Offline Document Voice Reader (Text-To-Speech)
* **100% Offline Read Aloud**: Reads PDF text, Word documents, Excel tables, and OCR text out loud using native Android `TextToSpeech`.
* Controls for Play, Pause, Stop, Speed (`0.75x`, `1.0x`, `1.25x`, `1.5x`, `2.0x`), and Language (Indonesian `id_ID` & English `en_US`).

### 📝 4. Native Word & Excel Editors
* Full WYSIWYG Word `.docx` editor and Excel `.xlsx` spreadsheet editor with formula calculations (`SUM`, `AVERAGE`, `MIN`, `MAX`, `COUNT`).

### 📱 5. QR Code & Barcode Manager
* High-speed QR Code/Barcode scanning and generator with direct PDF stamping.

### 🔒 6. Privacy, Security & Biometric Vault
* AES-256 encrypted vault protected by Fingerprint / Biometric / Screen Lock. Permanent blackout redaction & watermarking.

---

## 🛠️ Technical Specifications & Tech Stack

| Component | Technology / Library |
| :--- | :--- |
| **Language** | 100% Kotlin |
| **Min SDK / Target SDK** | Android 10 (API 29) / Android 14 (API 34) |
| **UI Framework** | Jetpack Compose + Material 3, AppCompat |
| **Computer Vision Engine** | OpenCV Android SDK (`com.quickbirdstudios:opencv:4.5.3.0`) |
| **Camera Engine** | CameraX 1.3.4 (Lifecycle & Camera2) |
| **OCR & Barcode** | Google ML Kit Text Recognition 16.0.1 & ZXing Core 3.5.3 |
| **PDF Generation** | Native Android `PdfDocument` & `PdfRenderer` |

---

## 📦 Build Artifacts

| File Name | Description | Target Platform |
| :--- | :--- | :--- |
| `DepupOffice_v2.3.0.apk` | Universal Signed APK (~67.5 MB) | Direct Installation |
| `DepupOffice_v2.3.0.aab` | Signed Android App Bundle (~32.0 MB) | Google Play Console |

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
`app/build/outputs/apk/release/DepupOffice_v2.3.0.apk`
