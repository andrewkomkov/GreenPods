package io.github.andrewkomkov.greenpods.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * GreenPods is, unsurprisingly, green. The palette is built around a single seed
 * so the light and dark schemes stay in step, and it is only used when the device
 * cannot supply a dynamic (Material You) scheme.
 */
private val Seed = Color(0xFF00A86B)

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF006C4C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF89F8C7),
        onPrimaryContainer = Color(0xFF002114),
        secondary = Color(0xFF4C6358),
        secondaryContainer = Color(0xFFCEE9DA),
        tertiary = Color(0xFF3D6373),
        tertiaryContainer = Color(0xFFC1E9FB),
        error = Color(0xFFBA1A1A),
        surface = Color(0xFFFBFDF9),
        surfaceContainer = Color(0xFFEFF1ED),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF6CDBAC),
        onPrimary = Color(0xFF003825),
        primaryContainer = Color(0xFF005138),
        onPrimaryContainer = Color(0xFF89F8C7),
        secondary = Color(0xFFB3CCBE),
        secondaryContainer = Color(0xFF344C41),
        tertiary = Color(0xFFA5CCDF),
        tertiaryContainer = Color(0xFF244C5B),
        error = Color(0xFFFFB4AB),
        surface = Color(0xFF191C1A),
        surfaceContainer = Color(0xFF1D211E),
    )

/**
 * Material 3 Expressive theme.
 *
 * Expressive motion comes from [GreenPodsMotion] rather than from the theme,
 * because material3 1.4.0 still keeps `MotionScheme` internal. Components take
 * their spring specs from there directly.
 */
@Composable
fun GreenPodsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You colours, where the platform supports them. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkScheme
            }

            else -> {
                LightScheme
            }
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** Exposed for previews and tests that need the non-dynamic palette. */
object GreenPodsPalette {
    val seed: Color = Seed
    val light = LightScheme
    val dark = DarkScheme
}
