package dev.lework.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.lework.platform.AndroidPlatformRuntime
import dev.lework.ui.ConsoleApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidPlatformRuntime.attach(applicationContext)
        AndroidPlatformRuntime.onIncomingUri(intent?.dataString)
        setContent {
            ConsoleApp()
        }
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
}
