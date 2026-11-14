package com.example.monoplayer.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.example.monoplayer.R

/**
 * "Acoustic Pebble" design system tokens from DESIGN.md.
 * Inspired by smoked acrylic, obsidian glass, and submerged OLED luminescence.
 */
object AcousticPebbleColors {
    val Surface = Color(0xFF10141A)
    val SurfaceDim = Color(0xFF10141A)
    val SurfaceBright = Color(0xFF353940)
    val SurfaceContainerLowest = Color(0xFF0A0E14)
    val SurfaceContainerLow = Color(0xFF181C22)
    val SurfaceContainer = Color(0xFF1C2026)
    val SurfaceContainerHigh = Color(0xFF262A31)
    val SurfaceContainerHighest = Color(0xFF31353C)

    val OnSurface = Color(0xFFDFE2EB)
    val OnSurfaceVariant = Color(0xFFBCC9CC)
    val InverseSurface = Color(0xFFDFE2EB)
    val InverseOnSurface = Color(0xFF2D3137)

    val Outline = Color(0xFF879396)
    val OutlineVariant = Color(0xFF3D494C)
    val SurfaceTint = Color(0xFF65D6EC)

    val Primary = Color(0xFFD9F7FF)
    val OnPrimary = Color(0xFF00363F)
    val PrimaryContainer = Color(0xFF76E5FC)
    val OnPrimaryContainer = Color(0xFF006675)
    val InversePrimary = Color(0xFF006877)

    val Secondary = Color(0xFFBFC8CF)
    val OnSecondary = Color(0xFF293237)
    val SecondaryContainer = Color(0xFF3F484E)
    val OnSecondaryContainer = Color(0xFFADB6BD)

    val Tertiary = Color(0xFFEBF3FF)
    val OnTertiary = Color(0xFF1F3245)
    val TertiaryContainer = Color(0xFFC4D8F0)
    val OnTertiaryContainer = Color(0xFF4B5E73)

    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    val Background = Color(0xFF10141A)
    val OnBackground = Color(0xFFDFE2EB)
    val SurfaceVariant = Color(0xFF31353C)
}

private val AcousticPebbleColorScheme = darkColorScheme(
    primary = AcousticPebbleColors.Primary,
    onPrimary = AcousticPebbleColors.OnPrimary,
    primaryContainer = AcousticPebbleColors.PrimaryContainer,
    onPrimaryContainer = AcousticPebbleColors.OnPrimaryContainer,
    inversePrimary = AcousticPebbleColors.InversePrimary,
    secondary = AcousticPebbleColors.Secondary,
    onSecondary = AcousticPebbleColors.OnSecondary,
    secondaryContainer = AcousticPebbleColors.SecondaryContainer,
    onSecondaryContainer = AcousticPebbleColors.OnSecondaryContainer,
    tertiary = AcousticPebbleColors.Tertiary,
    onTertiary = AcousticPebbleColors.OnTertiary,
    tertiaryContainer = AcousticPebbleColors.TertiaryContainer,
    onTertiaryContainer = AcousticPebbleColors.OnTertiaryContainer,
    background = AcousticPebbleColors.Background,
    onBackground = AcousticPebbleColors.OnBackground,
    surface = AcousticPebbleColors.Surface,
    onSurface = AcousticPebbleColors.OnSurface,
    surfaceVariant = AcousticPebbleColors.SurfaceVariant,
    onSurfaceVariant = AcousticPebbleColors.OnSurfaceVariant,
    surfaceTint = AcousticPebbleColors.SurfaceTint,
    inverseSurface = AcousticPebbleColors.InverseSurface,
    inverseOnSurface = AcousticPebbleColors.InverseOnSurface,
    error = AcousticPebbleColors.Error,
    onError = AcousticPebbleColors.OnError,
    errorContainer = AcousticPebbleColors.ErrorContainer,
    onErrorContainer = AcousticPebbleColors.OnErrorContainer,
    outline = AcousticPebbleColors.Outline,
    outlineVariant = AcousticPebbleColors.OutlineVariant,
    surfaceDim = AcousticPebbleColors.SurfaceDim,
    surfaceBright = AcousticPebbleColors.SurfaceBright,
    surfaceContainerLowest = AcousticPebbleColors.SurfaceContainerLowest,
    surfaceContainerLow = AcousticPebbleColors.SurfaceContainerLow,
    surfaceContainer = AcousticPebbleColors.SurfaceContainer,
    surfaceContainerHigh = AcousticPebbleColors.SurfaceContainerHigh,
    surfaceContainerHighest = AcousticPebbleColors.SurfaceContainerHighest,
)

private object CompositeBundledFontLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        if (font !is CompositeBundledFont) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val primaryFont = android.graphics.fonts.Font.Builder(context.resources, font.primaryResId).build()
            val fallbackFont = android.graphics.fonts.Font.Builder(context.resources, font.fallbackResId).build()
            val primaryFamily = android.graphics.fonts.FontFamily.Builder(primaryFont).build()
            val fallbackFamily = android.graphics.fonts.FontFamily.Builder(fallbackFont).build()
            return Typeface.CustomFallbackBuilder(primaryFamily)
                .addCustomFallback(fallbackFamily)
                .build()
        }
        return ResourcesCompat.getFont(context, font.primaryResId)
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? {
        return loadBlocking(context, font)
    }
}

private class CompositeBundledFont(
    val primaryResId: Int,
    val fallbackResId: Int,
    override val weight: FontWeight,
    override val style: FontStyle = FontStyle.Normal
) : AndroidFont(FontLoadingStrategy.Blocking, CompositeBundledFontLoader, FontVariation.Settings()) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompositeBundledFont) return false
        return primaryResId == other.primaryResId &&
            fallbackResId == other.fallbackResId &&
            weight == other.weight &&
            style == other.style
    }

    override fun hashCode(): Int {
        var result = primaryResId
        result = 31 * result + fallbackResId
        result = 31 * result + weight.hashCode()
        result = 31 * result + style.hashCode()
        return result
    }
}

val PlusJakartaSansFamily = FontFamily(
    CompositeBundledFont(R.font.plus_jakarta_sans_regular, R.font.noto_sans_jp_regular, FontWeight.Normal),
    CompositeBundledFont(R.font.plus_jakarta_sans_medium, R.font.noto_sans_jp_medium, FontWeight.Medium),
    CompositeBundledFont(R.font.plus_jakarta_sans_semibold, R.font.noto_sans_jp_medium, FontWeight.SemiBold),
    CompositeBundledFont(R.font.plus_jakarta_sans_bold, R.font.noto_sans_jp_bold, FontWeight.Bold),
)

private val AcousticPebbleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.42).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.22).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.08).sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.48.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun AcousticPebbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AcousticPebbleColorScheme,
        typography = AcousticPebbleTypography,
        content = content
    )
}
