# Uji & pelatihan AI potong (auto-crop)

Alat pengembang — tidak ikut di-build ke APK.

- `gen6.py <seed> <jumlah> <folder> <cases.json>` — membuat foto sintetis beragam: 18 latar (kayu, marmer, granit,
  ubin, sprei, karpet, laptop, koran, …), 11 jenis dokumen (struk, A4, A4 berwarna, faktur berpita judul, KTP,
  kartu nama, halaman buku, buku terbuka 2 halaman, kertas kusut), cahaya (redup, silau, bayangan, gradasi, vinyet,
  hangat/dingin), kamera miring/perspektif, zoom (dokumen terpotong bingkai), buram & goyang, jari, ponsel, pulpen,
  kabel, mug. Ground truth = masker halaman/dokumen yang dituju.
- `gen8.py <seed> <jumlah> <folder> <cases.json>` — "putih di atas putih": kertas di atas kain/sprei/papan putih yang
  menempel 1–2 sisinya, foto terlalu terang (putih jenuh), batas kertas hanya garis bayangan tipis; latar ramai.
  Set 8 (seed 8, 160 foto) untuk latih, set 9 (seed 9, 100 foto) untuk uji.
- `real/` — foto ASLI dari pengguna yang pernah gagal di HP (`real/cases.json`: sudut benar dalam pecahan 0..1).
  Wajib lolos sebelum rilis: `java -cp h.jar HarnessKt new real`.
- `gen.py`…`gen5.py` — set uji lama (latar dasar, latar baru, warna nyaru + lengkung, laptop, buku terbuka).
- `Harness.kt` — menjalankan `ScanCore.findDocument` dan menghitung IoU (berhasil bila ≥ 0,85) + rincian per kondisi.
- `SeamTrainer.kt` — melatih parameter `DocFind.SEAM` (potong di garis tepi samar, `DocFind.cutAtSeam`): grid search
  di set 6+8, dipilih di set 7+9; kemunduran di set umum dihukum 3x dan foto `real/` wajib lolos.
- `Trainer.kt` — melatih bobot penilaian kandidat (`DocFind.W`) dengan coordinate descent; pilih bobot berdasarkan
  set uji terpisah, bukan set latih.

Data dibaca dari folder env `CROP` (bawaan `/home/claude/crop`). Set 6/7: `gen6.py 1 300 imgs6 cases6.json` dan
`gen6.py 2 200 imgs7 cases7.json`; set 8/9: `gen8.py 8 160 imgs8 cases8.json` dan `gen8.py 9 100 imgs9 cases9.json`.
Env uji: `NOSEAM=1` mematikan cutAtSeam (pembanding), `SEAM=a,b,c,d,e` mengganti parameternya, `G=1` rincian per kondisi.

Kompilasi: `kotlinc Harness.kt Trainer.kt SeamTrainer.kt <sumber ScanCore/DocFind/LineQuad/DocQuad/CropTrim/SpreadSplit/Quad> -include-runtime -d h.jar`
lalu `java -cp h.jar HarnessKt new 6` (butuh folder gambar hasil generator).
