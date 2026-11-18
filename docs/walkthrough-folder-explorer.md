# Walkthrough - Pemeriksa Folder & Pengelola Kategori Berkas `sdcard/DepupOffice`

## Ringkasan Fitur Baru yang Ditambahkan

### 1. Eksplor & Manajer Folder DepupOffice (`showFolderExplorerSheet`)
- **Tampilan Informasi Folder**: Menampilkan total berkas tersimpan dan ukuran total folder pada lokasi publik `sdcard/DepupOffice`.
- **Filter Kategori Format Berkas**:
  - `SEMUA`: Menampilkan seluruh berkas tersimpan.
  - `PDF`: Menyaring dokumen PDF (`.pdf`).
  - `Word`: Menyaring dokumen Word (`.docx`, `.doc`).
  - `Excel`: Menyaring spreadsheet (`.xlsx`, `.xls`, `.csv`).
  - `Gambar`: Menyaring gambar dan hasil scan (`.jpg`, `.png`).
- **Tindakan Berkas Lengkap**:
  - **Buka**: Membuka dokumen langsung di Reader.
  - **Edit Dokumen**: Membuka dokumen di Editor Word / Editor Excel.
  - **Ubah Nama (Rename)**: Mengganti nama file fisik di memori.
  - **Hapus File**: Menghapus file fisik dari perangkat setelah konfirmasi.
  - **Bagikan**: Mengirim berkas via WhatsApp / Menu Bagikan.

---

### 2. Integrasi Tab "Simpan" & "Baca"
- **Tab Simpan**: Dilengkapi kartu utama *Manajer Folder DepupOffice* yang menampilkan statistik berkas per kategori (PDF, Word, Excel, Gambar) dan tombol "Eksplor Isi Folder DepupOffice".
- **Tab Baca**: Mengetuk ubin kategori *Buka PDF*, *Buka Word*, *Buka Excel*, atau *Semua Berkas* langsung membuka Folder Explorer tersaring sesuai kategori.

---

## Verifikasi & Pengujian
1. **Build & Kompilasi**: `gradle_build("app:assembleDebug")` berhasil tanpa error.
2. **Pengujian pada Perangkat**:
   - Tab Simpan & Tab Baca menampilkan kategori dan jumlah berkas `sdcard/DepupOffice` secara dinamis.
   - Folder Explorer sheet membuka daftar berkas, filter kategori bekerja akurat (PDF, Word, Excel, Gambar), dan menu opsi file (Buka, Edit, Rename, Hapus, Bagikan) berfungsi dengan lancar.
