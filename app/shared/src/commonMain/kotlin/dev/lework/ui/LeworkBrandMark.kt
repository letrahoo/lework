package dev.lework.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Unified Lework architectural brand mark (mitered brushed-gold `L` cradling a
 * faceted luminous forest-green forward command chevron on deep Everforest obsidian).
 * Shared across macOS Desktop window icon, Desktop auth view, and Mobile auth view.
 */
@Composable
fun LeworkBrandMark(
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        drawLeworkBrandBadge()
    }
}

@Composable
fun rememberLeworkBrandPainter(): Painter = remember {
    object : Painter() {
        override val intrinsicSize: Size = Size(256f, 256f)

        override fun DrawScope.onDraw() {
            drawLeworkBrandBadge()
        }
    }
}

private fun DrawScope.drawLeworkBrandBadge() {
    val w = size.width
    val h = size.height
    val s = minOf(w, h)
    val ox = (w - s) * 0.5f
    val oy = (h - s) * 0.5f
    val corner = CornerRadius(s * 0.225f, s * 0.225f)

    // Deep Everforest obsidian tile
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF252E34), Color(0xFF1C2327), Color(0xFF13191C)),
            start = Offset(ox + s * 0.15f, oy),
            end = Offset(ox + s * 0.90f, oy + s),
        ),
        topLeft = Offset(ox, oy),
        size = Size(s, s),
        cornerRadius = corner,
    )

    // Subtle emerald ambient halo behind chevron
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x3883C995), Color(0x0083C995)),
            center = Offset(ox + s * 0.60f, oy + s * 0.42f),
            radius = s * 0.36f,
        ),
        radius = s * 0.36f,
        center = Offset(ox + s * 0.60f, oy + s * 0.42f),
    )

    // Delicate inner hairline specular rim
    drawRoundRect(
        color = Color(0x26E8DFCC),
        topLeft = Offset(ox + s * 0.01f, oy + s * 0.01f),
        size = Size(s * 0.98f, s * 0.98f),
        cornerRadius = CornerRadius(s * 0.218f, s * 0.218f),
        style = Stroke(width = (s * 0.016f).coerceAtLeast(1f)),
    )

    fun pt(nx: Float, ny: Float) = Offset(ox + nx * s, oy + ny * s)

    // 1. Vertical mitered brushed-gold bar
    val vBar = Path().apply {
        moveTo(pt(0.205f, 0.334f).x, pt(0.205f, 0.334f).y)
        lineTo(pt(0.350f, 0.197f).x, pt(0.350f, 0.197f).y)
        lineTo(pt(0.350f, 0.633f).x, pt(0.350f, 0.633f).y)
        lineTo(pt(0.205f, 0.772f).x, pt(0.205f, 0.772f).y)
        close()
    }
    drawPath(
        path = vBar,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFF8E3B5), Color(0xFFE8C688), Color(0xFFCFA45D)),
            start = pt(0.205f, 0.197f),
            end = pt(0.370f, 0.772f),
        ),
    )
    drawPath(
        path = vBar,
        color = Color(0x55FFF3D4),
        style = Stroke(width = (s * 0.012f).coerceAtLeast(0.8f), join = StrokeJoin.Round),
    )

    // 2. Horizontal mitered brushed-gold bar
    val hBar = Path().apply {
        moveTo(pt(0.363f, 0.646f).x, pt(0.363f, 0.646f).y)
        lineTo(pt(0.799f, 0.646f).x, pt(0.799f, 0.646f).y)
        lineTo(pt(0.664f, 0.791f).x, pt(0.664f, 0.791f).y)
        lineTo(pt(0.219f, 0.791f).x, pt(0.219f, 0.791f).y)
        close()
    }
    drawPath(
        path = hBar,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFF6DFB0), Color(0xFFE4BE7C), Color(0xFFC69850)),
            start = pt(0.219f, 0.646f),
            end = pt(0.799f, 0.791f),
        ),
    )
    drawPath(
        path = hBar,
        color = Color(0x4CFFF2D0),
        style = Stroke(width = (s * 0.012f).coerceAtLeast(0.8f), join = StrokeJoin.Round),
    )

    // 3. Sculptural faceted forest-green forward command chevron `>`
    val cTop = Path().apply {
        moveTo(pt(0.480f, 0.258f).x, pt(0.480f, 0.258f).y)
        lineTo(pt(0.582f, 0.258f).x, pt(0.582f, 0.258f).y)
        lineTo(pt(0.736f, 0.410f).x, pt(0.736f, 0.410f).y)
        lineTo(pt(0.634f, 0.410f).x, pt(0.634f, 0.410f).y)
        close()
    }
    val cBot = Path().apply {
        moveTo(pt(0.634f, 0.410f).x, pt(0.634f, 0.410f).y)
        lineTo(pt(0.736f, 0.410f).x, pt(0.736f, 0.410f).y)
        lineTo(pt(0.582f, 0.562f).x, pt(0.582f, 0.562f).y)
        lineTo(pt(0.480f, 0.562f).x, pt(0.480f, 0.562f).y)
        close()
    }
    val cOutline = Path().apply {
        moveTo(pt(0.480f, 0.258f).x, pt(0.480f, 0.258f).y)
        lineTo(pt(0.582f, 0.258f).x, pt(0.582f, 0.258f).y)
        lineTo(pt(0.736f, 0.410f).x, pt(0.736f, 0.410f).y)
        lineTo(pt(0.582f, 0.562f).x, pt(0.582f, 0.562f).y)
        lineTo(pt(0.480f, 0.562f).x, pt(0.480f, 0.562f).y)
        lineTo(pt(0.634f, 0.410f).x, pt(0.634f, 0.410f).y)
        close()
    }
    drawPath(
        path = cTop,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFB9F0C6), Color(0xFF8BD29D), Color(0xFF6EB883)),
            start = pt(0.480f, 0.258f),
            end = pt(0.736f, 0.410f),
        ),
    )
    drawPath(
        path = cBot,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF74BD88), Color(0xFF5CA472), Color(0xFF47895C)),
            start = pt(0.736f, 0.410f),
            end = pt(0.480f, 0.562f),
        ),
    )
    drawPath(
        path = cOutline,
        color = Color(0x66D7FBE0),
        style = Stroke(width = (s * 0.011f).coerceAtLeast(0.8f), join = StrokeJoin.Round),
    )
}
