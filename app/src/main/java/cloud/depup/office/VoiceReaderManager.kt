package cloud.depup.office

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.Locale

class VoiceReaderManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false
    private var currentText: String = ""
    private var paragraphs: List<String> = emptyList()
    private var currentParagraphIndex = 0
    private var isPaused = false
    private var speechRate = 1.0f
    private var currentLocale: Locale = Locale("id", "ID")

    var onProgressUpdate: ((paragraphIndex: Int, totalParagraphs: Int, paragraphText: String) -> Unit)? = null
    var onStateChange: ((isPlaying: Boolean, isPaused: Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.language = currentLocale
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (!isPaused && currentParagraphIndex < paragraphs.size - 1) {
                        currentParagraphIndex++
                        speakCurrentParagraph()
                    } else if (currentParagraphIndex >= paragraphs.size - 1) {
                        (context as? Activity)?.runOnUiThread {
                            onStateChange?.invoke(false, false)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    fun startReading(text: String, locale: Locale = Locale("id", "ID")) {
        if (text.isBlank()) return
        currentLocale = locale
        currentText = text
        paragraphs = text.split("\n").filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) paragraphs = listOf(text)
        currentParagraphIndex = 0
        isPaused = false

        if (isInitialized) {
            tts?.language = currentLocale
            tts?.setSpeechRate(speechRate)
            speakCurrentParagraph()
        }
    }

    private fun speakCurrentParagraph() {
        if (paragraphs.isEmpty() || currentParagraphIndex >= paragraphs.size) return
        val textToSpeak = paragraphs[currentParagraphIndex]

        (context as? Activity)?.runOnUiThread {
            onProgressUpdate?.invoke(currentParagraphIndex + 1, paragraphs.size, textToSpeak)
            onStateChange?.invoke(true, false)
        }

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "paragraph_$currentParagraphIndex")
        tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "paragraph_$currentParagraphIndex")
    }

    fun pause() {
        if (isInitialized) {
            tts?.stop()
            isPaused = true
            onStateChange?.invoke(false, true)
        }
    }

    fun resume() {
        if (isPaused && paragraphs.isNotEmpty()) {
            isPaused = false
            speakCurrentParagraph()
        }
    }

    fun stop() {
        if (isInitialized) {
            tts?.stop()
            isPaused = false
            currentParagraphIndex = 0
            onStateChange?.invoke(false, false)
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate
        tts?.setSpeechRate(speechRate)
    }

    fun setLanguage(locale: Locale) {
        currentLocale = locale
        tts?.language = currentLocale
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        fun showVoiceReaderDialog(activity: Activity, title: String, contentText: String) {
            if (contentText.isBlank()) {
                toast(activity, "Tidak ada teks yang dapat dibacakan")
                return
            }

            var selectedLocale = Locale("id", "ID")
            var currentRate = 1.0f
            val reader = VoiceReaderManager(activity)

            val rootLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20))
                setBackgroundColor(Color.WHITE)
            }

            // Header
            val headerBox = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTv = TextView(activity).apply {
                text = "🔊 Voice Reader — $title"
                textSize = 16f
                setTextColor(Color.parseColor("#0F172A"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            headerBox.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rootLayout.addView(headerBox)

            // Progress text
            val progressTv = TextView(activity).apply {
                text = "Siap membaca..."
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(activity, 8), 0, dp(activity, 8))
            }
            rootLayout.addView(progressTv)

            // Current paragraph preview box
            val previewBox = TextView(activity).apply {
                text = if (contentText.length > 150) contentText.take(150) + "..." else contentText
                textSize = 13f
                setTextColor(Color.parseColor("#334155"))
                setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F8FAFC"))
                    setStroke(1, Color.parseColor("#E2E8F0"))
                    cornerRadius = dp(activity, 8).toFloat()
                }
            }
            rootLayout.addView(previewBox)

            // Speed Selector Bar
            val speedLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 12), 0, dp(activity, 8))
            }
            val speedLabel = TextView(activity).apply {
                text = "Kecepatan: "
                textSize = 12f
                setTextColor(Color.parseColor("#475569"))
            }
            speedLayout.addView(speedLabel)

            val speedRates = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            val speedButtons = mutableListOf<TextView>()

            speedRates.forEach { rate ->
                val btn = TextView(activity).apply {
                    text = "${rate}x"
                    textSize = 11f
                    setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4))
                    setTextColor(if (rate == currentRate) Color.WHITE else Color.parseColor("#1E293B"))
                    background = GradientDrawable().apply {
                        setColor(if (rate == currentRate) Color.parseColor("#2563EB") else Color.parseColor("#F1F5F9"))
                        cornerRadius = dp(activity, 6).toFloat()
                    }
                    setOnClickListener {
                        currentRate = rate
                        reader.setSpeechRate(currentRate)
                        speedButtons.forEachIndexed { idx, b ->
                            val r = speedRates[idx]
                            b.setTextColor(if (r == currentRate) Color.WHITE else Color.parseColor("#1E293B"))
                            (b.background as GradientDrawable).setColor(
                                if (r == currentRate) Color.parseColor("#2563EB") else Color.parseColor("#F1F5F9")
                            )
                        }
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(activity, 4), 0, dp(activity, 4), 0) }
                speedLayout.addView(btn, lp)
                speedButtons.add(btn)
            }
            rootLayout.addView(speedLayout)

            // Language Selector Bar
            val langLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 4), 0, dp(activity, 12))
            }
            val langLabel = TextView(activity).apply {
                text = "Bahasa: "
                textSize = 12f
                setTextColor(Color.parseColor("#475569"))
            }
            langLayout.addView(langLabel)

            val idBtn = TextView(activity)
            val enBtn = TextView(activity)

            val updateLangUi = { isId: Boolean ->
                idBtn.setTextColor(if (isId) Color.WHITE else Color.parseColor("#1E293B"))
                (idBtn.background as GradientDrawable).setColor(
                    if (isId) Color.parseColor("#16A34A") else Color.parseColor("#F1F5F9")
                )
                enBtn.setTextColor(if (!isId) Color.WHITE else Color.parseColor("#1E293B"))
                (enBtn.background as GradientDrawable).setColor(
                    if (!isId) Color.parseColor("#16A34A") else Color.parseColor("#F1F5F9")
                )
            }

            idBtn.apply {
                text = "🇮🇩 Indonesia"
                textSize = 11f
                setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
                background = GradientDrawable().apply { cornerRadius = dp(activity, 6).toFloat() }
                setOnClickListener {
                    selectedLocale = Locale("id", "ID")
                    reader.setLanguage(selectedLocale)
                    updateLangUi(true)
                }
            }
            enBtn.apply {
                text = "🇬🇧 English"
                textSize = 11f
                setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
                background = GradientDrawable().apply { cornerRadius = dp(activity, 6).toFloat() }
                setOnClickListener {
                    selectedLocale = Locale.US
                    reader.setLanguage(selectedLocale)
                    updateLangUi(false)
                }
            }
            updateLangUi(true)

            val lpMargin = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(activity, 4), 0, dp(activity, 4), 0) }
            langLayout.addView(idBtn, lpMargin)
            langLayout.addView(enBtn, lpMargin)
            rootLayout.addView(langLayout)

            // Play / Pause / Stop Control Buttons
            val ctrlBox = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(activity, 8), 0, 0)
            }

            val playBtn = TextView(activity).apply {
                text = "▶️ Putar Suara"
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB"))
                    cornerRadius = dp(activity, 8).toFloat()
                }
            }

            val stopBtn = TextView(activity).apply {
                text = "⏹️ Stop"
                textSize = 14f
                setTextColor(Color.parseColor("#1E293B"))
                setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E2E8F0"))
                    cornerRadius = dp(activity, 8).toFloat()
                }
            }

            ctrlBox.addView(playBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(activity, 12) })

            ctrlBox.addView(stopBtn)
            rootLayout.addView(ctrlBox)

            var isReading = false
            var isPaused = false

            playBtn.setOnClickListener {
                if (!isReading) {
                    isReading = true
                    isPaused = false
                    playBtn.text = "⏸️ Pause"
                    reader.startReading(contentText, selectedLocale)
                } else if (!isPaused) {
                    isPaused = true
                    playBtn.text = "▶️ Lanjutkan"
                    reader.pause()
                } else {
                    isPaused = false
                    playBtn.text = "⏸️ Pause"
                    reader.resume()
                }
            }

            stopBtn.setOnClickListener {
                isReading = false
                isPaused = false
                playBtn.text = "▶️ Putar Suara"
                progressTv.text = "Dihentikan"
                reader.stop()
            }

            reader.onProgressUpdate = { idx, total, textP ->
                progressTv.text = "Membaca paragraf $idx dari $total..."
                previewBox.text = textP
            }

            reader.onStateChange = { playing, paused ->
                isReading = playing || paused
                isPaused = paused
                playBtn.text = when {
                    paused -> "▶️ Lanjutkan"
                    playing -> "⏸️ Pause"
                    else -> "▶️ Putar Suara"
                }
                if (!playing && !paused) {
                    progressTv.text = "Selesai membaca seluruh dokumen."
                }
            }

            val dialog = AlertDialog.Builder(activity)
                .setView(rootLayout)
                .setOnDismissListener {
                    reader.shutdown()
                }
                .create()

            dialog.show()
        }

        private fun dp(ctx: Context, value: Int): Int {
            return (value * ctx.resources.displayMetrics.density).toInt()
        }

        private fun toast(ctx: Context, msg: String) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
