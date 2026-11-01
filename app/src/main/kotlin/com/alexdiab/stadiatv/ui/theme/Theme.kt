package com.alexdiab.stadiatv.ui.theme

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The BannerTV visual language: a neutral dark foundation with colour reserved for meaning
 * (live, starting soon, upcoming). Values mirror the iOS `Theme` so the apps feel like one product.
 */
object Palette {
    val Background = Color(0xFF080A0F)
    val Surface = Color(0xFF12151C)
    val SurfaceElevated = Color(0xFF181C24)
    val SurfaceHigh = Color(0xFF212633)
    val Accent = Color(0xFF3B82F6)
    val AccessibleAccent = Color(0xFF60A5FA)
    val ActionFill = Color(0xFF1D4ED8)
    val Live = Color(0xFFFF4D5E)
    val LiveFill = Color(0xFFD92D3F)
    val Starting = Color(0xFFF5B942)
    val Upcoming = Color(0xFF31C978)
    val Positive = Color(0xFF3DBE6B)
    val TextPrimary = Color(0xFFF7F8FA)
    val TextSecondary = Color(0xFF9BA3B2)
    val TextTertiary = Color(0xFF808898)
    val Hairline = Color(0x17FFFFFF)
    val HairlineStrong = Color(0x2EFFFFFF)
    val Scrim = Color(0xCC080A0F)
    val Gold = Color(0xFFFFD700)
    val Abyss = Color(0xFF0A1628)
    val Navy = Color(0xFF0D1F3C)
}

object Shapes {
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
}

object Spacing {
    val Xxs = 4.dp
    val Xs = 8.dp
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
    val Xxl = 48.dp

    /** TV safe area: horizontal margin and top inset on the 1920x1080 design canvas. */
    val ScreenH = 80.dp
    val ScreenTop = 28.dp
    val ScreenBottom = 56.dp
}

/** 10-foot type scale on the 1080p design canvas (1sp = 1px at 1080p). */
object Type {
    private val Family = FontFamily.Default
    private fun style(size: Int, weight: FontWeight, lineHeight: Int = (size * 1.22f).toInt(), spacing: Float = 0f, tnum: Boolean = false) =
        TextStyle(
            fontFamily = Family,
            fontSize = size.sp,
            fontWeight = weight,
            lineHeight = lineHeight.sp,
            letterSpacing = spacing.sp,
            fontFeatureSettings = if (tnum) "tnum" else null,
        )

    val Display = style(60, FontWeight.Bold, spacing = -0.8f)
    val Title1 = style(44, FontWeight.Bold, spacing = -0.5f)
    val Title2 = style(34, FontWeight.Bold, spacing = -0.3f)
    val Title3 = style(28, FontWeight.SemiBold, spacing = -0.2f)
    val Headline = style(24, FontWeight.SemiBold)
    val Body = style(22, FontWeight.Normal, lineHeight = 30)
    val BodyStrong = style(22, FontWeight.SemiBold, lineHeight = 30)
    val Callout = style(20, FontWeight.Medium, lineHeight = 27)
    val Caption = style(18, FontWeight.SemiBold, lineHeight = 24)
    val Overline = style(15, FontWeight.Bold, lineHeight = 20, spacing = 1.2f)

    val ScoreXl = style(64, FontWeight.Bold, lineHeight = 70, spacing = -1f, tnum = true)
    val ScoreLg = style(44, FontWeight.Bold, lineHeight = 50, spacing = -0.5f, tnum = true)
    val ScoreMd = style(30, FontWeight.Bold, lineHeight = 36, tnum = true)
    val Digits = style(20, FontWeight.SemiBold, lineHeight = 26, tnum = true)
}

/** Motion tokens: spring-based, short, never bouncy. */
object Motion {
    val FocusScale = spring<Float>(dampingRatio = 0.85f, stiffness = 520f)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val Quick = 140
    const val Standard = 240
    const val Slow = 380
}

@Immutable
class Gradients(
    val screen: Brush,
    val card: Brush,
    val heroFallback: Brush,
    val scrimBottom: Brush,
)

val LocalGradients = staticCompositionLocalOf {
    Gradients(
        screen = Brush.verticalGradient(listOf(Palette.Background, Palette.Background)),
        card = Brush.verticalGradient(listOf(Palette.Surface, Palette.Surface)),
        heroFallback = Brush.linearGradient(listOf(Palette.Abyss, Palette.Navy)),
        scrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Palette.Scrim)),
    )
}

object Tv {
    val gradients: Gradients @Composable @ReadOnlyComposable get() = LocalGradients.current
}

private val TvColorScheme: ColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.AccessibleAccent,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceElevated,
    onSurfaceVariant = Palette.TextSecondary,
    error = Palette.Live,
    border = Palette.Hairline,
)

/**
 * Lays the whole UI out on a fixed 1920x1080 canvas: 1dp equals one 1080p pixel on every device.
 * Fire TV sticks report anything from 720p-class to 1080p-class dp, and 4K models scale up, so
 * normalising density here means a 340dp card is the same fraction of the screen everywhere and
 * all type sizes in [Type] are exact.
 */
@Composable
fun StadiaTvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val density = remember(context) { designDensity(context) }
    val gradients = remember {
        Gradients(
            screen = Brush.verticalGradient(listOf(Color(0xFF0B0E15), Palette.Background)),
            card = Brush.verticalGradient(listOf(Color(0xFF151922), Palette.Surface)),
            heroFallback = Brush.linearGradient(listOf(Palette.Abyss, Palette.Navy)),
            scrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Palette.Scrim)),
        )
    }
    CompositionLocalProvider(LocalDensity provides density, LocalGradients provides gradients) {
        MaterialTheme(colorScheme = TvColorScheme, content = content)
    }
}

private fun designDensity(context: Context): Density {
    val metrics = Resources.getSystem().displayMetrics
    val activity = context as? Activity
    val width = activity?.resources?.displayMetrics?.widthPixels ?: metrics.widthPixels
    val height = activity?.resources?.displayMetrics?.heightPixels ?: metrics.heightPixels
    val longSide = maxOf(width, height).toFloat()
    return Density(density = longSide / DESIGN_WIDTH, fontScale = 1f)
}

private const val DESIGN_WIDTH = 1920f
