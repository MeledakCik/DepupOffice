package cloud.depup.office

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Pembaruan WAJIB: bila Play Store punya versi DepUp yang lebih baru, layar pembaruan penuh Google Play (IMMEDIATE)
 * muncul dan aplikasi tidak bisa dipakai sampai diperbarui. Hanya berlaku untuk instalasi dari Play Store; APK dari
 * GitHub/sideload tidak terpengaruh (Play tidak mengenalnya). Tidak mengirim data apa pun: hanya bertanya ke Play Store
 * di HP ini.
 */
class ForceUpdate(private val activity: AppCompatActivity) {
    private val manager by lazy { AppUpdateManagerFactory.create(activity.applicationContext) }
    private var blocker: AlertDialog? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            // Dibatalkan (tombol kembali) atau gagal → tetap terkunci sampai diperbarui.
            if (r.resultCode != Activity.RESULT_OK) showBlocker()
        }

    /** Panggil dari onResume: memeriksa lagi setiap kali aplikasi kembali ke depan. */
    fun check() {
        if (BuildConfig.DEBUG) return
        try {
            manager.appUpdateInfo.addOnSuccessListener { info ->
                val avail = info.updateAvailability()
                when {
                    avail == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> start(info)
                    avail == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> start(info)
                    avail == UpdateAvailability.UPDATE_AVAILABLE -> showBlocker()   // Play belum izinkan IMMEDIATE: arahkan ke Play Store
                    else -> { blocker?.dismiss(); blocker = null }
                }
            }
        } catch (_: Throwable) { /* tanpa Play Store: lewati */ }
    }

    private fun start(info: com.google.android.play.core.appupdate.AppUpdateInfo) {
        if (activity.isFinishing) return
        try {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
        } catch (_: Throwable) { showBlocker() }
    }

    private fun showBlocker() {
        if (activity.isFinishing || blocker?.isShowing == true) return
        blocker = AlertDialog.Builder(activity)
            .setTitle("Pembaruan wajib")
            .setMessage("Versi baru DepUp Office sudah tersedia. Perbarui dulu agar fitur & keamanan terbaru dapat dipakai.")
            .setCancelable(false)
            .setPositiveButton("Perbarui") { _, _ -> blocker = null; openStore(); }
            .setNegativeButton("Keluar") { _, _ -> blocker = null; activity.finishAffinity() }
            .show()
    }

    private fun openStore() {
        val pkg = activity.packageName
        try {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$pkg")).setPackage("com.android.vending"))
        } catch (_: Throwable) {
            try {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (_: Throwable) {}
        }
    }
}
