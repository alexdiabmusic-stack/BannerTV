package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BannerBlue = Color(0xFF4B86F7)
val BannerLive = Color(0xFFEF5966)
val BannerGreen = Color(0xFF54BC8A)

/** Semantic pairs are opaque: their contrast must not depend on the parent card color. */
@Immutable
data class PhoneStatusColors(val foreground: Color, val container: Color)

@Immutable
data class PhoneSemanticColors(
    val live: PhoneStatusColors,
    val success: PhoneStatusColors,
    val warning: PhoneStatusColors,
    val stale: PhoneStatusColors,
    val error: PhoneStatusColors,
)

private val DarkStatusColors = PhoneSemanticColors(
    live = PhoneStatusColors(Color(0xFFFF8997), Color(0xFF3A1824)),
    success = PhoneStatusColors(Color(0xFF69D2A1), Color(0xFF113528)),
    warning = PhoneStatusColors(Color(0xFFF0C778), Color(0xFF372C18)),
    stale = PhoneStatusColors(Color(0xFFBFC9D7), Color(0xFF273344)),
    error = PhoneStatusColors(Color(0xFFFFB4AB), Color(0xFF3A1B1C)),
)
private val LightStatusColors = PhoneSemanticColors(
    live = PhoneStatusColors(Color(0xFFB4233A), Color(0xFFFDE8EC)),
    success = PhoneStatusColors(Color(0xFF126D48), Color(0xFFE3F4EA)),
    warning = PhoneStatusColors(Color(0xFF785100), Color(0xFFFFF1CC)),
    stale = PhoneStatusColors(Color(0xFF46566D), Color(0xFFE8EDF4)),
    error = PhoneStatusColors(Color(0xFFA82924), Color(0xFFFDECEA)),
)
private val LocalPhoneSemanticColors = staticCompositionLocalOf { DarkStatusColors }

object PhoneDesign {
    val colors: PhoneSemanticColors
        @Composable get() = LocalPhoneSemanticColors.current
}

object PhoneSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val touchTarget = 48.dp
}

object PhoneShape {
    val control = RoundedCornerShape(8.dp)
    val panel = RoundedCornerShape(12.dp)
    val feature = RoundedCornerShape(20.dp)
}

/** Tabular figures hold the score column steady as a provider updates its numbers. */
object PhoneType {
    val score = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val compactScore = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFF79B8FF), onPrimary = Color(0xFF071524),
    primaryContainer = Color(0xFF183F69), onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Color(0xFFA6B4C8), onSecondary = Color(0xFF142136),
    secondaryContainer = Color(0xFF27384F), onSecondaryContainer = Color(0xFFDEE8F5),
    tertiary = Color(0xFF9DCBC8), onTertiary = Color(0xFF143330),
    tertiaryContainer = Color(0xFF234A46), onTertiaryContainer = Color(0xFFD0F1EB),
    background = Color(0xFF090F1B), onBackground = Color(0xFFEEF4FC),
    surface = Color(0xFF111C2E), onSurface = Color(0xFFEEF4FC),
    surfaceDim = Color(0xFF090F1B), surfaceBright = Color(0xFF30415B),
    surfaceContainerLowest = Color(0xFF070D17), surfaceContainerLow = Color(0xFF0E1828),
    surfaceContainer = Color(0xFF111C2E), surfaceContainerHigh = Color(0xFF1A2940), surfaceContainerHighest = Color(0xFF26374F),
    surfaceVariant = Color(0xFF1A2940), onSurfaceVariant = Color(0xFFA6B4C8),
    outline = Color(0xFF788AA3), outlineVariant = Color(0xFF33445D),
    error = DarkStatusColors.error.foreground, onError = Color(0xFF601410),
    errorContainer = DarkStatusColors.error.container, onErrorContainer = DarkStatusColors.error.foreground,
    inverseSurface = Color(0xFFDEE8F5), inverseOnSurface = Color(0xFF142136), inversePrimary = Color(0xFF005EC4),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF005EC4), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAFF), onPrimaryContainer = Color(0xFF123860),
    secondary = Color(0xFF46566D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E9F4), onSecondaryContainer = Color(0xFF203751),
    tertiary = Color(0xFF366661), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFEBE5), onTertiaryContainer = Color(0xFF163F39),
    background = Color(0xFFF3F6FA), onBackground = Color(0xFF142136),
    surface = Color.White, onSurface = Color(0xFF142136),
    surfaceDim = Color(0xFFD8E0EC), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F8FC),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE8EEF6), surfaceContainerHighest = Color(0xFFDCE5F0),
    surfaceVariant = Color(0xFFE8EEF6), onSurfaceVariant = Color(0xFF46566D),
    outline = Color(0xFF718197), outlineVariant = Color(0xFFCCD6E4),
    error = LightStatusColors.error.foreground, onError = Color.White,
    errorContainer = LightStatusColors.error.container, onErrorContainer = LightStatusColors.error.foreground,
    inverseSurface = Color(0xFF203047), inverseOnSurface = Color(0xFFEEF4FC), inversePrimary = Color(0xFF79B8FF),
)

@Composable
fun PhoneTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = when (appearance) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalPhoneSemanticColors provides if (dark) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp), small = PhoneShape.control,
                medium = PhoneShape.panel, large = PhoneShape.feature, extraLarge = RoundedCornerShape(28.dp),
            ),
            typography = Typography(
                displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
                headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
                headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
                headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
                titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
                labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            ),
            content = content,
        )
    }
}
