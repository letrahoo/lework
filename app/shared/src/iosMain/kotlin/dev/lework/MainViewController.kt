package dev.lework

import androidx.compose.ui.window.ComposeUIViewController
import dev.lework.ui.ConsoleApp
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    ConsoleApp()
}
