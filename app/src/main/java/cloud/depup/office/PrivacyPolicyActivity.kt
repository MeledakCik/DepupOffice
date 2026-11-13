package cloud.depup.office

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class PrivacyPolicyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_policy)

        findViewById<ImageView>(R.id.btnBackPrivacy)?.setOnClickListener {
            finish()
        }

        val webView = findViewById<WebView>(R.id.webViewPrivacy)
        if (webView != null) {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleExternalUrl(url)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return false
                    return handleExternalUrl(url)
                }
            }

            // Ikuti tema aplikasi (terang/gelap) dan ukuran huruf sistem.
            webView.setBackgroundColor(C.BG)
            webView.settings.textZoom = (resources.configuration.fontScale * 100).toInt()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleExternalUrl(url)
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("document.documentElement.className='${if (C.dark) "dark" else "light"}'", null)
                }
            }
            webView.loadUrl("file:///android_asset/privacy.html")
        }
    }

    private fun handleExternalUrl(url: String): Boolean {
        return if (url.startsWith("file:///android_asset/")) {
            false
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (_: Exception) {
            }
            true
        }
    }
}
