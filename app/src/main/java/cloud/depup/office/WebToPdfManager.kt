package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Pengubah Link Web / Artikel Jadi PDF (Web Page to PDF Converter).
 * Mengonversi link/halaman web atau artikel menjadi file PDF yang rapi untuk dibaca offline.
 */
object WebToPdfManager {

    @SuppressLint("SetJavaScriptEnabled")
    fun showDialog(activity: AppCompatActivity) {
        val fUrl = activity.field("Masukkan URL / Link Web (mis: https://...)").apply {
            setText("https://")
        }

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_cloud, C.ACCENT, C.BLUE_BG,
                "Web / Artikel ke PDF", "Simpan halaman web & artikel online jadi PDF offline", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val formBox = activity.vbox(10).apply {
                add(activity.tv("URL / Link Web", 12f, C.MUTED))
                add(fUrl)
                add(activity.tv("Tips: Masukkan URL lengkap termasuk https:// agar halaman web dimuat sempurna.", 11f, C.MUTED), top = 4)
            }

            pad.add(formBox)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Cetak & Simpan PDF Web", true, C.PRIMARY, R.drawable.ic_pdf) {
                    var urlStr = fUrl.text.toString().trim()
                    if (urlStr.isBlank() || urlStr == "https://") {
                        activity.toast("Masukkan link web yang valid")
                        return@btn
                    }
                    if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                        urlStr = "https://$urlStr"
                    }

                    dismiss()
                    convertWebToPdf(activity, urlStr)
                }, w = 0, weight = 1f)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun convertWebToPdf(activity: AppCompatActivity, url: String) {
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val webView = WebView(activity)
        webView.settings.javaScriptEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        val busyDialog = activity.busy("Memuat halaman web…")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, urlStr: String?) {
                try {
                    busyDialog.dismiss()
                    val printAdapter = webView.createPrintDocumentAdapter("Web_" + (urlStr?.substringAfter("://")?.take(15) ?: "Halaman"))
                    printManager.print("DepupOffice_Web", printAdapter, null)
                    activity.toast("Pilih \"Simpan sebagai PDF\" untuk menyimpan berkas PDF web")
                } catch (t: Throwable) {
                    try { busyDialog.dismiss() } catch (_: Throwable) {}
                    activity.fail(t)
                }
            }
        }

        webView.loadUrl(url)
    }
}
