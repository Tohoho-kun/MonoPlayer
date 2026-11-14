package com.example.monoplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
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

private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val PlusJakartaSansFont = GoogleFont("Plus Jakarta Sans")
private val NotoSansJpFont = GoogleFont("Noto Sans JP")

val PlusJakartaSansFamily = FontFamily(
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium),
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
    Font(googleFont = PlusJakartaSansFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Bold),
    Font(googleFont = NotoSansJpFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = NotoSansJpFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Medium),
    Font(googleFont = NotoSansJpFont, fontProvider = GoogleFontsProvider, weight = FontWeight.SemiBold),
    Font(googleFont = NotoSansJpFont, fontProvider = GoogleFontsProvider, weight = FontWeight.Bold),
)

private val AcousticPebbleTypography = Typography(
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
