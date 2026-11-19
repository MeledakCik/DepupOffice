# Aturan R8 bawaan (proguard-android-optimize.txt) sudah mencukupi untuk kode aplikasi.

# Kotlin: pemeriksaan null otomatis pada parameter & nilai dari API Java dibuang di build rilis — kode lebih kecil &
# sedikit lebih cepat. Pemeriksaan operator !! (checkNotNull) tetap dipertahankan.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
}

# Semua kelas aplikasi yang boleh diganti namanya dipindah ke satu paket pendek -> tabel string DEX lebih kecil.
# (Kelas yang disebut di manifest & aturan keep pustaka tetap dipertahankan namanya.)
-repackageclasses

# ---- Keamanan ----
# Honeypot: kelas umpan SENGAJA dipertahankan nama & isinya agar mudah "ditemukan" pembongkar APK.
-keep class cloud.depup.office.MasterKeys { *; }
# Log debug dibuang dari rilis (tidak membocorkan alur keamanan lewat logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Transisi menu dipanggil lewat XML (res/transition/menu_pop.xml, atribut class) — jangan dibuang/diganti nama R8.
-keep class cloud.depup.office.MenuPop { <init>(android.content.Context, android.util.AttributeSet); }
