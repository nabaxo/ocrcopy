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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlin.math.sqrt

/**
 * Photos are kept at full camera resolution unless the bitmap would exceed this size.
 * Android refuses to draw bitmaps over 100 MB ("trying to draw too large bitmap"), on any
 * phone, so stay under that. A 12.5 MP Pixel 8 Pro photo is ~50 MB and is never shrunk.
 */
private const val MAX_BITMAP_BYTES = 90L * 1024 * 1024
private const val MAX_ZOOM = 8f

/**
 * Bottom panel height, same on the camera and selection screens so the picture area is
 * identical on both. Fits 3 lines of selected text plus the buttons.
 */
private val BOTTOM_PANEL_HEIGHT = 176.dp

/** Dims the photo everywhere except detected text lines (ARGB: 50% black). */
private const val ScrimArgb = 0x80000000.toInt()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Top of every screen is camera/photo (black), so status bar icons are always light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            OcrCopyTheme {
                App()
            }
        }
    }
}

/** Material 3. Wallpaper-based dynamic color on Android 12+, follows system light/dark. */
@Composable
private fun OcrCopyTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Color for things drawn on top of the photo. The photo area is dark-ish regardless of
 * app theme, so always use the dark scheme's primary (a light tone) for contrast.
 */
@Composable
private fun overlayColor(): Color {
    val context = LocalContext.current
    val darkScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
    return darkScheme.primary
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

    Box(Modifier.fillMaxSize()) {
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera access", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "OCR Copy needs the camera to read text. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Grant camera access") }
        }
    }
}

@Composable
private fun CameraScreen(busy: Boolean, onShutter: (ImageCapture) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Biggest 4:3 photo the camera offers (Pixel 8 Pro: ~4080x3072).
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            .build()
    }

    // Release the camera while the frozen frame is shown.
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    // Same layout as SelectScreen (status bar, picture area, fixed-height bottom panel) and
    // FIT_CENTER, so the frozen photo appears exactly where the viewfinder showed it.
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        // Same 4:3 shape as the photo, so both show the same field of view.
                        val preview = Preview.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                    .build(),
                            )
                            .build()
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

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    if (busy) "Reading text…" else "Point at text, then tap the shutter",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(BOTTOM_PANEL_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (busy) Color.Gray else Color.White)
                    .clickable(enabled = !busy) { onShutter(imageCapture) },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }
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
    val accent = overlayColor()
    // Corner rounding / merge distance for the gooey shapes, in image pixels.
    val gooeyRadius = scan.textHeight * 0.3f

    // Dim layer: dark everywhere except the text lines, which are rounded and melted
    // together. Computed once per photo.
    val scrim = remember(scan) {
        gooeyLayer(
            image.width, image.height, scan.lineQuads, gooeyRadius,
            inside = 0x00000000, outside = ScrimArgb,
        ).asImageBitmap()
    }
    val density = LocalDensity.current
    val handleRadius = with(density) { 11.dp.toPx() }
    val grabRadius = with(density) { 36.dp.toPx() }

    fun fit() = Fit.of(viewSize, image.width, image.height)

    // Teardrop handles hang below the first and last selected glyph, like system text handles.
    // Anchor = the glyph corner the handle's point touches. Center = middle of the round part.
    // All in the canvas' own coords (before zoom/pan); sizes are divided by zoom to stay
    // constant on screen.
    fun handleAnchors(range: IntRange): Pair<Offset, Offset> {
        val f = fit()
        val start = f.toView(glyphs[range.first].quad[3]) // bottom-left
        val end = f.toView(glyphs[range.last].quad[2]) // bottom-right
        return start to end
    }

    fun handleCenters(range: IntRange): Pair<Offset, Offset> {
        val r = handleRadius / zoom
        val (start, end) = handleAnchors(range)
        return (start + Offset(-r, r)) to (end + Offset(r, r))
    }

    val text = selection?.let { selectedText(glyphs, it) }.orEmpty()

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                // Two fingers: zoom and pan. One finger while zoomed in: pan, unless the drag
                // starts on a selection handle. Lives here, outside the zoomed layer, so
                // positions are in screen space; panning from inside the layer would move the
                // layer under the finger and stutter. Runs in the Initial pass so it wins over
                // the one-finger selection gestures below.
                .pointerInput(scan) {
                    fun clampedPan(p: Offset, z: Float) = Offset(
                        p.x.coerceIn(size.width * (1f - z), 0f),
                        p.y.coerceIn(size.height * (1f - z), 0f),
                    )

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startsOnHandle = selection?.let { range ->
                            val local = (down.position - pan) / zoom
                            val (start, end) = handleCenters(range)
                            val limit = grabRadius / zoom
                            (local - start).getDistance() < limit || (local - end).getDistance() < limit
                        } ?: false
                        var panning = false
                        var travelled = Offset.Zero

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                val newPan = centroid - (centroid - pan) * (newZoom / zoom) +
                                    event.calculatePan()
                                zoom = newZoom
                                pan = clampedPan(newPan, newZoom)
                                // Lifting one finger afterwards keeps panning with the other.
                                panning = true
                                event.changes.forEach { it.consume() }
                            } else if (fingers == 1 && zoom > 1f && !startsOnHandle) {
                                val change = event.changes.first { it.pressed }
                                val delta = change.position - change.previousPosition
                                if (!panning) {
                                    // Wait for a real drag so taps and long-presses still work.
                                    travelled += delta
                                    panning = travelled.getDistance() > viewConfiguration.touchSlop
                                }
                                if (panning) {
                                    pan = clampedPan(pan + delta, zoom)
                                    change.consume()
                                }
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
                        fun selectWordAt(pos: Offset) {
                            val f = fit()
                            val maxDistance = grabRadius / zoom / f.scale
                            val hit = glyphAt(glyphs, f.toImage(pos), maxDistance)
                            if (hit != null) {
                                selection = wordRange(glyphs, hit)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                        detectTapGestures(
                            onLongPress = { pos -> selectWordAt(pos) },
                            onDoubleTap = { pos -> selectWordAt(pos) },
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
                                // Middle of the glyph's left/right edge, following the tilt.
                                val anchor = if (activeHandle == 1) {
                                    val q = glyphs[range.first].quad
                                    f.toView((q[0] + q[3]) / 2f)
                                } else {
                                    val q = glyphs[range.last].quad
                                    f.toView((q[1] + q[2]) / 2f)
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
                // Photo and the reduced-size layers all stretch over the same area.
                val dstOffset = IntOffset(f.offset.x.roundToInt(), f.offset.y.roundToInt())
                val dstSize = IntSize(
                    (image.width * f.scale).roundToInt(),
                    (image.height * f.scale).roundToInt(),
                )
                drawImage(image, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Medium)
                drawImage(scrim, dstOffset = dstOffset, dstSize = dstSize, filterQuality = FilterQuality.Low)

                val range = selection
                if (range != null) {
                    // One rounded shape per selected line; not merged. Multiply works like a
                    // highlighter pen: paper takes the color, dark text stays dark.
                    val highlight = Path()
                    for (quad in selectionQuads(glyphs, range)) {
                        highlight.addRoundedPolygon(quad.map { f.toView(it) }, gooeyRadius * f.scale)
                    }
                    drawPath(highlight, accent, blendMode = BlendMode.Multiply)
                    val (start, end) = handleAnchors(range)
                    val r = handleRadius / zoom
                    drawPath(teardropHandle(start, r, isStart = true), accent)
                    drawPath(teardropHandle(end, r, isStart = false), accent)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            // Fixed height (matches CameraScreen) so the photo area never changes size,
            // whatever is selected.
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(BOTTOM_PANEL_HEIGHT)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                val textModifier = Modifier.weight(1f).fillMaxWidth()
                if (text.isEmpty()) {
                    val hint = if (glyphs.isEmpty()) {
                        "No text found. Retake."
                    } else {
                        "Long-press or double-tap a word, drag the handles. Pinch to zoom."
                    }
                    Text(
                        hint,
                        modifier = textModifier,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text,
                        modifier = textModifier,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onRetake,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Retake")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { copyToClipboard(context, text) },
                        enabled = text.isNotEmpty(),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
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

/** Polygon with each corner cut by a curve of about [radius]. */
private fun Path.addRoundedPolygon(points: List<Offset>, radius: Float) {
    val n = points.size
    for (i in 0 until n) {
        val corner = points[i]
        val prev = points[(i - 1 + n) % n]
        val next = points[(i + 1) % n]
        // Never cut more than half an edge, or neighbouring curves overlap.
        val r = min(radius, min((prev - corner).getDistance(), (next - corner).getDistance()) / 2f)
        val curveStart = corner + unit(prev - corner) * r
        val curveEnd = corner + unit(next - corner) * r
        if (i == 0) moveTo(curveStart.x, curveStart.y) else lineTo(curveStart.x, curveStart.y)
        quadraticTo(corner.x, corner.y, curveEnd.x, curveEnd.y)
    }
    close()
}

/**
 * Selection handle shaped like Android's text handles: a circle with one sharp corner
 * touching [anchor]. Start handle hangs down-left of it, end handle down-right.
 */
private fun teardropHandle(anchor: Offset, radius: Float, isStart: Boolean): Path {
    val diameter = radius * 2
    val round = CornerRadius(radius)
    val sharp = CornerRadius.Zero
    val shape = if (isStart) {
        val rect = Rect(anchor.x - diameter, anchor.y, anchor.x, anchor.y + diameter)
        RoundRect(rect, topLeft = round, topRight = sharp, bottomRight = round, bottomLeft = round)
    } else {
        val rect = Rect(anchor.x, anchor.y, anchor.x + diameter, anchor.y + diameter)
        RoundRect(rect, topLeft = sharp, topRight = round, bottomRight = round, bottomLeft = round)
    }
    return Path().apply { addRoundRect(shape) }
}

private fun uprightBitmap(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    val bytes = raw.width.toLong() * raw.height * 4 // ARGB_8888
    val scale = if (bytes > MAX_BITMAP_BYTES) sqrt(MAX_BITMAP_BYTES.toFloat() / bytes) else 1f
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
