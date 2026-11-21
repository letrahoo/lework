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
import dev.lework.platform.AndroidNativeDelegate
import dev.lework.platform.AndroidPlatformRuntime
import dev.lework.platform.PickedImagePayload
import dev.lework.ui.ConsoleApp
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), AndroidNativeDelegate {
    private var showQrScanner by mutableStateOf(false)
    private var qrCallback: ((String?) -> Unit)? = null
    private var imageCallback: ((PickedImagePayload?) -> Unit)? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val cameraPermissionLauncher = registerForActivityResult(
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

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        val cb = imageCallback
        imageCallback = null
        if (uri == null || cb == null) {
            cb?.invoke(null)
            return@registerForActivityResult
        }
        ioExecutor.execute {
            val payload = decodeAndDownscaleImage(uri)
            runOnUiThread { cb.invoke(payload) }
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
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    override fun presentImagePicker(onResult: (PickedImagePayload?) -> Unit) {
        runOnUiThread {
            imageCallback?.invoke(null)
            imageCallback = onResult
            runCatching {
                imagePickerLauncher.launch("image/*")
            }.onFailure {
                imageCallback = null
                onResult(null)
            }
        }
    }

    private fun decodeAndDownscaleImage(uri: Uri): PickedImagePayload? = runCatching {
        val fileName = resolveDisplayName(uri) ?: "photo.jpg"
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
            if (b64.length <= 920_000) break
        }
        decoded.recycle()
        if (b64.isEmpty() || b64.length > 990_000) return null

        PickedImagePayload(
            fileName = if (fileName.contains('.')) fileName else "$fileName.jpg",
            mimeType = "image/jpeg",
            dataBase64 = b64,
        )
    }.getOrNull()

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
