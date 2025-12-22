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
import androidx.camera.core.FocusMeteringResult
import com.google.common.util.concurrent.ListenableFuture
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.AspectRatio
import android.view.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.Locale
import android.os.Environment
import androidx.camera.core.ZoomState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    yPx: Float,
    onFocusResult: ((Boolean) -> Unit)? = null
) {
    val point = previewView.meteringPointFactory.createPoint(xPx, yPx)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    )
        .disableAutoCancel() // ← 固定
        .build()

    val future: ListenableFuture<FocusMeteringResult> = camera.cameraControl.startFocusAndMetering(action)
    
    // フォーカス結果を監視
    future.addListener({
        try {
            val result = future.get()
            val isFocused = result.isFocusSuccessful
            onFocusResult?.invoke(isFocused)
        } catch (e: Exception) {
            onFocusResult?.invoke(false)
        }
    }, ContextCompat.getMainExecutor(previewView.context))
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

    // AF枠のサイズ（dp）
    var focusSize by remember { mutableStateOf(96.dp) }

    // フォーカス状態（ピントが合っているか）
    var isFocused by remember { mutableStateOf(false) }

    // ズーム倍率（1.0 = 等倍）
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var cameraZoomState by remember { mutableStateOf<ZoomState?>(null) }

    // パッドの操作感（大きいほど移動が速い）
    val moveScale = 1.2f

    Box(Modifier.fillMaxSize()) {

        // Preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black) // 余白を黒で塗りつぶし
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        previewView = pv
                        pv.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        pv.scaleType = PreviewView.ScaleType.FIT_CENTER // 画像全体を表示（余白は黒帯）

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            // PreviewViewのサイズを取得してアスペクト比を計算
                            pv.post {
                                val previewWidth = pv.width
                                val previewHeight = pv.height
                                
                                if (previewWidth > 0 && previewHeight > 0) {
                                    
                                    // プレビューとImageCaptureのアスペクト比を一致させる
                                    val preview = Preview.Builder()
                                        .build().also {
                                            it.surfaceProvider = pv.surfaceProvider
                                        }

                                    // ImageCaptureも同じアスペクト比に設定
                                    val capture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                        .build()

                                    cameraProvider.unbindAll()
                                    
                                    // デフォルトのバックカメラを使用
                                    val cam = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        capture
                                    )
                                    camera = cam

                                    // ズーム状態を監視
                                    cam.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                                        cameraZoomState = state
                                        zoomRatio = state.zoomRatio
                                    }

                                    imageCapture = capture

                                    // 中央に枠を置いて、その位置でAF/AE固定
                                    focusX = pv.width / 2f
                                    focusY = pv.height / 2f
                                    lockFocusAt(pv, cam, focusX, focusY) { focused ->
                                        isFocused = focused
                                    }
                                }
                            }

                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
        }

        // 枠表示（可変サイズ、フォーカス状態に応じて色を変更）
        val density = LocalDensity.current
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (focusX > 0 && focusY > 0) {
                val size = with(density) { focusSize.toPx() }
                val frameColor = if (isFocused) Color(0xFF4CAF50) else Color.Yellow // 緑色（ピント合い）または黄色
                drawRect(
                    color = frameColor,
                    topLeft = Offset(focusX - size / 2, focusY - size / 2),
                    size = Size(size, size),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }

        // 親指ドラッグパッドとサイズスライダー（画面下）
        val padSize: Dp = 140.dp
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp), // シャッターと被らないように少し上へ
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val actualMinZoom = cameraZoomState?.minZoomRatio ?: 0.5f
            val actualMaxZoom = cameraZoomState?.maxZoomRatio ?: 4.0f
            
            var isZoomSliderVisible by remember { mutableStateOf(false) }
            var zoomSliderBaseRatio by remember { mutableStateOf(1.0f) }
            var isZoomDragging by remember { mutableStateOf(false) }
            var cumulativeDragOffset by remember { mutableStateOf(0f) }
            val zoomCoroutineScope = rememberCoroutineScope()
            
            // 左側：ズームスライダーとボタン
            Box {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 長押し時に表示されるズームスライダー
                    if (isZoomSliderVisible) {
                        val sliderHeight = 180.dp
                        val sliderWidth = 60.dp
                        val density = LocalDensity.current
                        val sliderHeightPx = with(density) { sliderHeight.toPx() }
                        
                        LaunchedEffect(zoomSliderBaseRatio) {
                            cumulativeDragOffset = 0f
                        }
                        
                        Box(
                            modifier = Modifier
                                .width(sliderWidth)
                                .height(sliderHeight)
                                .background(Color(0xCC000000), RoundedCornerShape(30.dp))
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .pointerInput(zoomSliderBaseRatio, cumulativeDragOffset, actualMinZoom, actualMaxZoom) {
                                    var localDragOffset = cumulativeDragOffset
                                    
                                    detectDragGestures(
                                        onDragStart = {
                                            isZoomDragging = true
                                            localDragOffset = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            localDragOffset += dragAmount.y
                                            val normalizedDrag = localDragOffset / sliderHeightPx
                                            
                                            val zoomRange = actualMaxZoom - actualMinZoom
                                            val delta = -normalizedDrag * zoomRange * 1.5f
                                            
                                            val newRatio = (zoomSliderBaseRatio + delta).coerceIn(actualMinZoom, actualMaxZoom)
                                            camera?.cameraControl?.setZoomRatio(newRatio)
                                        },
                                        onDragEnd = {
                                            isZoomDragging = false
                                            localDragOffset = 0f
                                            zoomCoroutineScope.launch {
                                                delay(500)
                                                isZoomSliderVisible = false
                                            }
                                        }
                                    )
                                }
                        ) {
                            val currentRatioText = "%.2fx".format(zoomRatio)
                            Text(
                                text = currentRatioText,
                                color = Color.White,
                                fontSize = 16.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width(40.dp)
                                    .height(2.dp)
                                    .background(Color(0x88FFFFFF), RoundedCornerShape(1.dp))
                            )
                        }
                    }
                    
                    // ズーム倍率ボタン（縦並び）
                    val zoomButtons = listOf(0.5f, 1.0f, 2.0f)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        zoomButtons.forEach { buttonRatio ->
                            val targetZoomRatio = when (buttonRatio) {
                                0.5f -> actualMinZoom
                                1.0f -> 1.0f
                                else -> buttonRatio.coerceIn(actualMinZoom, actualMaxZoom)
                            }

                            val isSelected = abs(zoomRatio - targetZoomRatio) < 0.1f

                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) Color(0xCCFFFFFF) else Color(0x66000000),
                                        CircleShape
                                    )
                                    .pointerInput(camera, buttonRatio, actualMinZoom, actualMaxZoom) {
                                        detectTapGestures(
                                            onTap = {
                                                camera?.let { cam ->
                                                    val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: actualMinZoom
                                                    val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: actualMaxZoom
                                                    val newTargetZoom = when (buttonRatio) {
                                                        0.5f -> minZoom
                                                        1.0f -> 1.0f
                                                        else -> buttonRatio.coerceIn(minZoom, maxZoom)
                                                    }
                                                    cam.cameraControl.setZoomRatio(newTargetZoom)
                                                }
                                            },
                                            onLongPress = {
                                                camera?.let { cam ->
                                                    val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: actualMinZoom
                                                    val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: actualMaxZoom
                                                    val baseSliderRatio = when (buttonRatio) {
                                                        0.5f -> minZoom
                                                        1.0f -> 1.0f
                                                        else -> buttonRatio.coerceIn(minZoom, maxZoom)
                                                    }
                                                    zoomSliderBaseRatio = baseSliderRatio
                                                    isZoomSliderVisible = true
                                                }
                                            }
                                        )
                                    }
                            ) {
                                Text(
                                    text = "${buttonRatio}x",
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }
            }
            // 中央：親指ドラッグパッド
            Box(
                modifier = Modifier
                    .size(padSize)
                    .background(Color(0x66000000), RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                val pv = previewView ?: return@detectTapGestures
                                val cam = camera ?: return@detectTapGestures
                                focusX = pv.width / 2f
                                focusY = pv.height / 2f
                                isFocused = false // フォーカス状態をリセット
                                lockFocusAt(pv, cam, focusX, focusY) { focused ->
                                    isFocused = focused
                                }
                            },
                            onLongPress = {
                                // 長押しでオートフォーカスを作動
                                val pv = previewView ?: return@detectTapGestures
                                val cam = camera ?: return@detectTapGestures
                                if (focusX > 0 && focusY > 0) {
                                    isFocused = false // フォーカス状態をリセット
                                    lockFocusAt(pv, cam, focusX, focusY) { focused ->
                                        isFocused = focused
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()

                                val pv = previewView ?: return@detectDragGestures
                                val newX = focusX + dragAmount.x * moveScale
                                val newY = focusY + dragAmount.y * moveScale

                                focusX = newX.coerceIn(0f, pv.width.toFloat())
                                focusY = newY.coerceIn(0f, pv.height.toFloat())
                                isFocused = false // ドラッグ中はフォーカス状態をリセット
                            },
                            onDragEnd = {
                                val pv = previewView
                                val cam = camera
                                if (pv != null && cam != null && focusX > 0 && focusY > 0) {
                                    isFocused = false // フォーカス状態をリセット
                                    lockFocusAt(pv, cam, focusX, focusY) { focused ->
                                        isFocused = focused
                                    }
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

            // AF枠サイズスライダー（縦方向）
            val sliderHeight = padSize
            val minSize = 24.dp
            val maxSize = 200.dp
            val density = LocalDensity.current
            
            val normalized = (focusSize.value - minSize.value) / (maxSize.value - minSize.value)
            val maxOffsetPx = with(density) { (sliderHeight - 20.dp).toPx() }
            val sliderOffset = maxOffsetPx * (1f - normalized) - maxOffsetPx / 2f
            
            var isDragging by remember { mutableStateOf(false) }
            var dragOffset by remember { mutableStateOf(0f) }
            
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(sliderHeight)
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                
                                val totalOffset = sliderOffset + dragOffset
                                val clampedOffset = totalOffset.coerceIn(-maxOffsetPx, maxOffsetPx)
                                
                                val newNormalized = (clampedOffset + maxOffsetPx) / (maxOffsetPx * 2f)
                                focusSize = minSize + (maxSize - minSize) * (1f - newNormalized)
                            },
                            onDragEnd = {
                                isDragging = false
                                dragOffset = 0f
                            }
                        )
                    }
            ) {
                // スライダーのつまみ
                val currentOffset = if (isDragging) sliderOffset + dragOffset else sliderOffset
                val thumbPosition = with(density) { 
                    (sliderHeight.toPx() / 2f + currentOffset).coerceIn(
                        10.dp.toPx(),
                        sliderHeight.toPx() - 10.dp.toPx()
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = with(density) { (thumbPosition - 10.dp.toPx()).toDp() })
                        .width(20.dp)
                        .height(20.dp)
                        .background(Color(0xCCFFFFFF), RoundedCornerShape(10.dp))
                )
            }
        }


        // ★ 追加：シャッターボタン（右下） — 押下時に赤く表示
        val shutterInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val shutterPressed by shutterInteraction.collectIsPressedAsState()

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 44.dp, bottom = 56.dp)
                .size(78.dp)
                .clip(CircleShape)
                .background(if (shutterPressed) Color.Red else Color(0xCCFFFFFF))
                .clickable(
                    interactionSource = shutterInteraction,
                    indication = null
                ) {
                    val cap = imageCapture
                    if (cap == null) {
                        Toast.makeText(context, "カメラ準備中です", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    
                    // ★ 追加: 撮影時の回転情報を取得して設定
                    previewView?.display?.let { display ->
                        cap.targetRotation = display.rotation
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
                    .background(if (shutterPressed) Color(0xFFFFCDD2) else Color(0xFFFFFFFF))
            )
        }
    }
}
