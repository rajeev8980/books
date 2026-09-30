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

val Ink = Color(0xFF1A1714)
val InkRaised = Color(0xFF2A241F)
val Wood = Color(0xFF4A372C)
val Slot = Color(0xFF1A120E)
val Paper = Color(0xFFF4E7D4)
val PaperCard = Color(0xFFFFF8EE)
val Cream = Color(0xFFF6F0E6)
val Muted = Color(0xFFB7A89A)
val InkText = Color(0xFF241C16)
val MutedInk = Color(0xFF7A6A5C)
val Wax = Color(0xFFC4552A)

@Composable
fun BoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Wax,
            onPrimary = Cream,
            background = Ink,
            surface = InkRaised,
            onSurface = Cream,
            onSurfaceVariant = Muted,
            error = Color(0xFFFFB4A2),
        ),
        typography = Typography(
            headlineLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                color = Cream,
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                color = InkText,
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = Cream,
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 0.6.sp,
                color = MutedInk,
            ),
        ),
        content = content,
    )
}
