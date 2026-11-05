package com.paretino.ui.master

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** Full-screen camera preview with a shutter; reports the captured file. */
@Composable
fun CameraCapture(onCaptured: (Uri) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    var busy by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }.onFailure { onError("Camera unavailable: ${it.message}") }
                }, ContextCompat.getMainExecutor(context))
            },
        )
        Text(
            "Fit the whole wall in the frame",
            Modifier.align(Alignment.TopCenter).padding(16.dp),
            color = Color.White, style = MaterialTheme.typography.titleMedium,
        )
        FilledIconButton(
            onClick = {
                if (busy) return@FilledIconButton
                busy = true
                val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { busy = false; onCaptured(Uri.fromFile(file)) }
                        override fun onError(e: ImageCaptureException) { busy = false; onError("Capture failed: ${e.message}") }
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).size(76.dp)
                .border(4.dp, Color.White, CircleShape),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (busy) Color.Gray else Color.White.copy(alpha = 0.85f)),
        ) {}
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }
}
