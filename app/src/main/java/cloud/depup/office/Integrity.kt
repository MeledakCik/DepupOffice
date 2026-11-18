package cloud.depup.office

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.view.View
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pemeriksaan integritas runtime (anti-inject / anti-tamper) untuk melindungi Dokumen Terlindungi:
 *  - APK dipaketkan ulang dengan sertifikat lain (kecuali dipasang dari Play Store: Play App Signing);
 *  - debugger terpasang (JDWP / ptrace TracerPid);
 *  - framework hook/instrumentasi (Frida, Xposed/LSPosed, Substrate) dimuat ke proses;
 *  - build rilis yang ditandai debuggable.
 * Bila terdeteksi, kunci baca ASLI tidak pernah dibuka — penyedia kunci memberi kunci umpan (honeypot), sehingga
 * dekripsi gagal walau pemeriksaan di layar dilewati dengan hook.
 */
object Integrity {
    private const val PLAY = "com.android.vending"
    @Volatile private var cachedAt = 0L
    @Volatile private var cached: List<String> = emptyList()
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext; Honeypot.appCtx = app }

    /** Daftar temuan; kosong = lingkungan aman. Di-cache 15 detik. */
    fun findings(ctx: Context? = app): List<String> {
        val c = ctx?.applicationContext ?: return emptyList()
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < 15_000 && cachedAt != 0L) return cached
        val f = ArrayList<String>()
        try { if (debugger()) f += "debugger" } catch (_: Throwable) {}
        try { if (!BuildConfig.DEBUG && (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) f += "debuggable" } catch (_: Throwable) {}
        try { if (hooked()) f += "hook" } catch (_: Throwable) {}
        try { if (!BuildConfig.DEBUG && !signatureOk(c)) f += "signature" } catch (_: Throwable) {}
        cached = f; cachedAt = now
        return f
    }

    fun compromised(ctx: Context? = app): Boolean = findings(ctx).isNotEmpty()

    fun describe(f: List<String>): String = f.joinToString(", ") {
        when (it) {
            "debugger" -> "debugger terpasang"
            "debuggable" -> "aplikasi dibuat ulang (debuggable)"
            "hook" -> "alat injeksi/hook (Frida/Xposed) aktif"
            "signature" -> "APK dimodifikasi / tanda tangan tidak sah"
            else -> it
        }
    }

    private fun debugger(): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        val st = File("/proc/self/status").takeIf { it.canRead() }?.readLines() ?: return false
        val tp = st.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        return tp != 0
    }

    private val HOOK_LIBS = listOf("frida", "gum-js", "gadget", "xposed", "lsposed", "edxposed", "substrate", "libriru_", "sandhook")

    private fun hooked(): Boolean {
        for (cls in arrayOf("de.robv.android.xposed.XposedBridge", "de.robv.android.xposed.XC_MethodHook", "com.saurik.substrate.MS")) {
            try { Class.forName(cls, false, Integrity::class.java.classLoader); return true } catch (_: Throwable) {}
        }
        File("/proc/self/maps").takeIf { it.canRead() }?.useLines { lines ->
            if (lines.any { l -> val s = l.lowercase(); HOOK_LIBS.any { s.contains(it) } }) return true
        }
        File("/proc/self/task").listFiles()?.forEach { t ->
            val n = try { File(t, "comm").readText().trim() } catch (_: Throwable) { "" }
            if (n == "gum-js-loop" || n == "gmain" && hasFridaPipe() || n.startsWith("frida")) return true
        }
        return false
    }

    private fun hasFridaPipe(): Boolean = File("/proc/self/fd").listFiles()?.any {
        try { it.canonicalPath.contains("linjector") || it.canonicalPath.contains("frida") } catch (_: Throwable) { false }
    } == true

    private fun signatureOk(c: Context): Boolean {
        val pm = c.packageManager
        val info = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info.signingInfo ?: return false
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        val md = MessageDigest.getInstance("SHA-256")
        val hashes = certs.map { md.digest(it.toByteArray()) }
        val allowed = ArrayList<ByteArray>()
        val rel = Sx.releaseCert(); allowed += rel
        BuildConfig.EXTRA_CERTS.split(',').map { it.trim().replace(":", "") }.filter { it.length == 64 }.forEach { h ->
            allowed += ByteArray(32) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        if (hashes.any { h -> allowed.any { MessageDigest.isEqual(it, h) } }) return true
        // Play App Signing menandatangani ulang dengan kunci Google: terima bila dipasang oleh Play Store.
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(c.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(c.packageName)
        } catch (_: Throwable) { null }
        return installer == PLAY
    }

    // ---------- Intent internal (anti intent-injection) ----------
    const val EXTRA_TOKEN = "cloud.depup.office.extra.T"
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Tandai intent sebagai berasal dari dalam aplikasi (token acak per proses; aplikasi lain tidak bisa menebaknya). */
    fun internal(i: Intent): Intent = i.putExtra(EXTRA_TOKEN, token)

    fun isInternal(i: Intent?): Boolean = try {
        val t = i?.getStringExtra(EXTRA_TOKEN)
        t != null && MessageDigest.isEqual(t.toByteArray(), token.toByteArray())
    } catch (_: Throwable) { false }

    /**
     * URI yang menunjuk ke data privat aplikasi hanya boleh dibuka dari dalam aplikasi:
     * - file:// ke folder data/cache/berkas eksternal milik aplikasi;
     * - content:// lewat FileProvider aplikasi SENDIRI. Penyedia itu tidak diekspor, tetapi pembaca berjalan di
     *   proses DepUp, jadi aplikasi lain bisa "meminjam" izin kita dengan mengirim URI tersebut (confused deputy) —
     *   mis. untuk membuka salinan brankas/dokumen terdekripsi di cache.
     */
    fun isPrivateFile(ctx: Context, uri: android.net.Uri): Boolean {
        if (uri.scheme == "content") return uri.authority.equals(ctx.packageName + ".fileprovider", ignoreCase = true)
        if (uri.scheme != "file") return false
        val p = try { File(uri.path ?: return true).canonicalPath } catch (_: Throwable) { return true }
        val roots = listOfNotNull(ctx.applicationInfo.dataDir, ctx.filesDir.parent, ctx.cacheDir.parent)
            .mapNotNull { try { File(it).canonicalPath } catch (_: Throwable) { null } }
        val ext = listOfNotNull(ctx.getExternalFilesDir(null)?.parentFile).mapNotNull { try { it.canonicalPath } catch (_: Throwable) { null } }
        return (roots + ext).any { p == it || p.startsWith("$it/") }
    }
}

/**
 * Honeypot: kunci umpan di [MasterKeys]. Bila sebuah dokumen ternyata valid terhadap kunci umpan, berarti ada yang
 * membongkar APK, mengambil "kunci" yang tampak asli, lalu memalsukan dokumen. Dokumen ditolak & kejadian dicatat.
 */
object Honeypot {
    @Volatile var tripped = false; private set

    fun decoyKey(docId: String): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(MasterKeys.AES_MASTER_KEY, "HmacSHA256"))
        }.doFinal((MasterKeys.READ_KEY_SALT + docId).toByteArray(Charsets.UTF_8))

    fun trip(ctx: Context?, reason: String) {
        tripped = true
        try {
            val c = ctx ?: appCtx ?: return
            val p = c.getSharedPreferences("sec", Context.MODE_PRIVATE)
            p.edit().putInt("hp_count", p.getInt("hp_count", 0) + 1)
                .putLong("hp_last", System.currentTimeMillis())
                .putString("hp_reason", reason.take(64))
                .apply()
        } catch (_: Throwable) {}
    }

    @Volatile internal var appCtx: Context? = null
}

/**
 * Perisai anti-AI untuk layar dokumen terkunci: isi tidak boleh diambil asisten/agen AI di perangkat
 * (Assist API / "konteks layar", Content Capture, layanan aksesibilitas non-alat-bantu, autofill).
 * Melengkapi FLAG_SECURE dari [SecureGuard] yang sudah memblokir tangkapan layar (Circle to Search, dll.).
 */
object AiShield {
    fun protect(a: Activity) {
        val root = a.window?.decorView ?: return
        apply(root)
        a.findViewById<View>(android.R.id.content)?.let { apply(it) }
    }

    fun apply(v: View) {
        try { v.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 30) try { v.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS } catch (_: Throwable) {}
        // Android 14+: hanya layanan aksesibilitas yang benar-benar alat bantu (isAccessibilityTool) yang boleh membaca.
        if (Build.VERSION.SDK_INT >= 34) try { v.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES) } catch (_: Throwable) {}
    }
}
