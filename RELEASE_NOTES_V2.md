# 🚀 Depup Office v2.3.0 Major Release Notes

**Depup Office v2.3.0** brings a CamScanner-style AI document scanner powered by OpenCV Android SDK and Jetpack Compose, featuring automatic 4-corner document contour detection, interactive draggable corner points, perspective correction (warp), image enhancement filters, and multi-page PDF generation saved to `/Documents/DepupOffice/`.

---

## 🌟 What's New in v2.3.0

### 📷 1. CamScanner Auto-Crop Document Scanner (OpenCV + Jetpack Compose)
* **Auto-Detect Document Contours**: OpenCV-powered Canny Edge Detection & Contour Analysis automatically detects 4 corners of HVS paper / notebook pages on messy desks.
* **Interactive 4-Corner Draggable Points**: Fine-tune crop bounds manually with 4 color-coded draggable corner point handles on a Compose Canvas.
* **Perspective Correction (Warp Perspective)**: Automatically straightens skewed/tilted document photos into a flat rectangular document.
* **3 Document Filters**:
  * 🖼️ **Original**: Cropped photo as-is.
  * ✨ **Magic Color / Enhanced**: Increased contrast & brightness for clean paper readability.
  * 🖤 **Black & White**: Adaptive Threshold photocopy effect for crisp text.
* **Multi-Page Management**: Capture/pick multiple photos from CameraX or Gallery, crop each page, re-order or delete pages.
* **Automatic PDF Generation**: Merges all pages into a single clean PDF file saved directly in `/Documents/DepupOffice/`.

### 🖨️ 2. Direct Printing & Offline Voice Reader
* Direct 1-click printing to WiFi/Bluetooth printers via `PrintHelper`.
* 100% Offline Text-to-Speech Voice Reader for PDFs, Word `.docx`, Excel `.xlsx`, and OCR text in Indonesian & English.

### 🛡️ 3. Play Store Compliance & Saweria Donation
* Includes local Privacy Policy Activity (`file:///android_asset/privacy.html`).
* Direct external Saweria donation integration (`https://saweria.co/depupdonate`).

---

## 📦 Build Artifacts

| File Name | Format | Size | Target Platform |
| :--- | :---: | :---: | :--- |
| `DepupOffice_v2.3.0.apk` | Universal Signed APK | ~67.5 MB | Direct Installation |
| `DepupOffice_v2.3.0.aab` | Signed App Bundle | ~32.0 MB | Google Play Console |
