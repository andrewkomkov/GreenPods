package io.github.andrewkomkov.greenpods.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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
 * [MaterialExpressiveTheme] rather than [androidx.compose.material3.MaterialTheme]: it
 * installs the expressive shape and type scales and — the part that shows in motion —
 * an expressive [MotionScheme], whose springs are deliberately under-damped so things
 * arrive and settle instead of stopping dead.
 *
 * This needs material3 1.5.0-alpha. The whole Expressive API surface is internal or
 * absent in 1.4.0 stable, which the BOM pins, so `libs.versions.toml` overrides it for
 * this one module. That is a real trade-off — an alpha in a shipping app — taken because
 * the alternative was reimplementing five components by hand and calling the result
 * Expressive.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Exposed for previews and tests that need the non-dynamic palette. */
object GreenPodsPalette {
    val seed: Color = Seed
    val light = LightScheme
    val dark = DarkScheme
}
