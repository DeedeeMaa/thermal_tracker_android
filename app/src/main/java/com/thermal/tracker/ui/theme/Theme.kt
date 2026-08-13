package com.thermal.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 西门子 TIA Portal 风格：深灰背景 + 绿色高亮 + 白色字体（参照 PLC 调试界面配色）
private val SiemensColors = darkColorScheme(
    primary = Color(0xFF00A651),      // 西门子绿（按钮/高亮/指示）
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4CC97B),    // 浅绿点缀
    onSecondary = Color(0xFF00331A),
    background = Color(0xFF1E1E1E),   // TIA 深灰背景
    onBackground = Color(0xFFFFFFFF), // 白色字体
    surface = Color(0xFF2D2D30),      // 面板深灰
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF3A3A3E),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF5A5A5E),
    error = Color(0xFFFF4444),
)

@Composable
fun ThermalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SiemensColors,
        content = content,
    )
}
