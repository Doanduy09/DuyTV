package com.duytv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBg = Color(0xFF0A0E17)
val CardBg = Color(0xFF161B26)
val CardFocusBg = Color(0xFF222938)
val AccentRed = Color(0xFFE50914)
val AccentGlow = Color(0xFFFF334B)
val TextWhite = Color(0xFFFFFFFF)
val TextDim = Color(0xFFA0AAB8)

private val DarkColorScheme = darkColorScheme(
    primary = AccentRed,
    secondary = AccentGlow,
    background = DarkBg,
    surface = CardBg,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextWhite,
    onSurface = TextWhite
)

@Composable
fun DuyTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
