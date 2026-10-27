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
    private fun hash(salt: String, pin: String) = hex(MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()))

    fun hasPin(ctx: Context) = sp(ctx).contains("hash")

    fun setPin(ctx: Context, pin: String) {
        val salt = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        sp(ctx).edit().putString("salt", salt).putString("hash", hash(salt, pin)).apply()
    }

    fun check(ctx: Context, pin: String): Boolean {
        val s = sp(ctx)
        return hash(s.getString("salt", "")!!, pin) == s.getString("hash", null)
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
        h.add(icon(R.drawable.ic_back, C.TEXT, 24).apply { setPadding(0, 0, dp(14), 0); setOnClickListener { finish() } }, w = dp(38), h = dp(24))
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
                a.length !in 4..6 -> toast("PIN harus 4–6 digit")
                a != p2.text.toString() -> toast("PIN tidak sama")
                else -> { Vault.setPin(this, a); showList() }
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
            toast("✓ Autentikasi Biometrik Berhasil!")
            showList()
        }
    }

    private fun showLogin() {
        header("Masukkan PIN atau gunakan Sidik Jari")
        val p = field("PIN", pin = true)
        holder.add(p, top = 24)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.add(btn("Buka PIN") {
            if (Vault.check(this, p.text.toString())) showList() else { toast("PIN salah"); p.setText("") }
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
            holder.add(actionRow(R.drawable.ic_lock, Vault.displayName(f), Files.fmtSize(f.length()), C.AMBER, C.AMBER_BG) { fileMenu(f) }, top = 10)
        }
    }

    private fun addFiles(list: List<Uri>) {
        work("Mengenkripsi…", {
            list.forEach { u -> contentResolver.openInputStream(u)!!.use { Vault.encrypt(this, it, Files.nameOf(this, u)) } }
        }) { r -> r.onFailure { fail(it) }; showList() }
    }

    private fun fileMenu(f: File) {
        val items = arrayOf("Buka", "Kirim via WhatsApp", "Bagikan", "Ekspor ke Unduhan", "Hapus dari Brankas")
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
                        r2.onSuccess { toast("Tersimpan di Unduhan/Depup Office") }.onFailure { fail(it) }
                    }
                }
            }
        }.setNegativeButton("Tutup", null).show()
    }
}
