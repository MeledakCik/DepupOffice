# Uji & pelatihan AI potong (auto-crop)

Alat pengembang — tidak ikut di-build ke APK.

- `gen6.py <seed> <jumlah> <folder> <cases.json>` — membuat foto sintetis beragam: 18 latar (kayu, marmer, granit,
  ubin, sprei, karpet, laptop, koran, …), 11 jenis dokumen (struk, A4, A4 berwarna, faktur berpita judul, KTP,
  kartu nama, halaman buku, buku terbuka 2 halaman, kertas kusut), cahaya (redup, silau, bayangan, gradasi, vinyet,
  hangat/dingin), kamera miring/perspektif, zoom (dokumen terpotong bingkai), buram & goyang, jari, ponsel, pulpen,
  kabel, mug. Ground truth = masker halaman/dokumen yang dituju.
- `gen.py`…`gen5.py` — set uji lama (latar dasar, latar baru, warna nyaru + lengkung, laptop, buku terbuka).
- `Harness.kt` — menjalankan `ScanCore.findDocument` dan menghitung IoU (berhasil bila ≥ 0,85) + rincian per kondisi.
- `Trainer.kt` — melatih bobot penilaian kandidat (`DocFind.W`) dengan coordinate descent; pilih bobot berdasarkan
  set uji terpisah, bukan set latih.

Kompilasi: `kotlinc Harness.kt Trainer.kt <sumber ScanCore/DocFind/LineQuad/DocQuad/CropTrim/SpreadSplit/Quad> -include-runtime -d h.jar`
lalu `java -cp h.jar HarnessKt new 6` (butuh folder gambar hasil generator).
