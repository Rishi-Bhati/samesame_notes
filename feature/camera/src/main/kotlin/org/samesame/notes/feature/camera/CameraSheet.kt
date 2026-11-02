package org.samesame.notes.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

/**
 * Insert > Camera as a live camera sheet over the bottom of the screen (owner's reference: a chat app's camera sheet).
 * Full width, the bottom [CameraSheetMath.HEIGHT_FRACTION] of the window, 18 dp top corners, no scrim: the note stays
 * visible above. It slides up when shown and down when closed (Back, the close button, a drag down). Controls on one row:
 * close (bottom left), shutter (middle), flip camera (bottom right); flash toggle at the top right. A shot is written to
 * [outputDir] (upright JPEG) and handed to [onCaptured] at once (no review step), then the sheet closes.
 * [onUnavailable] runs when the camera cannot be opened (no camera, bind failure): the caller falls back to the system
 * camera. The caller holds the CAMERA permission before showing it.
 */
@Composable
fun CameraSheet(
    outputDir: File,
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val captured by rememberUpdatedState(onCaptured)
    val close by rememberUpdatedState(onClose)
    val unavailable by rememberUpdatedState(onUnavailable)
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val preview = remember { Preview.Builder().build() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // The activity handles rotation itself (no recreation), so the use cases are told the new display rotation: the
    // preview stays upright and the saved JPEG carries the right orientation.
    DisposableEffect(context, preview, capture, previewView) {
        val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val rotation = previewView.display?.rotation ?: return
                preview.targetRotation = rotation
                capture.targetRotation = rotation
            }
        }
        displays?.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { displays?.unregisterDisplayListener(listener) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val sheetPx = with(density) { CameraSheetMath.sheetHeight(maxHeight.toPx()) }
        val sheetDp = with(density) { sheetPx.toDp() }
        val offset = remember { Animatable(sheetPx) }
        var closing by remember { mutableStateOf(false) }
        fun dismiss() {
            if (closing) return
            closing = true
            scope.launch {
                offset.animateTo(sheetPx, tween(CameraSheetMath.SLIDE_MS, easing = FastOutLinearInEasing))
                close()
            }
        }
        LaunchedEffect(Unit) { offset.animateTo(0f, tween(CameraSheetMath.SLIDE_MS, easing = LinearOutSlowInEasing)) }
        BackHandler { dismiss() }

        // Bind the use cases to the screen's lifecycle; rebinding follows the lens.
        DisposableEffect(front, lifecycleOwner) {
            val future = ProcessCameraProvider.getInstance(context)
            var provider: ProcessCameraProvider? = null
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    if (!p.hasCamera(selector)) {
                        if (front) { front = false; return@addListener }
                        unavailable(); return@addListener
                    }
                    p.unbindAll()
                    preview.surfaceProvider = previewView.surfaceProvider
                    p.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                } catch (_: Exception) {
                    unavailable()
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose { runCatching { provider?.unbindAll() } }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetDp)
                .graphicsLayer { translationY = offset.value }
                .clip(RoundedCornerShape(topStart = CameraSheetMath.CORNER_DP.dp, topEnd = CameraSheetMath.CORNER_DP.dp))
                .background(Color.Black)
                .pointerInput(sheetPx) {
                    val tracker = VelocityTracker()
                    var drag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f; tracker.resetTracking() },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            drag = CameraSheetMath.dragOffset(drag + dy, sheetPx)
                            scope.launch { offset.snapTo(drag) }
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y / density.density
                            if (CameraSheetMath.dismissOnRelease(drag, sheetPx, v)) dismiss()
                            else scope.launch { offset.animateTo(0f, spring()) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                    )
                },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            // Flash toggle (top right).
            RoundButton(
                label = stringResource(if (flash) R.string.feature_camera_flash_on else R.string.feature_camera_flash_off),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                size = 40,
                onClick = {
                    flash = !flash
                    capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                },
            ) { Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, null, Modifier.size(22.dp), tint = Color.White) }
            val rowCenter = CameraSheetMath.CONTROLS_CENTER_FROM_BOTTOM_DP.dp
            // Close (bottom left).
            RoundButton(
                label = stringResource(R.string.feature_camera_close),
                modifier = Modifier.align(Alignment.BottomStart).offset(x = 58.7.dp - 21.5.dp, y = -(rowCenter - 21.5.dp)),
                size = 43,
                onClick = { dismiss() },
            ) { Icon(AppIcons.Back, null, Modifier.size(24.dp), tint = Color.White) }
            // Shutter (middle): white disc in a grey ring.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(rowCenter - 36.dp))
                    .size(72.dp)
                    .border(5.dp, Color(0xB3FFFFFF), CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (busy) Color(0xFFBDBDBD) else Color.White)
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = stringResource(R.string.feature_camera_shutter)) {
                        busy = true
                        val file = File(outputDir.apply { mkdirs() }, CameraSheetMath.captureFileName(System.currentTimeMillis()))
                        takePhoto(context, capture, file, ContextCompat.getMainExecutor(context)) { ok ->
                            if (!ok) { busy = false; return@takePhoto }
                            scope.launch {
                                withContext(Dispatchers.IO) { CapturedPhoto.makeUpright(file) }
                                captured(file)
                                dismiss()
                            }
                        }
                    },
            )
            // Flip camera (bottom right).
            RoundButton(
                label = stringResource(R.string.feature_camera_flip),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = -(58.7.dp - 20.dp), y = -(rowCenter - 20.dp)),
                size = 40,
                onClick = { front = !front },
            ) { Icon(Icons.Outlined.Cameraswitch, null, Modifier.size(22.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun RoundButton(label: String, modifier: Modifier, size: Int, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun takePhoto(context: Context, capture: ImageCapture, file: File, executor: Executor, done: (Boolean) -> Unit) {
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(
        options, executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(file.length() > 0)
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                done(false)
            }
        },
    )
}

/** Turns a captured JPEG upright (applies its EXIF orientation to the pixels) and bounds its size. */
internal object CapturedPhoto {
    private const val MAX_SIDE = 4096

    fun makeUpright(file: File) {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val (degrees, mirror) = CameraSheetMath.orientationFix(orientation)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val sample = CameraSheetMath.sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE)
        if (degrees == 0 && !mirror && sample == 1) return
        val src = runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: return
        val m = Matrix().apply {
            if (mirror) postScale(-1f, 1f)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        val out = try { Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true) } catch (_: OutOfMemoryError) { src.recycle(); return }
        runCatching { FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        if (out !== src) out.recycle()
        src.recycle()
    }
}
