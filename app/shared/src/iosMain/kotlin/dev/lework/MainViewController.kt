package dev.lework

import androidx.compose.ui.window.ComposeUIViewController
import dev.lework.ui.ConsoleApp
import platform.UIKit.UIColor
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    ConsoleApp()
}.also { vc ->
    // Match Everforest.Bg0 (#2D353B) on the underlying UIKit container so Skia Metal
    // initialization never flashes a white frame on cold start.
    vc.view.backgroundColor = UIColor(
        red = 0x2D / 255.0,
        green = 0x35 / 255.0,
        blue = 0x3B / 255.0,
        alpha = 1.0,
    )
}
