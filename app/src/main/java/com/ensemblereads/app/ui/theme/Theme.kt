package com.ensemblereads.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val iOSBlue = Color(0xFF007AFF)
val iOSSystemGray = Color(0xFF8E8E93)

/** 苹果风格主题：iOS 观感 —— 系统蓝、深浅色、大标题字号、圆角。 */
@Composable
fun EnsembleTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme(
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
