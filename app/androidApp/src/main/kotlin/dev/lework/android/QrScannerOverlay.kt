package dev.lework.android

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun QrScannerOverlay(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val finished = remember { AtomicBoolean(false) }
    var statusText by remember { mutableStateOf("对准电脑终端 lework pair 生成的二维码") }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF232A2E)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener(
                    {
                        runCatching {
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val reader = MultiFormatReader().apply {
                                setHints(
                                    mapOf(
                                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                        DecodeHintType.TRY_HARDER to true,
                                    ),
                                )
                            }
                            @Suppress("DEPRECATION")
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                decodeQrFromImageProxy(imageProxy, reader)?.let { text ->
                                    if (finished.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            runCatching { provider.unbindAll() }
                                            onScanned(text)
                                        }
                                    }
                                }
                            }

                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }.onFailure {
                            statusText = "无法打开摄像头，请在登录页手动粘贴 lework://pair 配对链接"
                        }
                    },
                    ContextCompat.getMainExecutor(ctx),
                )
                previewView
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "扫码配对主机",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFD3C6AA),
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onCancel() },
                ) {
                    Text(
                        text = "取消",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE6C384),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Transparent,
                border = BorderStroke(2.5.dp, Color(0xFF83C995)),
                modifier = Modifier.size(248.dp),
            ) {}

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.58f),
                modifier = Modifier.padding(bottom = 18.dp),
            ) {
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = Color(0xFFD3C6AA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }
        }
    }
}

private fun decodeQrFromImageProxy(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    try {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height
        val yBytes = ByteArray(buffer.remaining())
        buffer.get(yBytes)

        val dataWidth = if (rowStride >= width) rowStride else width
        if (yBytes.size < dataWidth * (height - 1) + width) return null

        val source = PlanarYUVLuminanceSource(
            yBytes,
            dataWidth,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return runCatching { reader.decodeWithState(bitmap).text?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        return null
    } finally {
        reader.reset()
        imageProxy.close()
    }
}
