package org.foxred.kage.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The single definition of brand colors, typography, spacing, shapes, and component dimensions. */
object DesignTokens {
    val none = 0.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val touchTarget = 48.dp
    val avatar = 40.dp
    val marker = 14.dp
    val logo = 160.dp
    val contentMax = 600.dp
    val drawerMax = 360.dp
    val dot = 8.dp
    val bodyMinHeight = 240.dp
    val messageHtmlHeight = 440.dp
    val newColor = Color(0xFF1376DC)
    val flagColor = Color(0xFFD27B12)
    val light =
        lightColorScheme(
            primary = Color(0xFF1376DC),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE5F1FF),
            onPrimaryContainer = Color(0xFF073864),
            secondary = Color(0xFF586579),
            secondaryContainer = Color(0xFFE8ECF3),
            background = Color(0xFFF7F8FA),
            surface = Color.White,
            onSurface = Color(0xFF202632),
            onSurfaceVariant = Color(0xFF626B7B),
            outline = Color(0xFF747D8B),
            surfaceContainer = Color(0xFFF0F3F8),
        )
    val dark =
        darkColorScheme(
            primary = Color(0xFFA5CCFF),
            onPrimary = Color(0xFF00315E),
            primaryContainer = Color(0xFF134B7C),
            onPrimaryContainer = Color(0xFFD7E9FF),
            background = Color(0xFF10141B),
            surface = Color(0xFF151A22),
            onSurface = Color(0xFFE5E9F1),
            onSurfaceVariant = Color(0xFFBCC5D4),
            surfaceContainer = Color(0xFF222A36),
        )
    private val font = FontFamily.SansSerif
    val typography =
        Typography(
            headlineLarge =
                TextStyle(
                    fontFamily = font,
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Medium,
                ),
            headlineMedium = TextStyle(fontFamily = font, fontSize = 28.sp, lineHeight = 36.sp),
            titleLarge = TextStyle(fontFamily = font, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium =
                TextStyle(
                    fontFamily = font,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                ),
            bodyLarge = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge =
                TextStyle(
                    fontFamily = font,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape(xs),
            small = RoundedCornerShape(sm),
            medium = RoundedCornerShape(md),
            large = RoundedCornerShape(xl),
            extraLarge = RoundedCornerShape(xxl),
        )

    /** HTML mail uses the same typography and palette as native controls. */
    fun emailCss(colors: ColorScheme, fontScale: Float): String {
        val size = typography.bodyLarge.fontSize.value * fontScale
        val lineHeight = typography.bodyLarge.lineHeight.value / typography.bodyLarge.fontSize.value
        fun Color.css() = "#%06X".format(toArgb() and 0xFFFFFF)
        return "body{font:${size}px/$lineHeight sans-serif;color:${colors.onSurface.css()};background:${colors.surface.css()};overflow-wrap:anywhere}img{max-width:100%}a,h1,h2{color:${colors.primary.css()}}"
    }
}
