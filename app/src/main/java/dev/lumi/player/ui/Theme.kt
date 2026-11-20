package dev.lumi.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Màu thương hiệu riêng của Lumi (không dùng màu đỏ của YouTube). */
val LumiBrand = Color(0xFF6C4DFF)

// Nền trung tính, chữ tương phản cao — kiểu bố cục quen thuộc của app xem video
private val LightColors = lightColorScheme(
    primary = LumiBrand,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFF2F2F2),
    onSecondaryContainer = Color(0xFF0F0F0F),
    background = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF606060),
    surfaceContainer = Color.White,
    outlineVariant = Color(0xFFE5E5E5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA996FF),
    onPrimary = Color(0xFF1B0F4D),
    secondaryContainer = Color(0xFF272727),
    onSecondaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceContainer = Color(0xFF0F0F0F),
    outlineVariant = Color(0xFF3F3F3F),
)

@Composable
fun LumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
