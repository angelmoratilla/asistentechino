package com.babelong.asistentechino.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RedPrimary = Color(0xFFE57373)
val RedPrimaryContainer = Color(0xFF5C1A16)
val AmberPinyin = Color(0xFFFFD54F)
val InkBackground = Color(0xFF101014)
val InkSurface = Color(0xFF18181D)
val InkSurfaceVariant = Color(0xFF23232A)

// Colores del pinyin por tono (esquema habitual en enseñanza de chino:
// 1º rojo, 2º verde, 3º azul, 4º morado, 5º/átono gris). Legibles sobre oscuro y claro.
val Tone1Color = Color(0xFFEF5350)
val Tone2Color = Color(0xFF66BB6A)
val Tone3Color = Color(0xFF42A5F5)
val Tone4Color = Color(0xFFAB47BC)
val Tone5Color = Color(0xFF9E9E9E)

private val DarkScheme = darkColorScheme(
    primary = RedPrimary,
    onPrimary = Color(0xFF2B0B09),
    primaryContainer = RedPrimaryContainer,
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = AmberPinyin,
    onSecondary = Color(0xFF2E2000),
    background = InkBackground,
    onBackground = Color(0xFFECECEC),
    surface = InkSurface,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC7C7CF),
    outline = Color(0xFF6E6E78),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    background = Color(0xFFF7F5F2),
    onBackground = Color(0xFF1C1B1A),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFEFEDE9),
    onSurfaceVariant = Color(0xFF4A4A50),
    outline = Color(0xFF9A9AA2),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/**
 * Tema oscuro por defecto (pensado para clase con poca luz) con opción clara.
 */
@Composable
fun AsistenteChinoTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
