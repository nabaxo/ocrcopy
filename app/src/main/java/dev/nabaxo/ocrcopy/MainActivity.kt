package dev.nabaxo.ocrcopy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Photos are shrunk to this long side before OCR. Plenty for text, keeps memory sane. */
private const val MAX_IMAGE_SIDE = 3000
private const val MAX_ZOOM = 8f

private val HighlightColor = Color(0x6633B5E5)
private val HandleColor = Color(0xFF33B5E5)
private val WordOutlineColor = Color(0x66FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        mutableStateOf(granted)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(Unit) {
        onDispose { recognizer.close() }
    }

    var scan by remember { mutableStateOf<Scan?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun capture(imageCapture: ImageCapture) {
        busy = true
        val callback = object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = uprightBitmap(image)
                image.close()
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text ->
                        scan = buildScan(bitmap, text)
                        busy = false
                    }
                    .addOnFailureListener { e ->
                        toast(context, "OCR failed: ${e.message}")
                        busy = false
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                toast(context, "Capture failed: ${exception.message}")
                busy = false
            }
        }
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), callback)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val currentScan = scan
        when {
            !hasCameraPermission -> PermissionScreen(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
            currentScan != null -> SelectScreen(currentScan, onRetake = { scan = null })
            else -> CameraScreen(busy, onShutter = { capture(it) })
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera permission is needed to read text.", color = Color.White)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) { Text("Grant camera") }
    }
}

@Composable
private fun CameraScreen(busy: Boolean, onShutter: (ImageCapture) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Release the camera while the frozen frame is shown.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    cameraProvider = provider
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .size(76.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(if (busy) Color.Gray else Color.White)
                .clickable(enabled = !busy) { onShutter(imageCapture) },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black)
        }
    }
}

@Composable
private fun SelectScreen(scan: Scan, onRetake: () -> Unit) {
    BackHandler(onBack = onRetake)

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val glyphs = scan.glyphs
    val image = remember(scan) { scan.bitmap.asImageBitmap() }

    var selection by remember(scan) { mutableStateOf<IntRange?>(null) }
    var zoom by remember(scan) { mutableFloatStateOf(1f) }
    var pan by remember(scan) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(Size.Zero) }

    val density = LocalDensity.current
    val handleRadius = with(density) { 10.dp.toPx() }
    val grabRadius = with(density) { 36.dp.toPx() }
    val outlineWidth = with(density) { 1.dp.toPx() }

    fun fit() = Fit.of(viewSize, image.width, image.height)

    // Handle circles hang just below the first and last selected glyph.
    // Positions are in the canvas' own coords (before zoom/pan), so sizes are divided by zoom
    // to stay constant on screen.
    fun handleCenters(range: IntRange): Pair<Offset, Offset> {
        val f = fit()
        val below = Offset(0f, handleRadius / zoom)
        val start = f.toView(glyphs[range.first].box.bottomLeft) + below
        val end = f.toView(glyphs[range.last].box.bottomRight) + below
        return start to end
    }

    val text = selection?.let { selectedText(glyphs, it) }.orEmpty()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                // Two fingers: zoom and pan. Runs in the Initial pass so it wins over the
                // one-finger selection gestures below.
                .pointerInput(scan) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val newPan = centroid - (centroid - pan) * (newZoom / zoom) +
                                    event.calculatePan()
                                val minX = size.width * (1f - newZoom)
                                val minY = size.height * (1f - newZoom)
                                zoom = newZoom
                                pan = Offset(newPan.x.coerceIn(minX, 0f), newPan.y.coerceIn(minY, 0f))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewSize = it.toSize() }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
                    // Pointer positions below arrive in canvas coords (zoom/pan already undone).
                    .pointerInput(scan) {
                        detectTapGestures(
                            onLongPress = { pos ->
                                val f = fit()
                                val maxDistance = grabRadius / zoom / f.scale
                                val hit = glyphAt(glyphs, f.toImage(pos), maxDistance)
                                if (hit != null) {
                                    selection = wordRange(glyphs, hit)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onTap = { pos ->
                                val range = selection ?: return@detectTapGestures
                                val (start, end) = handleCenters(range)
                                val limit = grabRadius / zoom
                                val onHandle = (pos - start).getDistance() < limit ||
                                    (pos - end).getDistance() < limit
                                if (!onHandle) selection = null
                            },
                        )
                    }
                    .pointerInput(scan) {
                        // 0 = none, 1 = start handle, 2 = end handle
                        var activeHandle = 0
                        // Finger sits below the handle; aim at the text line, not under the finger.
                        var aimOffset = Offset.Zero
                        detectDragGestures(
                            onDragStart = { pos ->
                                activeHandle = 0
                                val range = selection ?: return@detectDragGestures
                                val (start, end) = handleCenters(range)
                                val toStart = (pos - start).getDistance()
                                val toEnd = (pos - end).getDistance()
                                if (min(toStart, toEnd) > grabRadius / zoom) return@detectDragGestures

                                val f = fit()
                                activeHandle = if (toStart <= toEnd) 1 else 2
                                val anchor = if (activeHandle == 1) {
                                    f.toView(glyphs[range.first].box.centerLeft)
                                } else {
                                    f.toView(glyphs[range.last].box.centerRight)
                                }
                                aimOffset = anchor - pos
                            },
                            onDrag = { change, _ ->
                                if (activeHandle == 0) return@detectDragGestures
                                change.consume()
                                val range = selection ?: return@detectDragGestures
                                val f = fit()
                                val aim = f.toImage(change.position + aimOffset)
                                val target = glyphAt(glyphs, aim) ?: return@detectDragGestures

                                var first = range.first
                                var last = range.last
                                if (activeHandle == 1) first = target else last = target
                                if (first > last) {
                                    // Handles crossed: swap roles so dragging stays smooth.
                                    val tmp = first
                                    first = last
                                    last = tmp
                                    activeHandle = 3 - activeHandle
                                }
                                selection = first..last
                            },
                            onDragEnd = { activeHandle = 0 },
                            onDragCancel = { activeHandle = 0 },
                        )
                    },
            ) {
                val f = Fit.of(size, image.width, image.height)
                drawImage(
                    image = image,
                    dstOffset = IntOffset(f.offset.x.roundToInt(), f.offset.y.roundToInt()),
                    dstSize = IntSize(
                        (image.width * f.scale).roundToInt(),
                        (image.height * f.scale).roundToInt(),
                    ),
                    filterQuality = FilterQuality.Medium,
                )

                val outline = Stroke(width = outlineWidth / zoom)
                for (box in scan.wordBoxes) {
                    val r = f.toView(box)
                    drawRect(WordOutlineColor, r.topLeft, r.size, style = outline)
                }

                val range = selection
                if (range != null) {
                    for (i in range) {
                        val r = f.toView(glyphs[i].box)
                        drawRect(HighlightColor, r.topLeft, r.size)
                    }
                    val (start, end) = handleCenters(range)
                    drawCircle(HandleColor, handleRadius / zoom, start)
                    drawCircle(HandleColor, handleRadius / zoom, end)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                val hint = when {
                    glyphs.isEmpty() -> "No text found. Retake."
                    text.isEmpty() -> "Long-press a word, drag the handles. Pinch to zoom."
                    else -> text
                }
                Text(
                    hint,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = if (text.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onRetake) { Text("Retake") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { copyToClipboard(context, text) }, enabled = text.isNotEmpty()) {
                        Text("Copy")
                    }
                }
            }
        }
    }
}

/** Maps image pixels to canvas coords for an image letterboxed with "fit" scaling. */
private class Fit(val scale: Float, val offset: Offset) {
    fun toView(p: Offset) = offset + p * scale
    fun toView(r: Rect) = Rect(toView(r.topLeft), toView(r.bottomRight))
    fun toImage(v: Offset) = (v - offset) / scale

    companion object {
        fun of(view: Size, imageWidth: Int, imageHeight: Int): Fit {
            if (view.width <= 0f || view.height <= 0f) return Fit(1f, Offset.Zero)
            val scale = min(view.width / imageWidth, view.height / imageHeight)
            val offset = Offset(
                (view.width - imageWidth * scale) / 2f,
                (view.height - imageHeight * scale) / 2f,
            )
            return Fit(scale, offset)
        }
    }
}

private fun uprightBitmap(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    val longSide = max(raw.width, raw.height)
    val scale = if (longSide > MAX_IMAGE_SIDE) MAX_IMAGE_SIDE.toFloat() / longSide else 1f
    if (rotation == 0 && scale == 1f) return raw

    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        postScale(scale, scale)
    }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(context, "Copied")
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
