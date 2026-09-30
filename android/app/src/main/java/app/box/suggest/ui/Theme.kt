package app.box.suggest.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Black = Color(0xFF000000)
val BlackCard = Color(0xFF111111)
val Bubble = Color(0xFF1C1C1C)
val Line = Color(0xFF2A2A2A)
val White = Color(0xFFFFFFFF)
val Muted = Color(0xFF9A9A9A)
val Ink = Black
val Cream = White
val InkText = White
val MutedInk = Muted
val Wax = White
val Paper = Black
val PaperCard = Bubble

@Composable
fun BoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = White,
            onPrimary = Black,
            background = Black,
            surface = BlackCard,
            onSurface = White,
            onSurfaceVariant = Muted,
            error = Color(0xFFFF8A80),
        ),
        typography = Typography(
            headlineLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
                color = White,
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = White,
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = White,
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Muted,
            ),
        ),
        content = content,
    )
}
