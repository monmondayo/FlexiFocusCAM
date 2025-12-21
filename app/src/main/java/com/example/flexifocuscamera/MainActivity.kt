package com.example.flexifocuscamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.core.FocusMeteringAction
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectTapGestures
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Locale
import android.os.Environment

class MainActivity : ComponentActivity() {

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 権限がなければ要求
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            CameraPreviewScreen()
        }
    }
}

private fun lockFocusAt(
    previewView: PreviewView,
    camera: Camera,
    xPx: Float,
    yPx: Float
) {
    val point = previewView.meteringPointFactory.createPoint(xPx, yPx)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    )
        .disableAutoCancel() // ← 固定
        .build()

    camera.cameraControl.startFocusAndMetering(action)
}
@Composable
fun CameraPreviewScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // ★ 追加：静止画撮影ユースケース
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // プレビュー上の枠位置（px）
    var focusX by remember { mutableStateOf(0f) }
    var focusY by remember { mutableStateOf(0f) }

    // パッドの操作感（大きいほど移動が速い）
    val moveScale = 1.2f

    Box(Modifier.fillMaxSize()) {

        // Preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    previewView = pv
                    pv.implementationMode = PreviewView.ImplementationMode.PERFORMANCE

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(pv.surfaceProvider)
                        }

                        // ★ 追加：ImageCapture
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )

                        imageCapture = capture

                        // 中央に枠を置いて、その位置でAF/AE固定
                        pv.post {
                            focusX = pv.width / 2f
                            focusY = pv.height / 2f
                            val cam = camera
                            if (cam != null) lockFocusAt(pv, cam, focusX, focusY)
                        }

                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )

        // 枠表示（小さめ）
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (focusX > 0 && focusY > 0) {
                val size = 96.dp.toPx()
                drawRect(
                    color = Color.Yellow,
                    topLeft = Offset(focusX - size / 2, focusY - size / 2),
                    size = Size(size, size),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // 親指ドラッグパッド（画面下）
        val padSize: Dp = 140.dp
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp) // シャッターと被らないように少し上へ
                .size(padSize)
                .background(Color(0x66000000), RoundedCornerShape(18.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()

                            val pv = previewView ?: return@detectDragGestures
                            val newX = focusX + dragAmount.x * moveScale
                            val newY = focusY + dragAmount.y * moveScale

                            focusX = newX.coerceIn(0f, pv.width.toFloat())
                            focusY = newY.coerceIn(0f, pv.height.toFloat())
                        },
                        onDragEnd = {
                            val pv = previewView
                            val cam = camera
                            if (pv != null && cam != null && focusX > 0 && focusY > 0) {
                                lockFocusAt(pv, cam, focusX, focusY)
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(10.dp)
                    .background(Color(0xCCFFFFFF), RoundedCornerShape(50))
            )
        }

        // 中央に戻すボタン（便利）
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 36.dp)
                .background(Color(0x66000000), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .pointerInput(Unit) {
                    detectTapGestures {
                        val pv = previewView ?: return@detectTapGestures
                        val cam = camera ?: return@detectTapGestures
                        focusX = pv.width / 2f
                        focusY = pv.height / 2f
                        lockFocusAt(pv, cam, focusX, focusY)
                    }
                }
        ) {
            Text("中央", color = Color.White)
        }

        // ★ 追加：シャッターボタン（右下）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 28.dp)
                .size(78.dp)
                .clip(CircleShape)
                .background(Color(0xCCFFFFFF))
                .clickable {
                    val cap = imageCapture
                    if (cap == null) {
                        Toast.makeText(context, "カメラ準備中です", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }

                    // ファイル名（日時）
                    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN)
                        .format(System.currentTimeMillis())

                    // MediaStoreに保存（Pictures/FlexiFocusCAM）
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "FlexiFocus_$name")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                        }
                    }

                    val outputOptions = ImageCapture.OutputFileOptions.Builder(
                        context.contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ).build()

                    cap.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                            }

                            override fun onError(exception: ImageCaptureException) {
                                Toast.makeText(
                                    context,
                                    "保存失敗: ${exception.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
        ) {
            // 内側リング（シャッターっぽく）
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFFFFF))
            )
        }
    }
}
