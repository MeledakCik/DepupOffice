# 🚀 Depup Office v1.0.0 Release Notes

**Depup Office** is a 100% offline, privacy-first, lightweight Android mobile office and document scanner. Powered by on-device ML Kit OCR and Android native APIs, Depup Office delivers fast PDF processing, document editing, and smart camera scanning without sending your private files to cloud servers.

---

## 🌟 Complete Feature List in v1.0.0

### 📱 1. Document Management & File System Integration
* **System File Picker (SAF)**: 1-tap open for PDF, Word (`.docx`), and Excel (`.xlsx`) directly via Android's System File Manager.
* **Organize PDF Pages**: Reorder, rotate 90°, or delete specific pages with interactive thumbnail controls.
* **PDF Merge & Split**: Merge multiple PDFs in custom order or split by page ranges.
* **PDF to Image & Image Extractor**: Convert PDF pages to JPGs or extract embedded images directly to your Gallery.
* **Target-Size PDF Compression**: Compress PDFs precisely to fit online portal limits (`< 200 KB`, `< 500 KB`, `< 1 MB`, `< 2 MB` for SSCASN, CPNS, BUMN, and job portals).
* **Password Protection**: Encrypt PDFs with custom passwords.

### 📷 2. Smart Camera Scanner & OCR
* 🪪 **KTP / ID Card 2-Side Layout**: Capture Front & Back ID photos; automatically arranges them on a single clean A4 page.
* 📸 **Multi-Format Export (WPS Style)**: Choose output format in 1 tap — **📷 Pure Image (JPG)**, **📄 Searchable PDF**, or **📝 Editable Word (.docx)**.
* 🧾 **Receipt & Expense Scanner**: Auto-detects **Merchant Name**, **Date**, and **Total Amount (Rp)** with 1-click export to Excel (`.xlsx`).
* 🌐 **On-Device OCR Translator**: Instant offline document translation between English and Indonesian.
* 📌 **Smart Document Summary**: Automatically extracts critical dates, contract/document numbers, and monetary figures.

### 🔒 3. Privacy, Security & Watermark
* ⬛ **Blackout / Redaction Tool**: Permanent solid black redaction overlay over sensitive info (NIK, phone number, address) before exporting.
* 🏷️ **Watermark Stamping**: Diagonal semi-transparent custom watermarks (*"HANYA UNTUK VERIFIKASI"*, *"DOKUMEN RAHASIA"*, *"COPY"*).
* 🔐 **AES-256 Vault with Biometric Lock**: Encrypted storage protected with PIN and **Fingerprint / Biometric / Screen Lock**.

### ✍️ 4. Digital Signatures, PNG Extractor & QR Manager
* 🖋️ **Transparent PNG Signature Extractor**: Turns photos of wet signatures or paper stamps into transparent PNGs with 1 click.
* 📱 **QR Code / Barcode Manager**: Scan verification QR codes or generate custom QR codes to save as PNG or stamp onto documents.
* 🖊️ **Digital Signature Placement**: Draw signatures on-screen and stamp onto any PDF page position.

### 💻 5. Reader, Templates & Fullscreen Presenter Mode
* 🎯 **Fullscreen Slideshow + Laser Pointer**: Present PDFs in fullscreen with an interactive glowing digital laser pointer that follows your touch.
* 👁️ **Eye-Care Paper Themes**: 4 reader themes including **Day**, **Night**, **Sepia Parchment**, and **Eye-Care Soft Green**.
* 📜 **Ready-To-Use Templates**: Includes Job Application Letter, CV / Resume, Receipt, and Invoice templates ready to customize.

---

## 📦 Build Artifacts

| File Name | Format | Size | Target Platform |
| :--- | :---: | :---: | :--- |
| `app-release.apk` | Signed APK | ~64.7 MB | APKPure / Direct Installation |
| `app-release.aab` | Signed App Bundle | ~30.0 MB | Google Play Console |

---

### 🔑 Signing Info
* **Keystore**: `depup-office.jks`
* **Alias**: `depup`
* **SHA-256 / AES Encryption**: Native Android KeyStore
