# 🚀 DepupOffice_v2.1.0

**DepupOffice_v2.1.0** is a 100% offline, privacy-first, lightweight Android mobile office, document editor, spreadsheet manager, and smart camera scanner. Powered by on-device Google ML Kit OCR and Android native APIs, Depup Office delivers fast PDF processing, Word document editing, Excel spreadsheet management, QR code scanning/generation, and security tools **without sending your private files to cloud servers**.

<br>

<div align="left">
  <a href="https://github.com/MeledakCik/DepupOffice/releases/tag/DepupOffice_v2.1.0">
    <img src="https://img.shields.io/badge/Download-APK%20v2.1.0-00E095?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
  </a>
  <a href="https://github.com/MeledakCik/DepupOffice/releases/tag/DepupOffice_v2.1.0">
    <img src="https://img.shields.io/badge/Download-AAB%20v2.1.0-FFB800?style=for-the-badge&logo=googleplay&logoColor=white" alt="Download AAB">
  </a>
  <img src="https://img.shields.io/badge/Privacy-100%25%20Offline-1E60D5?style=for-the-badge&logo=lock&logoColor=white" alt="100% Offline">
  <img src="https://img.shields.io/badge/Platform-Android%2010%2B-purple?style=for-the-badge&logo=android&logoColor=white" alt="Platform">
</div>

---

## 🌟 What's New in v2.1.0

### 📝 1. Native Word & Document Editor (`.docx`, `.txt`)
* **WYSIWYG Rich-Text Editing**: Edit Word documents directly on your phone with text formatting (Bold, Italic, Underline, Strikethrough, Text Color, Background Highlight).
* **Paragraph & Structure Alignment**: Left, Center, Right, and Justified alignment with bulleted/numbered list support.
* **Image & Table Insertion**: Insert photos from Gallery or camera and build structured tables directly inside your `.docx` files.
* **On-Device Docx Engine**: Save and export files instantly to standard Microsoft Word `.docx` format completely offline.

### 📊 2. Native Excel & Spreadsheet Editor (`.xlsx`, `.csv`)
* **Full Grid Spreadsheet Interface**: Interactive multi-column, multi-row spreadsheet editor with smooth scrolling and cell selection.
* **Formula & Calculation Engine**: Built-in evaluation for common functions (`SUM`, `AVERAGE`, `MIN`, `MAX`, `COUNT`).
* **Cell Formatting & Styling**: Background fill colors, text styles, alignment, and column width adjustments.
* **Export to XLSX & CSV**: Instant conversion and saving to standard Microsoft Excel `.xlsx` and `.csv` formats.

### 📱 3. QR Code & Barcode Manager
* **Instant Scanner**: High-speed camera scanner for QR codes, Barcodes (EAN-13, Code 128, UPC), and e-Meterai document verification codes.
* **Custom QR Generator**: Create custom QR codes for URLs, text, phone numbers, WhatsApp links, or document IDs.
* **Direct Stamp to PDF**: Stamp generated QR codes directly onto any PDF page with customizable position and size.

### 👁️ 4. Eye-Care Paper Reading Themes
* **4 Eye-Friendly Reader Modes**:
  * ☀️ **Day Mode** (Classic Crisp White)
  * 🌙 **Night Mode** (Inverted Dark `#0F172A` to prevent screen flare)
  * 📜 **Sepia Parchment** (Warm Warm Book Paper `#FBF0D9`)
  * 🌿 **Eye-Care Soft Green** (Anti-Glare Soft Green `#E2F0D9` designed to reduce eye fatigue during extended reading sessions)

### 📷 5. Smart Camera Scanner & On-Device OCR
* 🪪 **KTP / ID Card 2-Sided Layout**: Guided camera capture for Front & Back ID photos that automatically places both sides on a single clean A4 page with official borders and header.
* 📸 **Multi-Format Export**: 1-tap export to **Pure Image (JPG)**, **Searchable PDF**, or **Editable Word (.docx)**.
* 🧾 **Receipt & Expense Scanner**: Auto-detects **Merchant Name**, **Date**, and **Total Amount (Rp)** with 1-click export to Excel (`.xlsx`).
* 🌐 **On-Device OCR Translator**: Offline document text translation between English and Indonesian.
* 📌 **Smart Document Summary**: Extracts crucial dates, contract/document numbers, and monetary figures into a shareable summary card.

### 🔒 6. Privacy, Security & Biometric Vault
* ⬛ **Blackout / Redaction Tool**: Permanent solid black redaction boxes over sensitive NIK, phone number, and address fields before sharing.
* 🏷️ **Custom Watermark Stamping**: Semi-transparent diagonal watermarks (*"HANYA UNTUK VERIFIKASI"*, *"DOKUMEN RAHASIA"*, *"COPY"*).
* 🔐 **AES-256 Biometric Vault**: Hardware-encrypted vault protected by PIN and native Android **Fingerprint / Biometric / Screen Lock**.

### ✍️ 7. Transparent Signature Extractor & Presenter Mode
* 🖋️ **1-Tap Signature Extractor**: Converts photos of wet signatures or paper company stamps into transparent PNGs with automated background removal.
* 🎯 **Fullscreen Presenter + Laser Pointer**: Present PDF slides in fullscreen with an interactive glowing digital laser pointer following finger touch.

### 📜 8. Ready-To-Use Office Templates
* **Job Application Letter (Surat Lamaran Kerja)**: HRD-standard letter template in editable `.docx`.
* **Curriculum Vitae (CV / Resume)**: Clean, professional resume structure.
* **Payment Receipt & Invoice**: Pre-filled official payment receipt and sales invoice spreadsheet in `.xlsx`.

---

## 🛡️ 100% Offline & Privacy Guarantee

DepupOffice_v2.1.0 operates **completely offline**.
- 🚫 **No Internet Permission**: `android.permission.INTERNET` is excluded from `AndroidManifest.xml`.
- 🔐 **Zero Cloud Uploads**: All OCR processing, document parsing, encryption, and PDF conversions happen 100% locally on your device's CPU/NPU.
- 📁 **System File Picker (SAF)**: Uses Android's native Storage Access Framework for secure file access without unnecessary broad storage permissions.

---

## 🛠️ Technical Specifications & Tech Stack

| Component | Technology / Library |
| :--- | :--- |
| **Language** | 100% Kotlin |
| **Minimum SDK** | Android 10 (API Level 29) |
| **Target SDK** | Android 14 (API Level 34) |
| **UI Architecture** | AndroidX Material 3, AppCompat, RecyclerView |
| **Camera Engine** | CameraX 1.3.4 (Lifecycle & Camera2) |
| **OCR & Vision** | Google ML Kit Text Recognition 16.0.1 (On-Device) |
| **Barcode Engine** | Google ML Kit Barcode Scanning 17.2.0 & ZXing Core 3.5.3 |
| **Security** | Android KeyStore, Biometric Prompt API, AES-256-GCM |

---

## 📦 Build Artifacts

| File Name | Description | Target Platform |
| :--- | :--- | :--- |
| `DepupOffice_v2.1.0.apk` | Universal Signed APK (~64.7 MB) | Direct Sideload / APKPure / Alt Stores |
| `DepupOffice_v2.1.0.aab` | Signed Android App Bundle (~30.0 MB) | Google Play Console |

---

## 💻 Building from Source

### Prerequisites
- Android Studio Jellyfish (2023.3.1) or higher
- JDK 17 (Java Development Kit)
- Android SDK 34

### Local Build Steps
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
`app/build/outputs/apk/release/DepupOffice_v2.1.0.apk`

---

## 📄 License & Credits

Developed with ❤️ for job seekers, office professionals, students, and businesses who value maximum privacy, performance, and document productivity.
