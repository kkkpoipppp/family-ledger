package com.familyledger.app.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF28643B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EFE1),
    onPrimaryContainer = Color(0xFF153B21),
    secondary = Color(0xFFA45118),
    secondaryContainer = Color(0xFFFFEAD9),
    background = Color(0xFFF5F3ED),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFE1E4DC),
    onBackground = Color(0xFF1F271E),
    onSurface = Color(0xFF1F271E),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD39B),
    onPrimary = Color(0xFF073917),
    primaryContainer = Color(0xFF1D4F2D),
    onPrimaryContainer = Color(0xFFA6F0B4),
    secondary = Color(0xFFFFB77E),
    secondaryContainer = Color(0xFF6A370B),
    background = Color(0xFF171915),
    surface = Color(0xFF20231E),
    onBackground = Color(0xFFE5E8E0),
    onSurface = Color(0xFFE5E8E0),
)

private val LedgerTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun FamilyLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LedgerTypography,
        content = content,
    )
}
