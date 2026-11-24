package dev.lework.ui

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Everforest Dark (Custom Low-Glare Terminal Edition) — tuned for warm
 * terminal readability across Compose Multiplatform mobile screens.
 */
internal object Everforest {
    // Surfaces & Borders
    val BgDim = Color(0xFF232A2E)       // Deep terminal well / code block canvas
    val Bg0 = Color(0xFF2D353B)         // Terminal Background (#2d353b)
    val Bg1 = Color(0xFF343F44)         // Tab / Auxiliary Bar (#343f44)
    val Bg2 = Color(0xFF3D484D)         // Selection Background (#3d484d)
    val Bg3 = Color(0xFF475258)         // Ansi 0 / Pane Border (#475258)
    val Bg4 = Color(0xFF4F585E)
    val BgGreen = Color(0xFF425047)
    val BgVisual = Color(0xFF543A48)
    val BgYellow = Color(0xFF4D4C43)
    val BgRed = Color(0xFF514045)
    val BgBlue = Color(0xFF3A515D)

    // Low-Contrast Shell-Aligned Reading Hierarchy
    val Fg = Color(0xFFD8CAAC)          // 正文复古暖麦色 (#d8caac)
    val BoldFg = Color(0xFFE5DCC8)      // **加粗正文** 微提亮暖象牙白 (#e5dcc8，与 Shell 加粗一致，不发黄刺眼)
    val HeadingWarm = Color(0xFFE3D5B5) // 一级/二级标题 极克制微暖米白 (#e3d5b5)
    val InlineCode = Color(0xFF9AB0C1)  // `行内代码` 低饱和雾霭灰蓝 (#9ab0c1，对齐 Shell 冷蓝且柔和低对比)
    val SoftSand = Color(0xFFD9C59E)    // 代码关键字 / 运行中进度 低对比暖沙色 (#d9c59e)
    val SoftSage = Color(0xFF9EB89F)    // 代码字符串 / Diff 新增 低饱和灰豆绿 (#9eb89f)
    val SoftRed = Color(0xFFD48A8C)     // Diff 删除 / 温和错误提示 (#d48a8c)

    // UI Chrome Accents (Buttons, Active Badges)
    val BoldGold = Color(0xFFE6C384)
    val CodeGreen = Color(0xFF83C995)
    val CommentGrey = Color(0xFF859289) // 思考过程 / 注释 / 次要信息 苔藓灰绿 (#859289)
    val FaintGrey = Color(0xBF859289)   // Faint Text Alpha 0.75 (#859289 @ 75%)

    // Neutral Greys
    val Grey0 = Color(0xFF7A8478)
    val Grey1 = Color(0xFF859289)
    val Grey2 = Color(0xFF9DA9A0)

    // Native Everforest ANSI 1~6 & Badge Accents
    val Red = Color(0xFFE67E80)         // Ansi 1 / 9 (#e67e80)
    val Green = Color(0xFFA7C080)       // Ansi 2 / 10 (#a7c080)
    val Yellow = Color(0xFFDBBC7F)      // Ansi 3 / 11 (#dbbc7f)
    val Blue = Color(0xFF7FBBB3)        // Ansi 4 / Link Color (#7fbbb3)
    val Purple = Color(0xFFD699B6)      // Ansi 5 / 13 (#d699b6)
    val Aqua = Color(0xFF83C092)        // Ansi 6 / 14 (#83c092)
    val Orange = Color(0xFFE69875)      // Badge / Command Separator (#e69875)
}

internal val EverforestColorScheme = darkColorScheme(
    primary = Everforest.Green,
    onPrimary = Everforest.Bg0,
    secondary = Everforest.CodeGreen,
    onSecondary = Everforest.Bg0,
    tertiary = Everforest.BoldGold,
    background = Everforest.Bg0,
    onBackground = Everforest.Fg,
    surface = Everforest.Bg1,
    onSurface = Everforest.Fg,
    surfaceVariant = Everforest.Bg2,
    onSurfaceVariant = Everforest.Grey2,
    outline = Everforest.Bg3,
    error = Everforest.Red,
)

internal val EverforestTextSelectionColors = TextSelectionColors(
    handleColor = Everforest.BoldGold,
    backgroundColor = Everforest.Blue.copy(alpha = 0.34f),
)
