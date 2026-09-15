package com.ensemblereads.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val iOSBlue = Color(0xFF007AFF)
val iOSSystemGray = Color(0xFF8E8E93)

/** 应用主题：跟随系统 / 亮 / 暗 / 羊皮纸(暖) / 墨绿(护眼)。 */
enum class AppTheme { SYSTEM, LIGHT, DARK, PARCHMENT, FOREST }

/** 阅读器正文独立配色（与 App 主题解耦，沉浸阅读时背景用 palette.bg）。 */
data class ReaderPalette(
    val bg: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val selection: Color,
    val surface: Color,
)

val LightReaderPalette = ReaderPalette(
    bg = Color(0xFFFFFFFF), text = Color(0xFF1A1A1A), muted = Color(0xFF8E8E93),
    accent = Color(0xFF007AFF), selection = Color(0xFFD6E4FF), surface = Color(0xFFF2F2F7),
)
val DarkReaderPalette = ReaderPalette(
    bg = Color(0xFF000000), text = Color(0xFFE5E5EA), muted = Color(0xFF8E8E93),
    accent = Color(0xFF0A84FF), selection = Color(0xFF1E3A5F), surface = Color(0xFF1C1C1E),
)
val ParchmentReaderPalette = ReaderPalette(
    bg = Color(0xFFF5EFE0), text = Color(0xFF3A3226), muted = Color(0xFF9C917C),
    accent = Color(0xFF8A6D3B), selection = Color(0xFFE8DCC0), surface = Color(0xFFF5EFE0),
)
val ForestReaderPalette = ReaderPalette(
    bg = Color(0xFF1E2A24), text = Color(0xFFD8E0DA), muted = Color(0xFF7A8A80),
    accent = Color(0xFF8BC34A), selection = Color(0xFF2E4438), surface = Color(0xFF24332C),
)

/** 当前阅读器配色（由 EnsembleTheme 按主题提供）。 */
val LocalReaderPalette = staticCompositionLocalOf { LightReaderPalette }

/** 苹果风格主题：iOS 观感 —— 系统蓝、深浅色、大标题字号、圆角；支持多阅读主题。 */
@Composable
fun EnsembleTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.DARK, AppTheme.FOREST -> true
        AppTheme.LIGHT, AppTheme.PARCHMENT -> false
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val readerPalette = when (theme) {
        AppTheme.PARCHMENT -> ParchmentReaderPalette
        AppTheme.FOREST -> ForestReaderPalette
        AppTheme.LIGHT -> LightReaderPalette
        AppTheme.DARK -> DarkReaderPalette
        AppTheme.SYSTEM -> if (dark) DarkReaderPalette else LightReaderPalette
    }
    val scheme = if (dark) darkColorScheme(
        primary = iOSBlue,
        background = Color(0xFF000000),
        surface = Color(0xFF1C1C1E),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFF8E8E93),
    ) else lightColorScheme(
        primary = iOSBlue,
        background = Color(0xFFF2F2F7),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE5E5EA),
        onSurfaceVariant = Color(0xFF8E8E93),
    )
    CompositionLocalProvider(LocalReaderPalette provides readerPalette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp),  // iOS 大标题
                headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp),                 // 阅读正文
                bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
                labelMedium = TextStyle(fontSize = 13.sp),
            ),
            content = content,
        )
    }
}
