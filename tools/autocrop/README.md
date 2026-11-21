# Uji & pelatihan AI potong (auto-crop)

Alat pengembang — tidak ikut di-build ke APK.

## Data

- `gen6.py <seed> <jumlah> <folder> <cases.json>` — membuat foto sintetis beragam: 18 latar (kayu, marmer, granit,
  ubin, sprei, karpet, laptop, koran, …), 11 jenis dokumen (struk, A4, A4 berwarna, faktur berpita judul, KTP,
  kartu nama, halaman buku, buku terbuka 2 halaman, kertas kusut), cahaya (redup, silau, bayangan, gradasi, vinyet,
  hangat/dingin), kamera miring/perspektif, zoom (dokumen terpotong bingkai), buram & goyang, jari, ponsel, pulpen,
  kabel, mug. Ground truth = masker halaman/dokumen yang dituju.
- `gen8.py <seed> <jumlah> <folder> <cases.json>` — "putih di atas putih": kertas di atas kain/sprei/papan putih yang
  menempel 1–2 sisinya, foto terlalu terang (putih jenuh), batas kertas hanya garis bayangan tipis; latar ramai.
- `gen10.py <seed> <jumlah> <folder> <cases.json>` — dokumen BERWARNA & dokumen yang DIPEGANG: kertas warna pekat,
  papan/tanda bersudut bulat berbingkai putih, poster, sampul buku, brosur berpita warna, kartu bergradasi; adegan di
  atas meja, dipegang tangan dengan latar ruangan buram, atau tergantung di plafon (rangka besi, lampu neon, atap
  tembus cahaya); pengganggu bidang putih terang tepat di samping dokumen (lampu/jendela/silau) dan papan lain yang
  terpotong bingkai; foto terlalu terang. GT = masker dokumen utuh (bagian yang tertutup jari tetap dihitung).
- `real/` — foto ASLI dari pengguna yang pernah gagal di HP (`real/cases.json`: sudut benar dalam pecahan 0..1,
  urutan KA, KaA, KaB, KiB). **Wajib lolos** (juga diuji JVM: `app/src/test/.../AutoCropRealTest.kt`, yang membaca
  `<foto>.small.gz` — foto yang sudah diperkecil persis seperti ScanAi.small, karena classpath uji Android tidak memuat
  javax.imageio). Setiap menambah foto asli: `java -cp h.jar HarnessKt new realsmall tools/autocrop/real`.
  - `user1.png` — kertas putih di atas kain putih, batas hanya garis bayangan tipis (15-11-2026).
  - `user2.png` — papan merah tergantung di plafon: dulu yang terpilih atap tembus cahaya putih di bawahnya (20-11-2026).
  - `user3.png` — struk dipegang tangan di bawah papan merah: dulu sisi kanannya melebar ke silau lampu (20-11-2026).

- `mock/` — 17 foto CONTOH dokumen dari pengguna (21-11-2026): kartu ID di lanyard, rim kertas, label di kardus
  (krem membungkus sudut, merah muda, putih), label gantung, kartu pos, brosur dipegang tangan, kartu sempit, kartu
  nama di kursi/kain, brosur lipat, kartu tenda merah tua, buku katalog, selebaran di latar putih, koran di meja.
  Gambarnya desain milik orang lain dan repo ini publik, jadi **yang di-commit hanya `mock/cases.json`** (gambar
  diabaikan `.gitignore`); untuk melatih/menguji, taruh `m01.png`…`m17.png` di `$CROP/mock/`. Ground truth berupa
  POLIGON pecahan 0..1 (boleh lebih dari 4 sudut: label yang terlipat di sudut kardus, brosur lipat dua panel) dengan
  alternatif dipisah `;` (rim kertas: permukaan atas ATAU seluruh kotak); IoU = yang terbaik di antaranya.

Set yang dipakai (folder env `CROP`, bawaan `/home/claude/crop`):

| set | perintah | guna |
|---|---|---|
| 6 / 7 | `gen6.py 1 300 imgs6 cases6.json` / `gen6.py 2 200 imgs7 cases7.json` | umum — latih / uji |
| 8 / 9 | `gen8.py 8 160 imgs8 cases8.json` / `gen8.py 9 100 imgs9 cases9.json` | putih di atas putih — latih / uji |
| 10 / 11 | `gen10.py 10 240 imgs10 cases10.json` / `gen10.py 11 150 imgs11 cases11.json` | berwarna & dipegang — latih / uji |
| real | salin `real/` ke `$CROP/real` | foto asli pengguna |
| mock | `mock/cases.json` + gambar contoh di `$CROP/mock` | foto contoh label/kartu/brosur — ikut dilatih |

`gen.py`…`gen5.py` — set uji lama (latar dasar, latar baru, warna nyaru + lengkung, laptop, buku terbuka).

## Alat

- `Harness.kt` — menjalankan `ScanCore.findDocument` dan menghitung IoU (berhasil bila ≥ 0,85) + rincian per kondisi:
  `java -cp h.jar HarnessKt new 6` (… `7`–`11`, `real`, `mock`; `realsmall <folder>` menulis `.small.gz` untuk uji JVM). Env: `G=1` rincian per kondisi, `V=1` daftar yang gagal,
  `W=…` bobot pengganti, `MS=…` ambang yakin, `AD=…`/`MESSY=…`/`AL=…` ALIGN_DROP/MESSY/INNER_AL pengganti, `SEAM=a,b,c,d,e` parameter cutAtSeam, `NOSEAM=1` matikan cutAtSeam.
- `Trainer.kt` — melatih bobot penilaian `DocFind.W` + `MIN_SCORE`, `ALIGN_DROP`, `MESSY`, `INNER_AL` dengan **alur penuh di
  dalam lingkaran pelatihan**: kandidat & fiturnya disiapkan sekali per foto (`ScanCore.prepareFind`), lalu tiap set
  bobot dinilai dengan `ScanCore.findWith` (pilih-yang-lebih-besar, lembar-di-dalam, gabung halaman, perluas/potong ke
  tepi kertas, SpreadSplit, trimToPaper, cutAtSeam — hasil tahap mahal di-memo). Latih set 6+8+10 + foto asli (wajib
  lolos) + foto contoh `mock` (bobot 4), pilih dengan set 7+9+11 (+ jumlah mock yang lolos). `java -Xmx5500m -cp h.jar Trainer` (env `R` = jumlah titik awal, `T` = thread,
  `FIX=0,3` = indeks yang tidak diubah).
- `SeamTrainer.kt` — melatih parameter `DocFind.SEAM` (potong di garis tepi samar, `DocFind.cutAtSeam`).
- `Inspect.kt` — periksa satu foto: kandidat bernilai tertinggi + fitur mentahnya, kandidat terbaik menurut IoU,
  hasil akhir & gambar overlay: `java -cp h.jar InspectKt foto.png [8 pecahan sudut benar] [overlay.png]`
  (env `P="STAGE|BIGGER|INNER|EXPAND s="` mencetak tahap-tahap alur).

Kompilasi (Kotlin 2.2.10, sama dengan proyek):
`kotlinc Harness.kt Trainer.kt SeamTrainer.kt Inspect.kt <sumber ScanCore/DocFind/LineQuad/DocQuad/CropTrim/SpreadSplit/Quad> <stub android.graphics.Bitmap> -include-runtime -d h.jar`
(`ScanCore.isBlankPage` memakai `android.graphics.Bitmap`; untuk JVM cukup stub kelas kosong bernama itu).

## Hasil

Persentase foto dengan IoU ≥ 0,85.

### 21-11-2026 (2) — lembar POLOS di tempat ramai (`real/user4`)

Foto asli pengguna: papan putih polos di tengah rak besi toko — yang terpilih seluruh rangka besi (IoU 0,17). Kandidat
yang pas (IoU 0,97, keempat tepi sempurna) bernilai isi/tinta 0, jadi dihukum ±20× sebagai "bidang polos" (lampu,
jendela, dinding); aturan "lebih besar" lalu memperluasnya ke rangka. Perbaikan: `cleanBlank` — lembar polos bersih
(tepi tegas & lurus di keempat sisi, isi rata seperti kertas, tidak menempel bingkai, median 120–240 alias tidak silau
seperti lampu/jendela) mendapat isi/tinta pengganti `W[13]` = 0,12 dan hanya boleh diperluas bila tambahannya juga
kertas (`extraIsPaper`). Hasil: `user4` 0,17 → 0,95; semua set lain TIDAK berubah (6: 218, 7: 144, 8: 89, 9: 49,
10: 184, 11: 114, mock 14/17, foto asli 4/4).

### 21-11-2026 — foto contoh label, kartu, brosur & koran (`mock/`)

| set | sebelum | sesudah |
|---|---|---|
| 6 umum (latih) | 217/300 | 218/300 |
| 7 umum (uji) | 147/200 | 144/200 |
| 8 putih di atas putih (latih) | 89/160 | 89/160 |
| 9 putih di atas putih (uji) | 47/100 | 49/100 |
| 10 berwarna & dipegang (latih) | 183/240 | 184/240 |
| 11 berwarna & dipegang (uji) | 115/150 | 114/150 |
| foto asli | 3/3 | 3/3 |
| foto contoh `mock` (latih) | 12/17 (IoU rata2 0,835) | 14/17 (IoU rata2 0,912) |

Yang berubah: (1) ambang isi/tinta 28 → 18 — garis gambar & tulisan halus di label berwarna (label merah muda di
kardus) dulu tidak terhitung isi sehingga kalah oleh permukaan kardus; (2) aturan `INNER_AL`: pemenang yang salah satu
sisinya bukan tepi lurus nyata (koran + permukaan meja) diganti lembar di dalamnya yang keempat sisinya tepi nyata, lebih
seragam, hampir seri nilainya, tidak kecil & tidak lebih jauh dari tengah (di ±1.170 foto hanya aktif pada koran itu);
(3) bobot dilatih ulang dengan `mock` ikut di set latih.

Masih gagal: `m02` rim kertas putih di latar putih & `m16` selebaran krem di latar putih (sisi kanannya hampir tak
terlihat — beda terang ±5–7), `m14` kartu tenda merah tua (tepi bawah/kirinya hanya beda rona dengan bayangannya
sendiri). Sudah dicoba dan DITOLAK: kandidat Hough tambahan dari abu-abu yang histogramnya diratakan dan dari rona
kuning-biru — kandidat benar untuk `m14` muncul (IoU 0,95), tetapi pengecohnya ikut bertambah: papan merah di atas
struk (`real/user3`) jadi menang, set 6/10/11 turun 3–7 walau sudah dilatih ulang.

### 20-11-2026 — dokumen berwarna & dipegang tangan

| set | sebelum (20-11-2026) | sesudah |
|---|---|---|
| 6 umum (latih) | 211/300 (70%) | 217/300 (72%) |
| 7 umum (uji) | 142/200 (71%) | 147/200 (74%) |
| 8 putih di atas putih (latih) | 66/160 (41%) | 89/160 (56%) |
| 9 putih di atas putih (uji) | 39/100 (39%) | 47/100 (47%) |
| 10 berwarna & dipegang (latih) | 126/240 (53%) | 183/240 (76%) |
| 11 berwarna & dipegang (uji) | 90/150 (60%) | 115/150 (77%) |
| foto asli | 1/3 | 3/3 |

Rata-rata "bocor" (bagian hasil potong yang bukan dokumen — penyebab hasil terasa "offset") di set 10/11 turun dari
19%/17% menjadi 9%/9%. Set 11 per jenis (sebelum → sesudah): papan 21→28/30, sampul 7→11/12, kertas warna 23→27/34,
kartu berwarna 10→16/19, poster 15→18/24; dipegang tangan 23→35/60, tergantung 21→30/35.

Perubahan yang membuatnya naik: bobot dilatih ulang dengan dokumen berwarna (suku "mirip kertas putih" hampir hilang,
keseragaman isi & isi/tinta naik), fitur baru isi/tinta dan kesejajaran tepi, aturan "lembar bersih di dalam wilayah
campuran", syarat kesejajaran tepi sebelum pemenang diganti yang lebih besar, dan perluas/potong ke tepi kertas yang
tidak lagi salah mengira tulisan putih di papan berwarna sebagai kertas.
