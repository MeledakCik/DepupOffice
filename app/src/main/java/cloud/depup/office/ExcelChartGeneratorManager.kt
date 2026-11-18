package cloud.depup.office

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

data class ChartSeries(
    val label: String,
    val value: Float,
    val color: Int
)

/**
 * Pembuat Grafik & Chart Otomatis dari Excel (Excel Chart Generator).
 * Mengonversi data tabel Excel/Spreadsheet menjadi Grafik Batang (Bar), Lingkaran (Pie), dan Garis (Line).
 */
object ExcelChartGeneratorManager {

    val CHART_COLORS = intArrayOf(
        0xFF1652F6.toInt(), // Biru
        0xFF35754C.toInt(), // Hijau
        0xFFF5A524.toInt(), // Amber
        0xFFC0262D.toInt(), // Merah
        0xFF72539E.toInt(), // Ungu
        0xFF0284C7.toInt()  // Cyan
    )

    fun showDialog(activity: AppCompatActivity, onPickFile: (cb: (Uri) -> Unit) -> Unit) {
        onPickFile { uri ->
            processSpreadsheet(activity, uri)
        }
    }

    private fun processSpreadsheet(activity: AppCompatActivity, uri: Uri) {
        val name = Files.nameOf(activity, uri)

        activity.work("Membaca data tabel Excel…", {
            val rows = if (name.endsWith(".csv", ignoreCase = true)) {
                OfficeReader.readCsv(activity, uri)
            } else {
                OfficeReader.readXlsx(activity, uri)
            }

            if (rows == null || rows.rows.isEmpty()) {
                throw IllegalStateException("Tabel Excel kosong atau tidak dapat dibaca")
            }

            val series = extractChartSeries(rows.rows)
            if (series.isEmpty()) {
                throw IllegalStateException("Tidak ditemukan kolom angka untuk dibuat grafik")
            }

            Pair(name, series)
        }) { res ->
            res.onSuccess { (fileName, series) ->
                showChartSheet(activity, uri, fileName, series)
            }.onFailure { activity.fail(it) }
        }
    }

    private fun extractChartSeries(rows: List<List<String>>): List<ChartSeries> {
        val series = ArrayList<ChartSeries>()
        if (rows.size < 2) return series

        // angka bergaya Indonesia ("15.000", "Rp 1.250.000") dikenali — dulu toDoubleOrNull() menganggapnya bukan angka
        fun num(s: String): Double? = XlsxWriter.parseNumber(s)
        val dataRows = if (rows.firstOrNull()?.any { num(it) != null } == true) rows else rows.drop(1)

        dataRows.take(12).forEachIndexed { idx, row ->
            val label = row.firstOrNull()?.trim()?.ifBlank { "Data ${idx + 1}" } ?: "Data ${idx + 1}"
            val numVal = row.drop(1).mapNotNull { num(it)?.toFloat() }.firstOrNull()
                ?: row.mapNotNull { num(it)?.toFloat() }.firstOrNull() ?: 0f

            if (numVal > 0f) {
                val color = CHART_COLORS[idx % CHART_COLORS.size]
                series.add(ChartSeries(label.take(15), numVal, color))
            }
        }

        return series
    }

    @SuppressLint("ThreadConstraint")
    private fun showChartSheet(
        activity: AppCompatActivity,
        srcUri: Uri,
        fileName: String,
        series: List<ChartSeries>
    ) {
        var chartType = 0 // 0 = Bar, 1 = Pie, 2 = Line

        activity.sheet { body, dismiss ->
            activity.sheetHeader(
                body, R.drawable.ic_sheet, C.XLS_FG, C.XLS_BG,
                "Grafik & Chart Excel", "$fileName • ${series.size} data", dismiss
            )
            val pad = activity.vbox().apply { setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(20)) }

            val chartFrame = FrameLayout(activity).apply {
                background = activity.shape(C.SURFACE2, 16)
                setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
            }

            fun renderChartView(): View {
                return CustomChartView(activity, series, chartType)
            }

            fun updateChart() {
                chartFrame.removeAllViews()
                chartFrame.addView(renderChartView(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, activity.dp(240)))
            }

            updateChart()
            pad.add(chartFrame)

            // Selector Tipe Grafik
            val typeHolder = activity.vbox()
            typeHolder.add(activity.segmented(listOf("Grafik Batang", "Grafik Lingkaran", "Grafik Garis"), chartType) {
                chartType = it
                updateChart()
            })
            pad.add(typeHolder, top = 12)

            val actionRow = activity.hbox().apply {
                add(activity.btn("Simpan Gambar Grafik", false, C.ACCENT) {
                    dismiss()
                    val bmp = generateChartBitmap(activity, series, chartType, fileName)
                    val out = Files.namedOut(activity, "Grafik_$fileName", "png")
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    activity.toast("Grafik disimpan ke Documents/DepUp Office/Gambar")
                    activity.showImagePreviewDialog(bmp, "Grafik $fileName")
                }, w = 0, weight = 1f)

                add(activity.btn("Simpan PDF Laporan", true, C.PRIMARY, R.drawable.ic_pdf) {
                    dismiss()
                    generateChartPdf(activity, series, chartType, fileName)
                }, w = 0, weight = 1f, side = 8)
            }

            pad.add(actionRow, top = 16)
            body.add(pad)
        }
    }

    private fun generateChartBitmap(ctx: Context, series: List<ChartSeries>, type: Int, title: String): Bitmap {
        val w = 1200; val h = 800
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1652F6.toInt(); textSize = 32f; isFakeBoldText = true }
        c.drawText("Laporan Grafik: $title", 50f, 60f, pTitle)

        val chartView = CustomChartView(ctx, series, type)
        chartView.measure(View.MeasureSpec.makeMeasureSpec(w - 100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h - 120, View.MeasureSpec.EXACTLY))
        chartView.layout(0, 0, w - 100, h - 120)

        c.save()
        c.translate(50f, 90f)
        chartView.draw(c)
        c.restore()

        return bmp
    }

    private fun generateChartPdf(activity: AppCompatActivity, series: List<ChartSeries>, type: Int, fileName: String) {
        val outName = "Laporan_Grafik_" + Files.baseName(fileName)
        val out = Files.newOut(activity, outName, "pdf")

        activity.work("Membuat PDF Laporan Grafik…", {
            val chartBmp = generateChartBitmap(activity, series, type, fileName)
            val doc = PdfDocument()
            val pw = 595; val ph = 842
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)

            val dstRect = android.graphics.Rect(40, 60, pw - 40, (ph * 0.6f).toInt())
            c.drawBitmap(chartBmp, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))

            doc.finishPage(page)
            FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
        }) { res ->
            res.onSuccess {
                activity.toast("PDF Laporan Grafik disimpan!")
                Files.open(activity, Files.uriFor(activity, out), PDF)
            }.onFailure { activity.fail(it) }
        }
    }

    private class CustomChartView(ctx: Context, val series: List<ChartSeries>, val type: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt(); textSize = 28f }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (series.isEmpty()) return

            val w = width.toFloat()
            val h = height.toFloat()
            val maxVal = series.maxOf { it.value }.coerceAtLeast(1f)

            when (type) {
                0 -> {
                    val barWidth = (w - 100f) / series.size - 20f
                    series.forEachIndexed { i, s ->
                        val barHeight = (s.value / maxVal) * (h - 100f)
                        val left = 50f + i * (barWidth + 20f)
                        val top = h - 60f - barHeight
                        val right = left + barWidth
                        val bottom = h - 60f

                        paint.color = s.color
                        c.drawRoundRect(RectF(left, top, right, bottom), 8f, 8f, paint)

                        c.drawText(s.label.take(8), left, h - 20f, textPaint.apply { textSize = 22f })
                        c.drawText(s.value.toInt().toString(), left, top - 10f, textPaint.apply { textSize = 20f })
                    }
                }
                1 -> {
                    val total = series.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
                    val cx = w / 2f
                    val cy = h / 2f
                    val radius = Math.min(w, h) / 2.5f
                    val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

                    var startAngle = -90f
                    series.forEach { s ->
                        val sweepAngle = (s.value / total) * 360f
                        paint.color = s.color
                        c.drawArc(rect, startAngle, sweepAngle, true, paint)

                        val midAngle = Math.toRadians((startAngle + sweepAngle / 2f).toDouble())
                        val tx = (cx + radius * 0.65f * cos(midAngle)).toFloat()
                        val ty = (cy + radius * 0.65f * sin(midAngle)).toFloat()

                        val pct = ((s.value / total) * 100).toInt()
                        if (pct > 5) {
                            c.drawText("$pct%", tx - 15f, ty + 10f, textPaint.apply { color = Color.WHITE; textSize = 22f })
                        }

                        startAngle += sweepAngle
                    }
                }
                2 -> {
                    val stepX = (w - 100f) / (series.size - 1).coerceAtLeast(1)
                    val points = series.mapIndexed { i, s ->
                        val x = 50f + i * stepX
                        val y = h - 60f - (s.value / maxVal) * (h - 100f)
                        Pair(x, y)
                    }

                    paint.color = 0xFF1652F6.toInt()
                    paint.strokeWidth = 6f
                    paint.style = Paint.Style.STROKE

                    for (i in 0 until points.size - 1) {
                        c.drawLine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second, paint)
                    }

                    paint.style = Paint.Style.FILL
                    points.forEachIndexed { i, pt ->
                        paint.color = series[i % series.size].color
                        c.drawCircle(pt.first, pt.second, 12f, paint)
                        c.drawText(series[i].value.toInt().toString(), pt.first - 15f, pt.second - 20f, textPaint.apply { textSize = 20f; color = 0xFF111111.toInt() })
                    }
                }
            }
        }
    }
}
