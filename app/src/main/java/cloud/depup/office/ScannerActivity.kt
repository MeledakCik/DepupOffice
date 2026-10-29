package cloud.depup.office

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ScannedPageData(
    val originalBitmap: Bitmap,
    var corners: List<ScannerPoint>,
    var warpedBitmap: Bitmap? = null,
    var filterType: FilterType = FilterType.MAGIC_COLOR,
    var filteredBitmap: Bitmap? = null
)

class ScannerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    CamScannerApp(onFinish = { finish() })
                }
            }
        }
    }
}

enum class ScannerStep {
    CAMERA,
    CROP_ADJUSTMENT,
    FILTER_SELECTION,
    PDF_PREVIEW
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamScannerApp(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableStateOf(ScannerStep.CAMERA) }
    val scannedPages = remember { mutableStateListOf<ScannedPageData>() }
    var activePageIndex by remember { mutableIntStateOf(0) }
    var isProcessing by remember { mutableStateOf(false) }
    var generatedPdfFile by remember { mutableStateOf<File?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isProcessing = true
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        try {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val bitmap = BitmapFactory.decodeStream(stream)
                                if (bitmap != null) {
                                    val detectedCorners = DocumentScanner.detectDocumentContour(bitmap)
                                    val warped = DocumentScanner.warpPerspective(bitmap, detectedCorners)
                                    val filtered = DocumentScanner.applyFilter(warped, FilterType.MAGIC_COLOR)
                                    scannedPages.add(
                                        ScannedPageData(
                                            originalBitmap = bitmap,
                                            corners = detectedCorners,
                                            warpedBitmap = warped,
                                            filterType = FilterType.MAGIC_COLOR,
                                            filteredBitmap = filtered
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                isProcessing = false
                if (scannedPages.isNotEmpty()) {
                    activePageIndex = scannedPages.size - 1
                    currentStep = ScannerStep.CROP_ADJUSTMENT
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (currentStep) {
                            ScannerStep.CAMERA -> "CamScanner • Ambil Foto"
                            ScannerStep.CROP_ADJUSTMENT -> "Potong & Deteksi Tepi (Halaman ${activePageIndex + 1}/${scannedPages.size})"
                            ScannerStep.FILTER_SELECTION -> "Pilih Filter Kertas"
                            ScannerStep.PDF_PREVIEW -> "File PDF Siap"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (currentStep) {
                            ScannerStep.CAMERA -> onFinish()
                            ScannerStep.CROP_ADJUSTMENT -> currentStep = ScannerStep.CAMERA
                            ScannerStep.FILTER_SELECTION -> currentStep = ScannerStep.CROP_ADJUSTMENT
                            ScannerStep.PDF_PREVIEW -> currentStep = ScannerStep.FILTER_SELECTION
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentStep) {
                ScannerStep.CAMERA -> {
                    CameraViewfinderScreen(
                        hasPermission = hasCameraPermission,
                        pageCount = scannedPages.size,
                        onImageCaptured = { bitmap ->
                            scope.launch {
                                isProcessing = true
                                withContext(Dispatchers.IO) {
                                    val detectedCorners = DocumentScanner.detectDocumentContour(bitmap)
                                    val warped = DocumentScanner.warpPerspective(bitmap, detectedCorners)
                                    val filtered = DocumentScanner.applyFilter(warped, FilterType.MAGIC_COLOR)
                                    scannedPages.add(
                                        ScannedPageData(
                                            originalBitmap = bitmap,
                                            corners = detectedCorners,
                                            warpedBitmap = warped,
                                            filterType = FilterType.MAGIC_COLOR,
                                            filteredBitmap = filtered
                                        )
                                    )
                                }
                                isProcessing = false
                                activePageIndex = scannedPages.size - 1
                                currentStep = ScannerStep.CROP_ADJUSTMENT
                            }
                        },
                        onOpenGallery = {
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onProceed = {
                            if (scannedPages.isNotEmpty()) {
                                currentStep = ScannerStep.CROP_ADJUSTMENT
                            }
                        }
                    )
                }

                ScannerStep.CROP_ADJUSTMENT -> {
                    if (scannedPages.isNotEmpty() && activePageIndex in scannedPages.indices) {
                        val currentPage = scannedPages[activePageIndex]
                        CropAdjustmentScreen(
                            pageData = currentPage,
                            onCornersUpdated = { newCorners ->
                                currentPage.corners = newCorners
                            },
                            onAutoDetect = {
                                scope.launch {
                                    isProcessing = true
                                    withContext(Dispatchers.Default) {
                                        currentPage.corners = DocumentScanner.detectDocumentContour(currentPage.originalBitmap)
                                    }
                                    isProcessing = false
                                }
                            },
                            onResetFull = {
                                currentPage.corners = listOf(
                                    ScannerPoint(0.01f, 0.01f),
                                    ScannerPoint(0.99f, 0.01f),
                                    ScannerPoint(0.99f, 0.99f),
                                    ScannerPoint(0.01f, 0.99f)
                                )
                            },
                            onApplyCrop = {
                                scope.launch {
                                    isProcessing = true
                                    withContext(Dispatchers.Default) {
                                        val warped = DocumentScanner.warpPerspective(currentPage.originalBitmap, currentPage.corners)
                                        currentPage.warpedBitmap = warped
                                        currentPage.filteredBitmap = DocumentScanner.applyFilter(warped, currentPage.filterType)
                                    }
                                    isProcessing = false
                                    currentStep = ScannerStep.FILTER_SELECTION
                                }
                            }
                        )
                    }
                }

                ScannerStep.FILTER_SELECTION -> {
                    if (scannedPages.isNotEmpty() && activePageIndex in scannedPages.indices) {
                        FilterSelectionScreen(
                            pages = scannedPages,
                            activeIdx = activePageIndex,
                            onSelectPage = { idx -> activePageIndex = idx },
                            onFilterChanged = { filter ->
                                val pg = scannedPages[activePageIndex]
                                pg.filterType = filter
                                scope.launch {
                                    isProcessing = true
                                    withContext(Dispatchers.Default) {
                                        val warped = pg.warpedBitmap ?: DocumentScanner.warpPerspective(pg.originalBitmap, pg.corners)
                                        pg.filteredBitmap = DocumentScanner.applyFilter(warped, filter)
                                    }
                                    isProcessing = false
                                }
                            },
                            onAddMorePages = {
                                currentStep = ScannerStep.CAMERA
                            },
                            onDeletePage = { idx ->
                                if (scannedPages.size > 1) {
                                    scannedPages.removeAt(idx)
                                    activePageIndex = activePageIndex.coerceIn(0, scannedPages.size - 1)
                                } else {
                                    scannedPages.clear()
                                    currentStep = ScannerStep.CAMERA
                                }
                            },
                            onGeneratePdf = {
                                scope.launch {
                                    isProcessing = true
                                    withContext(Dispatchers.IO) {
                                        val bitmapsToExport = scannedPages.mapNotNull { it.filteredBitmap ?: it.warpedBitmap ?: it.originalBitmap }
                                        generatedPdfFile = PdfGenerator.generatePdf(context, bitmapsToExport, "CamScanner_Depup")
                                    }
                                    isProcessing = false
                                    currentStep = ScannerStep.PDF_PREVIEW
                                }
                            }
                        )
                    }
                }

                ScannerStep.PDF_PREVIEW -> {
                    generatedPdfFile?.let { pdfFile ->
                        PdfPreviewSuccessScreen(
                            pdfFile = pdfFile,
                            onOpenPdf = {
                                val uri = Files.uriFor(context, pdfFile)
                                Files.open(context, uri, PDF)
                            },
                            onSharePdf = {
                                val uri = Files.uriFor(context, pdfFile)
                                Files.share(context, uri, PDF, false)
                            },
                            onPrintPdf = {
                                val uri = Files.uriFor(context, pdfFile)
                                PrintHelper.printPdf(context, uri, pdfFile.name)
                            },
                            onDone = onFinish
                        )
                    }
                }
            }

            if (isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF2563EB))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Memproses Gambar OpenCV...", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraViewfinderScreen(
    hasPermission: Boolean,
    pageCount: Int,
    onImageCaptured: (Bitmap) -> Unit,
    onOpenGallery: () -> Unit,
    onProceed: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Izin Kamera Diperlukan untuk Pindaian", color = Color.White)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    imageCapture = ImageCapture.Builder().build()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Flash Button
        IconButton(
            onClick = { flashEnabled = !flashEnabled },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(Icons.Default.FlashOn, contentDescription = "Flash", tint = if (flashEnabled) Color.Yellow else Color.White)
        }

        // Camera Controls Bottom Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery Picker
            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = "Galeri", tint = Color.White)
            }

            // Capture Shutter Button
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .padding(6.dp)
                    .clickable {
                        val capture = imageCapture ?: return@clickable
                        val photoFile = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                        capture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                                    if (bitmap != null) {
                                        onImageCaptured(bitmap)
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Toast.makeText(context, "Gagal mengambil foto", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(3.dp, Color(0xFF2563EB), CircleShape)
                )
            }

            // Page Counter & Proceed Button
            if (pageCount > 0) {
                Button(
                    onClick = onProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Lanjut ($pageCount)")
                }
            } else {
                Spacer(modifier = Modifier.width(52.dp))
            }
        }
    }
}

@Composable
fun CropAdjustmentScreen(
    pageData: ScannedPageData,
    onCornersUpdated: (List<ScannerPoint>) -> Unit,
    onAutoDetect: () -> Unit,
    onResetFull: () -> Unit,
    onApplyCrop: () -> Unit
) {
    var corners by remember { mutableStateOf(pageData.corners) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        // Option Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onAutoDetect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF38BDF8))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Auto Detect", fontSize = 12.sp)
            }

            Button(
                onClick = onResetFull,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset Penuh", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Draggable 4-Corner Overlay Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = pageData.originalBitmap
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Foto Asli",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            // Overlaid Polygon and Draggable Handles
            Box(modifier = Modifier.fillMaxSize()) {
                val ptTL = corners.getOrNull(0) ?: ScannerPoint(0.05f, 0.05f)
                val ptTR = corners.getOrNull(1) ?: ScannerPoint(0.95f, 0.05f)
                val ptBR = corners.getOrNull(2) ?: ScannerPoint(0.95f, 0.95f)
                val ptBL = corners.getOrNull(3) ?: ScannerPoint(0.05f, 0.95f)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    val pTL = Offset(ptTL.x * w, ptTL.y * h)
                    val pTR = Offset(ptTR.x * w, ptTR.y * h)
                    val pBR = Offset(ptBR.x * w, ptBR.y * h)
                    val pBL = Offset(ptBL.x * w, ptBL.y * h)

                    val path = Path().apply {
                        moveTo(pTL.x, pTL.y)
                        lineTo(pTR.x, pTR.y)
                        lineTo(pBR.x, pBR.y)
                        lineTo(pBL.x, pBL.y)
                        close()
                    }

                    drawPath(path, Color(0xFF38BDF8).copy(alpha = 0.3f))
                    drawPath(path, Color(0xFF38BDF8), style = Stroke(width = 4f))
                }

                // Draggable Handles
                CornerHandle(
                    point = ptTL,
                    color = Color(0xFFEF4444),
                    onDrag = { dx, dy, width, height ->
                        val newTL = ScannerPoint(
                            (ptTL.x + dx / width).coerceIn(0f, 1f),
                            (ptTL.y + dy / height).coerceIn(0f, 1f)
                        )
                        corners = listOf(newTL, ptTR, ptBR, ptBL)
                        onCornersUpdated(corners)
                    }
                )

                CornerHandle(
                    point = ptTR,
                    color = Color(0xFF3B82F6),
                    onDrag = { dx, dy, width, height ->
                        val newTR = ScannerPoint(
                            (ptTR.x + dx / width).coerceIn(0f, 1f),
                            (ptTR.y + dy / height).coerceIn(0f, 1f)
                        )
                        corners = listOf(ptTL, newTR, ptBR, ptBL)
                        onCornersUpdated(corners)
                    }
                )

                CornerHandle(
                    point = ptBR,
                    color = Color(0xFF10B981),
                    onDrag = { dx, dy, width, height ->
                        val newBR = ScannerPoint(
                            (ptBR.x + dx / width).coerceIn(0f, 1f),
                            (ptBR.y + dy / height).coerceIn(0f, 1f)
                        )
                        corners = listOf(ptTL, ptTR, newBR, ptBL)
                        onCornersUpdated(corners)
                    }
                )

                CornerHandle(
                    point = ptBL,
                    color = Color(0xFFF59E0B),
                    onDrag = { dx, dy, width, height ->
                        val newBL = ScannerPoint(
                            (ptBL.x + dx / width).coerceIn(0f, 1f),
                            (ptBL.y + dy / height).coerceIn(0f, 1f)
                        )
                        corners = listOf(ptTL, ptTR, ptBR, newBL)
                        onCornersUpdated(corners)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onApplyCrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Crop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Potong & Ratakan Kertas (Perspective Warp)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CornerHandle(
    point: ScannerPoint,
    color: Color,
    onDrag: (dx: Float, dy: Float, width: Float, height: Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y, size.width.toFloat(), size.height.toFloat())
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = point.x * size.width
            val cy = point.y * size.height

            drawCircle(color, radius = 24f, center = Offset(cx, cy))
            drawCircle(Color.White, radius = 12f, center = Offset(cx, cy))
        }
    }
}

@Composable
fun FilterSelectionScreen(
    pages: List<ScannedPageData>,
    activeIdx: Int,
    onSelectPage: (Int) -> Unit,
    onFilterChanged: (FilterType) -> Unit,
    onAddMorePages: () -> Unit,
    onDeletePage: (Int) -> Unit,
    onGeneratePdf: () -> Unit
) {
    val activePage = pages.getOrNull(activeIdx) ?: return
    val displayBitmap = activePage.filteredBitmap ?: activePage.warpedBitmap ?: activePage.originalBitmap

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        // Main Image Display
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = "Hasil Crop & Filter",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Filter Options Row
        Text("Pilih Filter Dokumen:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FilterChip(
                label = "Original",
                isSelected = activePage.filterType == FilterType.ORIGINAL,
                onClick = { onFilterChanged(FilterType.ORIGINAL) }
            )

            FilterChip(
                label = "✨ Magic Color",
                isSelected = activePage.filterType == FilterType.MAGIC_COLOR,
                onClick = { onFilterChanged(FilterType.MAGIC_COLOR) }
            )

            FilterChip(
                label = "🖤 Black & White",
                isSelected = activePage.filterType == FilterType.BLACK_AND_WHITE,
                onClick = { onFilterChanged(FilterType.BLACK_AND_WHITE) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Page Thumbnails Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Halaman (${pages.size}):", color = Color.White, fontSize = 13.sp)

            IconButton(onClick = onAddMorePages) {
                Icon(Icons.Default.Add, contentDescription = "Tambah Halaman", tint = Color(0xFF38BDF8))
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(pages) { idx, page ->
                val bmp = page.filteredBitmap ?: page.warpedBitmap ?: page.originalBitmap
                val isSelected = idx == activeIdx

                Box(
                    modifier = Modifier
                        .size(64.dp, 84.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color(0xFF2563EB) else Color.Gray,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectPage(idx) }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    IconButton(
                        onClick = { onDeletePage(idx) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(Color.Red.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Generate PDF Button
        Button(
            onClick = onGeneratePdf,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Gabungkan & Simpan ke PDF", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun PdfPreviewSuccessScreen(
    pdfFile: File,
    onOpenPdf: () -> Unit,
    onSharePdf: () -> Unit,
    onPrintPdf: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color(0xFF16A34A).copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("PDF Berhasil Dibuat!", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Nama Berkas:", color = Color.Gray, fontSize = 12.sp)
                Text(pdfFile.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(8.dp))

                Text("Lokasi Simpan:", color = Color.Gray, fontSize = 12.sp)
                Text("/Documents/DepupOffice/${pdfFile.name}", color = Color(0xFF38BDF8), fontSize = 12.sp)

                Spacer(modifier = Modifier.height(8.dp))

                Text("Ukuran Berkas:", color = Color.Gray, fontSize = 12.sp)
                Text(Files.fmtSize(pdfFile.length()), color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onOpenPdf,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Buka Dokumen PDF")
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSharePdf,
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bagikan", fontSize = 13.sp)
            }

            Button(
                onClick = onPrintPdf,
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cetak", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            modifier = Modifier.border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
        ) {
            Text("Selesai & Kembali ke Beranda", color = Color.White)
        }
    }
}
