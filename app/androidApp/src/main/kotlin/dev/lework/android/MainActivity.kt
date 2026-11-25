package dev.lework.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.lework.platform.AndroidNativeDelegate
import dev.lework.platform.AndroidPlatformRuntime
import dev.lework.platform.ImagePickSource
import dev.lework.platform.PickedImagePayload
import dev.lework.ui.ConsoleApp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), AndroidNativeDelegate {
    private var showQrScanner by mutableStateOf(false)
    private var qrCallback: ((String?) -> Unit)? = null
    private var multiImageCallback: ((List<PickedImagePayload>) -> Unit)? = null
    private var pendingMaxImages: Int = 4
    private var pendingCameraFile: File? = null
    private var pendingCameraUri: Uri? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val qrCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            val cb = qrCallback
            qrCallback = null
            cb?.invoke(null)
        }
    }

    private val photoCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchSystemCameraCapture()
        } else {
            val cb = multiImageCallback
            multiImageCallback = null
            cb?.invoke(emptyList())
        }
    }

    private val multiImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        val cb = multiImageCallback
        multiImageCallback = null
        val limit = pendingMaxImages.coerceIn(1, 4)
        if (uris.isEmpty() || cb == null) {
            cb?.invoke(emptyList())
            return@registerForActivityResult
        }
        ioExecutor.execute {
            val payloads = uris.take(limit).mapNotNull { decodeAndDownscaleImage(it) }
            runOnUiThread { cb.invoke(payloads) }
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success: Boolean ->
        val cb = multiImageCallback
        multiImageCallback = null
        val uri = pendingCameraUri
        val file = pendingCameraFile
        pendingCameraUri = null
        pendingCameraFile = null
        if (!success || uri == null || cb == null) {
            runCatching { file?.delete() }
            cb?.invoke(emptyList())
            return@registerForActivityResult
        }
        ioExecutor.execute {
            val payload = decodeAndDownscaleImage(uri, defaultName = file?.name ?: "camera.jpg")
            runCatching { file?.delete() }
            runOnUiThread { cb.invoke(listOfNotNull(payload)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        AndroidPlatformRuntime.attach(applicationContext)
        AndroidPlatformRuntime.registerDelegate(this)
        AndroidPlatformRuntime.onIncomingUri(intent?.dataString)

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                ConsoleApp()
                if (showQrScanner) {
                    QrScannerOverlay(
                        onScanned = { scanned ->
                            showQrScanner = false
                            val cb = qrCallback
                            qrCallback = null
                            cb?.invoke(scanned)
                        },
                        onCancel = {
                            showQrScanner = false
                            val cb = qrCallback
                            qrCallback = null
                            cb?.invoke(null)
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        AndroidPlatformRuntime.registerDelegate(null)
        ioExecutor.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AndroidPlatformRuntime.onIncomingUri(intent.dataString)
    }

    override fun onResume() {
        super.onResume()
        AndroidPlatformRuntime.onForegroundChanged(true)
    }

    override fun onPause() {
        AndroidPlatformRuntime.onForegroundChanged(false)
        super.onPause()
    }

    override fun presentQrScanner(onResult: (String?) -> Unit) {
        runOnUiThread {
            qrCallback?.invoke(null)
            qrCallback = onResult
            val hasPerm = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                showQrScanner = true
            } else {
                qrCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun presentImagePicker(onResult: (PickedImagePayload?) -> Unit) {
        presentMultiImagePicker(ImagePickSource.LIBRARY, 1) { list ->
            onResult(list.firstOrNull())
        }
    }

    override fun presentMultiImagePicker(
        source: ImagePickSource,
        maxCount: Int,
        onResult: (List<PickedImagePayload>) -> Unit,
    ) {
        runOnUiThread {
            multiImageCallback?.invoke(emptyList())
            multiImageCallback = onResult
            pendingMaxImages = maxCount.coerceIn(1, 4)
            if (source == ImagePickSource.CAMERA) {
                val hasPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    launchSystemCameraCapture()
                } else {
                    photoCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            } else {
                runCatching {
                    multiImagePickerLauncher.launch("image/*")
                }.onFailure {
                    multiImageCallback = null
                    onResult(emptyList())
                }
            }
        }
    }

    private fun launchSystemCameraCapture() {
        runCatching {
            val dir = File(cacheDir, "camera_captures").apply { mkdirs() }
            val photoFile = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                photoFile,
            )
            pendingCameraFile = photoFile
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }.onFailure {
            val cb = multiImageCallback
            multiImageCallback = null
            cb?.invoke(emptyList())
        }
    }

    private fun decodeAndDownscaleImage(uri: Uri, defaultName: String = "photo.jpg"): PickedImagePayload? = runCatching {
        val fileName = resolveDisplayName(uri) ?: defaultName
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val targetMaxDim = 1280
        var sampleSize = 1
        while (boundsOpts.outWidth / (sampleSize * 2) >= targetMaxDim ||
            boundsOpts.outHeight / (sampleSize * 2) >= targetMaxDim
        ) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        try {
            fun encodeWithinLimit(src: Bitmap, maxDim: Int, quality: Int): String {
                val longest = maxOf(src.width, src.height)
                val working = if (longest > maxDim && longest > 0) {
                    val ratio = maxDim.toFloat() / longest.toFloat()
                    val w = (src.width * ratio).toInt().coerceAtLeast(1)
                    val h = (src.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, w, h, true)
                } else {
                    src
                }
                val out = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (working !== src) working.recycle()
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }

            // Guarantee Base64 payload fits comfortably inside protocol PromptImageAttachment 1_000_000 cap
            var b64 = ""
            for ((dim, q) in listOf(1280 to 78, 1280 to 60, 1024 to 52, 800 to 45)) {
                b64 = encodeWithinLimit(decoded, dim, q)
                if (b64.length <= 900_000) break
            }
            val thumbB64 = encodeWithinLimit(decoded, 520, 68).takeIf { it.length <= 240_000 }
            if (b64.isEmpty() || b64.length > 990_000) return null

            PickedImagePayload(
                fileName = if (fileName.contains('.')) fileName else "$fileName.jpg",
                mimeType = "image/jpeg",
                dataBase64 = b64,
                thumbnailBase64 = thumbB64,
            )
        } finally {
            decoded.recycle()
        }
    }.getOrNull()

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
