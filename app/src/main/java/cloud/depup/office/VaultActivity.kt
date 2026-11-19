package cloud.depup.office

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Brankas: file dienkripsi AES-256-GCM dengan kunci di Android Keystore; UI dikunci PIN. */
object Vault {
    private const val ALIAS = "depup_vault_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("depup_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    /** Format lama (sebelum 4.1.1): SHA-256 sekali — hanya untuk memverifikasi & memindahkan PIN lama. */
    private fun legacyHash(salt: String, pin: String) = hex(MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()))

    /**
     * PIN 4–6 digit hanya punya ≤ 1 juta kemungkinan: hash sekali bisa ditebak habis dalam hitungan detik bila setelan
     * bocor. PBKDF2-HMAC-SHA256 dengan banyak putaran membuat tiap tebakan mahal.
     */
    private const val ITER = 60_000
    private fun kdf(salt: String, pin: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(), ITER, 256)
        return try { hex(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }

    private fun same(a: String?, b: String?): Boolean =
        a != null && b != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())   // waktu-konstan

    fun hasPin(ctx: Context) = sp(ctx).contains("hash")

    /** Lambat (PBKDF2): panggil dari thread latar. */
    fun setPin(ctx: Context, pin: String) {
        val salt = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        sp(ctx).edit().putString("salt", salt).putString("hash", kdf(salt, pin)).putString("kdf", "pbkdf2")
            .remove("fails").remove("lockUntil").apply()
    }

    /** Sisa waktu terkunci (ms) setelah terlalu banyak PIN salah; 0 = boleh mencoba. */
    fun lockRemainingMs(ctx: Context): Long =
        (sp(ctx).getLong("lockUntil", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Periksa PIN. Lambat (PBKDF2): panggil dari thread latar. Setelah 5 kali salah brankas terkunci 30 detik,
     * lalu berlipat dua tiap salah berikutnya (maks. 15 menit) — PIN 4 digit tidak bisa ditebak beruntun.
     */
    fun check(ctx: Context, pin: String): Boolean = synchronized(this) {
        if (lockRemainingMs(ctx) > 0) return false
        val s = sp(ctx)
        val salt = s.getString("salt", "") ?: ""
        val stored = s.getString("hash", null)
        val pbk = s.getString("kdf", null) == "pbkdf2"
        val ok = if (pbk) same(kdf(salt, pin), stored) else same(legacyHash(salt, pin), stored)
        if (ok) {
            if (!pbk) setPin(ctx, pin)    // pindahkan PIN lama ke format PBKDF2
            else s.edit().remove("fails").remove("lockUntil").apply()
        } else {
            val fails = s.getInt("fails", 0) + 1
            val e = s.edit().putInt("fails", fails)
            if (fails >= 5) e.putLong("lockUntil", System.currentTimeMillis() + minOf(30_000L shl minOf(fails - 5, 5), 15 * 60_000L))
            e.apply()
        }
        ok
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "vault").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles() ?: emptyArray()).filter { it.name.endsWith(".enc") }.sortedByDescending { it.lastModified() }

    fun displayName(f: File) = f.name.removeSuffix(".enc")

    fun encrypt(ctx: Context, input: InputStream, name: String) {
        val safe = name.replace("/", "_")
        var f = File(dir(ctx), "$safe.enc")
        if (f.exists()) f = File(dir(ctx), "${System.currentTimeMillis()}_$safe.enc")
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        FileOutputStream(f).use { fo ->
            fo.write(c.iv.size)
            fo.write(c.iv)
            CipherOutputStream(fo, c).use { input.copyTo(it) }
        }
    }

    fun decrypt(enc: File, out: File) {
        out.parentFile?.mkdirs()
        FileInputStream(enc).use { fi ->
            val di = DataInputStream(fi)
            val iv = ByteArray(di.readUnsignedByte())
            di.readFully(iv)
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            CipherInputStream(di, c).use { ci -> FileOutputStream(out).use { ci.copyTo(it) } }
        }
    }
}

class VaultActivity : AppCompatActivity() {
    private lateinit var holder: LinearLayout

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) addFiles(l)
    }

    private fun tmpDir() = File(cacheDir, "vault_tmp")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kembali: pratinjau prediktif (Android 14+) — layar mengecil mengikuti ibu jari
        onBackPressedDispatcher.addCallback(this, PreviewBack(this) { finish() })
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        tmpDir().deleteRecursively()
        holder = vbox(18)
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(holder) })
        if (Vault.hasPin(this)) showLogin() else showCreate()
    }

    override fun onDestroy() {
        tmpDir().deleteRecursively()
        super.onDestroy()
    }

    private fun header(sub: String) {
        holder.removeAllViews()
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        h.add(icon(R.drawable.ic_back, C.TEXT, 24).asButton("Kembali").apply { setOnClickListener { finish() } }, w = dp(48), h = dp(48))
        val c = vbox()
        c.add(tv("Vault Brankas", 20f, C.TEXT, true))
        c.add(tv(sub, 12f, C.MUTED))
        h.add(c, w = 0, weight = 1f)
        holder.add(h, top = 8)
    }

    private fun showCreate() {
        header("Buat PIN 4–6 digit untuk mengunci Brankas")
        val p1 = field("PIN baru", pin = true)
        val p2 = field("Ulangi PIN", pin = true)
        holder.add(p1, top = 24)
        holder.add(p2, top = 10)
        holder.add(btn("Buat PIN") {
            val a = p1.text.toString()
            when {
                a.length !in 4..6 || !a.all { it in '0'..'9' } -> toast("PIN harus 4–6 digit angka")
                a != p2.text.toString() -> toast("PIN tidak sama")
                else -> Thread {   // PBKDF2 sengaja lambat: jangan di thread UI
                    val ok = try { Vault.setPin(this, a); true } catch (_: Exception) { false }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ok) showList() else toast("PIN gagal disimpan")
                    }
                }.start()
            }
        }, top = 16)
        holder.add(tv("Jangan lupa PIN ini. File di Brankas tidak bisa dibuka tanpa PIN, dan tidak ada fitur reset.", 12f, C.MUTED), top = 14)
    }

    private fun authenticateBiometric() {
        val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km != null && km.isKeyguardSecure) {
            val i = km.createConfirmDeviceCredentialIntent("Buka Brankas Vault", "Gunakan Sidik Jari / Biometrik / PIN Layar HP Anda")
            if (i != null) {
                try {
                    startActivityForResult(i, 888)
                    return
                } catch (_: Exception) {}
            }
        }
        toast("Autentikasi biometrik/layar tidak tersedia di perangkat ini")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 888 && resultCode == RESULT_OK) {
            toast("Autentikasi biometrik berhasil")
            showList()
        }
    }

    private fun showLogin() {
        header("Masukkan PIN atau gunakan Sidik Jari")
        val p = field("PIN", pin = true)
        holder.add(p, top = 24)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var checking = false
        row.add(btn("Buka PIN") {
            val wait = Vault.lockRemainingMs(this)
            if (wait > 0) toast("Terlalu banyak PIN salah. Coba lagi dalam ${(wait + 999) / 1000} detik.")
            else if (!checking) {
                checking = true
                val pin = p.text.toString()
                // PBKDF2 sengaja lambat: jangan di thread UI
                Thread {
                    val ok = try { Vault.check(this, pin) } catch (_: Exception) { false }
                    runOnUiThread {
                        checking = false
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ok) showList() else {
                            val w = Vault.lockRemainingMs(this)
                            toast(if (w > 0) "PIN salah. Brankas dikunci ${(w + 999) / 1000} detik." else "PIN salah")
                            p.setText("")
                        }
                    }
                }.start()
            }
        }, w = 0, weight = 1f)
        row.add(btn("Sidik Jari / Biometrik", true, C.ACCENT, R.drawable.ic_lock) {
            authenticateBiometric()
        }, w = 0, weight = 1.2f, side = 8)
        holder.add(row, top = 14)
    }

    private fun showList() {
        header("Terenkripsi AES-256 · tersimpan hanya di perangkat ini")
        holder.add(btn("Tambah file", true, C.ACCENT, R.drawable.ic_plus) { picker.launch(arrayOf("*/*")) }, top = 18)
        val files = Vault.list(this)
        if (files.isEmpty()) {
            holder.add(tv("Brankas masih kosong.", 13f, C.MUTED), top = 20)
        }
        files.forEach { f ->
            holder.add(actionRow(R.drawable.ic_lock, Vault.displayName(f), Files.fmtSize(f.length()), C.AMBER, C.AMBER_BG, shadow = false) { fileMenu(f) }, top = 10)
        }
    }

    private fun addFiles(list: List<Uri>) {
        work("Mengenkripsi…", {
            list.forEach { u -> contentResolver.openInputStream(u)!!.use { Vault.encrypt(this, it, Files.nameOf(this, u)) } }
        }) { r -> r.onFailure { fail(it) }; showList() }
    }

    private fun fileMenu(f: File) {
        val items = arrayOf("Buka", "Kirim via WhatsApp", "Bagikan", "Ekspor ke DepUp Office", "Hapus dari Brankas")
        AlertDialog.Builder(this).setTitle(Vault.displayName(f)).setItems(items) { _, i ->
            if (i == 4) {
                confirm("Hapus file?", "${Vault.displayName(f)} akan dihapus permanen dari Brankas.", "Hapus") { f.delete(); showList() }
                return@setItems
            }
            val tmp = File(tmpDir(), Vault.displayName(f))
            work("Membuka…", { Vault.decrypt(f, tmp) }) { r ->
                r.onFailure { fail(it); return@work }
                val uri = Files.uriFor(this, tmp)
                val mime = Files.byName(tmp.name)
                when (i) {
                    0 -> Files.open(this, uri, mime)
                    1 -> Files.share(this, uri, mime, true)
                    2 -> Files.share(this, uri, mime, false)
                    else -> work("Menyimpan…", { Files.saveToDownloads(this, uri, tmp.name, mime) }) { r2 ->
                        r2.onSuccess { toast("Tersimpan di Documents/DepUp Office") }.onFailure { fail(it) }
                    }
                }
            }
        }.setNegativeButton("Tutup", null).show()
    }
}
