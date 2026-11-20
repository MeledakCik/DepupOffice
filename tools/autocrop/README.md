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
  urutan KA, KaA, KaB, KiB). **Wajib lolos** (juga diuji JVM: `app/src/test/.../AutoCropRealTest.kt`).
  - `user1.png` — kertas putih di atas kain putih, batas hanya garis bayangan tipis (15-11-2026).
  - `user2.png` — papan merah tergantung di plafon: dulu yang terpilih atap tembus cahaya putih di bawahnya (20-11-2026).
  - `user3.png` — struk dipegang tangan di bawah papan merah: dulu sisi kanannya melebar ke silau lampu (20-11-2026).

Set yang dipakai (folder env `CROP`, bawaan `/home/claude/crop`):

| set | perintah | guna |
|---|---|---|
| 6 / 7 | `gen6.py 1 300 imgs6 cases6.json` / `gen6.py 2 200 imgs7 cases7.json` | umum — latih / uji |
| 8 / 9 | `gen8.py 8 160 imgs8 cases8.json` / `gen8.py 9 100 imgs9 cases9.json` | putih di atas putih — latih / uji |
| 10 / 11 | `gen10.py 10 240 imgs10 cases10.json` / `gen10.py 11 150 imgs11 cases11.json` | berwarna & dipegang — latih / uji |
| real | salin `real/` ke `$CROP/real` | foto asli pengguna |

`gen.py`…`gen5.py` — set uji lama (latar dasar, latar baru, warna nyaru + lengkung, laptop, buku terbuka).

## Alat

- `Harness.kt` — menjalankan `ScanCore.findDocument` dan menghitung IoU (berhasil bila ≥ 0,85) + rincian per kondisi:
  `java -cp h.jar HarnessKt new 6` (… `7`–`11`, `real`). Env: `G=1` rincian per kondisi, `V=1` daftar yang gagal,
  `W=…` bobot pengganti, `MS=…` ambang yakin, `SEAM=a,b,c,d,e` parameter cutAtSeam, `NOSEAM=1` matikan cutAtSeam.
- `Trainer.kt` — melatih bobot penilaian `DocFind.W` + `MIN_SCORE`, `ALIGN_DROP`, `MESSY` dengan **alur penuh di
  dalam lingkaran pelatihan**: kandidat & fiturnya disiapkan sekali per foto (`ScanCore.prepareFind`), lalu tiap set
  bobot dinilai dengan `ScanCore.findWith` (pilih-yang-lebih-besar, lembar-di-dalam, gabung halaman, perluas/potong ke
  tepi kertas, SpreadSplit, trimToPaper, cutAtSeam — hasil tahap mahal di-memo). Latih set 6+8+10 + foto asli (wajib
  lolos), pilih dengan set 7+9+11. `java -Xmx5500m -cp h.jar Trainer` (env `R` = jumlah titik awal, `T` = thread,
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
