package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightBorder,
    outlineVariant = LightBorder
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkBorder,
    outlineVariant = DarkBorder
)

@Immutable
data class PulsStatusColors(
    val active: Color,
    val activeContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val hairlineBorder: Color
)

val LocalPulsStatusColors = staticCompositionLocalOf {
    PulsStatusColors(
        active = LightSecondary,
        activeContainer = LightSecondaryContainer,
        warning = LightTertiary,
        warningContainer = LightTertiaryContainer,
        danger = LightError,
        dangerContainer = LightErrorContainer,
        hairlineBorder = LightBorder
    )
}

private val PulsShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PulsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val statusColors = if (darkTheme) {
        PulsStatusColors(
            active = DarkSecondary,
            activeContainer = DarkSecondaryContainer,
            warning = DarkTertiary,
            warningContainer = DarkTertiaryContainer,
            danger = DarkError,
            dangerContainer = DarkErrorContainer,
            hairlineBorder = DarkBorder
        )
    } else {
        PulsStatusColors(
            active = LightSecondary,
            activeContainer = LightSecondaryContainer,
            warning = LightTertiary,
            warningContainer = LightTertiaryContainer,
            danger = LightError,
            dangerContainer = LightErrorContainer,
            hairlineBorder = LightBorder
        )
    }

    CompositionLocalProvider(LocalPulsStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = PulsShapes,
            content = content
        )
    }
}
