# Depup Solid — generator ikon

Semua ikon `app/src/main/res/drawable/ic_*.xml` (kecuali logo & ikon peluncur) dibuat oleh `gen.py`:
bentuk solid bersudut squircle, detail berupa **lubang** (fill even-odd — ikon di-tint satu warna di kode, jadi
lubang tetap benar di atas latar apa pun), ujung garis bulat, celah kecil antar-bagian.

```bash
pip install shapely
python3 tools/icons/gen.py app/src/main/res/drawable --svg /tmp/ikon      # semua ikon (+ pratinjau SVG)
python3 tools/icons/gen.py app/src/main/res/drawable camera home         # hanya ikon tertentu
```

Ikon baru: tambahkan fungsi `@icon("nama")` di `gen.py` dari primitif `squircle`, `circle`, `line`, `poly`,
lalu `knock` (lubangi), `gap` (beri celah), `split` (potong jadi beberapa bagian). Konstanta: `SW` (tebal garis),
`KW` (tebal lubang), `GAP` (celah). `ic_bookmark_off` sengaja berupa garis — pasangan sakelar `ic_bookmark`.
