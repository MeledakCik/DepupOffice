package cloud.depup.office

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
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
 * Bila temuan muncul atau pemeriksaan gagal, kunci sesi tidak pernah diberikan dan akses dokumen dibatalkan.
 */
object Integrity {
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Pemeriksaan segar per operasi; setiap error pemeriksaan berarti fail-closed. */
    fun findings(ctx: Context? = app): List<String> {
        val c = ctx?.applicationContext ?: return listOf("context")
        val f = ArrayList<String>()
        try { if (debugger()) f += "debugger" } catch (_: Throwable) { f += "debugger-check" }
        try { if (!BuildConfig.DEBUG && (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) f += "debuggable" } catch (_: Throwable) { f += "app-info" }
        try { if (hooked()) f += "hook" } catch (_: Throwable) { f += "hook-check" }
        try { if (rooted(c)) f += "root" } catch (_: Throwable) { f += "root-check" }
        try {
            val nativeFlags = IntegrityNative.scan()
            if (nativeFlags < 0) f += "native"
            else {
                if (nativeFlags and 0x0F != 0) f += "hook"
                if (nativeFlags and 0x70 != 0) f += "root"
                if (nativeFlags and 0x80 != 0) f += "root"
            }
        } catch (_: Throwable) { f += "native" }
        try { if (!BuildConfig.DEBUG && !signatureOk(c)) f += "signature" } catch (_: Throwable) { f += "signature-check" }
        return f.distinct()
    }

    fun compromised(ctx: Context? = app): Boolean = findings(ctx).isNotEmpty()

    internal fun cryptoEnvironmentTrusted(): Boolean = findings().isEmpty()

    internal fun requireCryptoEnvironment() {
        val issues = findings()
        if (issues.isNotEmpty()) throw SecurityException("Pemeriksaan integritas gagal: ${describe(issues)}")
    }

    fun describe(f: List<String>): String = f.joinToString(", ") {
        when (it) {
            "debugger" -> "debugger terpasang"
            "debuggable" -> "aplikasi dibuat ulang (debuggable)"
            "hook" -> "alat injeksi/hook (Frida/Xposed) aktif"
            "signature" -> "APK dimodifikasi / tanda tangan tidak sah"
            "root", "root-check" -> "perangkat terdeteksi root atau sistem dimodifikasi"
            "native", "debugger-check", "hook-check", "signature-check", "app-info" -> "pemeriksaan integritas gagal"
            "context" -> "konteks aplikasi tidak tersedia"
            else -> it
        }
    }

    private fun debugger(): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        val status = File("/proc/self/status")
        if (!status.canRead()) throw SecurityException("status tidak dapat dibaca")
        val tracer = status.readLines().firstOrNull { it.startsWith("TracerPid:") }
            ?: throw SecurityException("TracerPid tidak tersedia")
        val tp = tracer.substringAfter(':').trim().toIntOrNull()
            ?: throw SecurityException("TracerPid tidak valid")
        return tp != 0
    }

    private val HOOK_LIBS = listOf("frida", "gum-js", "gadget", "xposed", "lsposed", "edxposed", "substrate", "libriru_", "sandhook")

    private fun hooked(): Boolean {
        for (cls in arrayOf("de.robv.android.xposed.XposedBridge", "de.robv.android.xposed.XC_MethodHook", "com.saurik.substrate.MS")) {
            try { Class.forName(cls, false, Integrity::class.java.classLoader); return true } catch (_: Throwable) {}
        }
        val maps = File("/proc/self/maps")
        if (!maps.canRead()) throw SecurityException("maps tidak dapat dibaca")
        maps.useLines { lines ->
            if (lines.any { l -> val s = l.lowercase(); HOOK_LIBS.any { s.contains(it) } }) return true
        }
        val tasks = File("/proc/self/task").listFiles() ?: throw SecurityException("task tidak dapat dibaca")
        tasks.forEach { t ->
            val n = try { File(t, "comm").readText().trim() } catch (_: Throwable) { "" }
            if (n == "gum-js-loop" || n == "gmain" && hasFridaPipe() || n.startsWith("frida")) return true
        }
        if (File("/proc/self/fd").listFiles() == null) throw SecurityException("fd tidak dapat dibaca")
        return false
    }

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/vendor/bin/su"
    )
    private val ROOT_PATHS = listOf("/sbin/.magisk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap")
    private val ROOT_PACKAGES = listOf(
        "com.topjohnwu.magisk", "me.weishu.kernelsu", "me.weishu.kernelsu.manager",
        "me.bmax.apatch", "com.rifsxd.ksunext", "org.lsposed.manager", "com.noshufou.android.su",
        "com.thirdparty.superuser", "eu.chainfire.supersu"
    )

    private fun rooted(c: Context): Boolean {
        if (Build.TAGS?.split(',')?.any { it.trim() == "test-keys" } == true) return true
        if (SU_PATHS.any { File(it).exists() } || ROOT_PATHS.any { File(it).exists() }) return true
        val mounts = File("/proc/self/mounts").readText()
        if (mounts.contains("magisk", true) || mounts.contains("kernelsu", true) || mounts.contains("/data/adb", true)) return true
        if (Regex("\\s/(system|vendor|product)\\s+[^\\n]*\\brw[, ]").containsMatchIn(mounts)) return true
        val pm = c.packageManager
        for (name in ROOT_PACKAGES) {
            try {
                if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(name, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                // Expected when a known root manager is not installed.
            }
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
        val allowed = BuildConfig.RELEASE_CERTS.split(',').map { it.trim().replace(":", "").lowercase() }
            .filter { it.matches(Regex("[0-9a-f]{64}")) }
            .map { h -> ByteArray(32) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
        if (allowed.isEmpty()) return false
        return hashes.any { actual -> allowed.any { expected -> MessageDigest.isEqual(expected, actual) } }
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
