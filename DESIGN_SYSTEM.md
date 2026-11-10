# Depup Office — Sistem Desain "Depup Fresh" (v3)

Sumber kebenaran: `app/src/main/java/cloud/depup/office/Ui.kt` (object `C`, `Theme`, dan komponen).
Semua layar memakai token ini, sehingga tema **terang/gelap** berganti otomatis tanpa mengubah layar.

## Prinsip
1. **Praktis** — aksi paling sering (Scan) selalu satu ketukan dari mana pun; tiap layar punya satu tujuan jelas.
2. **Rapi** — satu keluarga huruf (Inter Tight), radius 12–16dp, jarak kelipatan 4dp, daftar dikelompokkan dalam satu kartu bergaris pemisah.
3. **Mudah dimengerti** — label teks selalu tampil di navigasi & tombol alat; warna jenis berkas konsisten (PDF merah, Word biru, Excel hijau, Gambar ungu).
4. **Aksesibel** — lihat bagian Aksesibilitas.

## Navigasi
`Beranda · Dokumen · [Scan] · Alat · Saya`

| Tujuan | Isi |
|---|---|
| Beranda | Sapaan, kolom cari, kartu Scan, pintasan Buka PDF/Word/Excel/Lainnya, Buat baru, Template, Terakhir dibuka |
| Dokumen | Cari langsung, segmen Terkini / Ditandai / Hasil olahan (+ chip kategori) |
| Scan | Tombol tengah, langsung membuka kamera |
| Alat | Dikelompokkan: Kelola PDF · Gambar & foto · Tanda tangan & keamanan · Bagikan & ekspor |
| Saya | Brankas, privasi, penyimpanan, **tema (Ikuti HP/Terang/Gelap)**, ukuran huruf, format bawaan, dukungan |

## Token warna
| Token | Terang | Gelap |
|---|---|---|
| `BG` | #F5F6FA | #0E1016 |
| `SURFACE` | #FFFFFF | #181B23 |
| `SURFACE2` | #EEF0F5 | #232733 |
| `SURFACE3` | #E3E6EE | #2E3342 |
| `LINE` | #D5D9E3 | #373C4C |
| `LINE_STRONG` | #7C8295 | #6A7184 |
| `TEXT` | #151823 | #ECEEF4 |
| `MUTED` | #535A6C | #A9AFC1 |
| `PRIMARY` | #4338CA | #A8B1FF |
| `ON_PRIMARY` | #FFFFFF | #171452 |
| `PRIMARY_C` | #E7E6FC | #2E2B6E |
| `ON_PRIMARY_C` | #2E2A87 | #E2E4FF |
| `SUCCESS` | #15703A | #5BD88A |
| `SUCCESS_C` | #DCF5E4 | #15301F |
| `WARN_T` | #8A4508 | #F6C35A |
| `WARN_C` | #FDF0CC | #372A0C |
| `ERR` | #C0262D | #FF8A8A |
| `ERR_C` | #FCE4E4 | #3A1618 |
| `PDF` | #B42318 | #FF9C90 |
| `PDF_C` | #FDE4E1 | #3B1A18 |
| `DOC` | #1D4ED8 | #93B6FF |
| `DOC_C` | #DDE8FD | #182A52 |
| `XLS` | #157A3B | #7DDDA0 |
| `XLS_C` | #DBF3E2 | #15301F |
| `IMG` | #7A2BC4 | #D3A8FF |
| `IMG_C` | #F1E6FD | #2E1B47 |
| `AMBER` | #F5A524 | #F5B53D |

Teks di atas warna isian bebas memakai `C.on(bg)` (memilih hitam/putih yang kontrasnya lebih tinggi).
UI kamera & panel foto selalu gelap; halaman dokumen (Word/PDF) selalu "kertas putih" seperti di Word.

## Kontras (WCAG 2.1)
| Pasangan | Token | Terang | Gelap |
|---|---|---|---|
| Teks utama di latar | `TEXT` / `BG` | 16.4 : 1 | 16.4 : 1 |
| Teks utama di kartu | `TEXT` / `SURFACE` | 17.7 : 1 | 14.8 : 1 |
| Teks sekunder di latar | `MUTED` / `BG` | 6.4 : 1 | 8.7 : 1 |
| Teks sekunder di kolom/chip | `MUTED` / `SURFACE2` | 6.0 : 1 | 6.8 : 1 |
| Tautan/aksen di kartu | `PRIMARY` / `SURFACE` | 7.9 : 1 | 8.5 : 1 |
| Teks tombol utama | `ON_PRIMARY` / `PRIMARY` | 7.9 : 1 | 8.3 : 1 |
| Teks di kartu sorotan | `ON_PRIMARY_C` / `PRIMARY_C` | 9.5 : 1 | 9.9 : 1 |
| Status sukses | `SUCCESS_T` / `SUCCESS_C` | 7.9 : 1 | 11.4 : 1 |
| Peringatan | `WARN_T` / `WARN_C` | 6.3 : 1 | 8.6 : 1 |
| Galat | `ERR_T` / `ERR_C` | 7.7 : 1 | 11.0 : 1 |
| Lencana PDF | `PDF` / `PDF_C` | 5.4 : 1 | 7.7 : 1 |
| Lencana Word | `DOC` / `DOC_C` | 5.4 : 1 | 7.0 : 1 |
| Lencana Excel | `XLS` / `XLS_C` | 4.6 : 1 | 8.6 : 1 |
| Lencana Gambar | `IMG` / `IMG_C` | 5.9 : 1 | 7.9 : 1 |
| Batas kontrol (non-teks, min 3:1) | `LINE_STRONG` / `SURFACE` | 3.8 : 1 | 3.5 : 1 |

Semua teks ≥ 4,5 : 1 (AA), kontrol non-teks ≥ 3 : 1.

## Tipografi (sp, mengikuti ukuran huruf sistem)
| Peran | Ukuran | Bobot |
|---|---|---|
| Judul layar | 22–24 | Bold |
| Judul seksi | 18–19 | Bold, ditandai *heading* |
| Isi / judul baris | 15–16 | SemiBold |
| Keterangan | 13–14 | Regular |
| Minimum mutlak | 12 | (dijaga oleh `tv()`, `MIN_SP`) |

## Komponen (Ui.kt)
`btn` (utama/tonal, 48dp), `outlineBtn`, `iconBtn` (48dp + label TalkBack + tooltip), `ImageView.asButton()`,
`tappable()`, `field` (cincin fokus 2dp), `card`, `listCard` (MainActivity), `actionRow`, `tile`, `segmented`,
`filterChip`, `emptyState`, `sheet` + `sheetHeader` (tombol Tutup berlabel), `headingTv`.

## Aksesibilitas
- Target sentuh minimal **48×48dp** untuk semua tombol, baris, chip (≥ 40dp + jarak), item navigasi 56dp.
- Setiap tombol ikon punya `contentDescription` (dan tooltip saat ditekan lama). Ikon dekoratif disembunyikan dari pembaca layar.
- Baris berkas dibacakan sebagai satu kalimat: nama, jenis, ukuran, waktu; aksi ⋮ dan penanda punya label sendiri.
- Judul seksi ditandai `isAccessibilityHeading` sehingga TalkBack bisa melompat per judul.
- Status terpilih (tab, segmen, chip, alat pita) diumumkan (`isSelected` / checkable).
- Teks minimal 12sp, mengikuti setelan ukuran huruf HP; halaman Privasi juga mengikuti (`textZoom`).
- Animasi pendek (≤ 320ms) dan otomatis mati bila "Hapus animasi" aktif di HP.
- Mode gelap ikut sistem atau dipilih manual di **Saya › Tampilan**.
