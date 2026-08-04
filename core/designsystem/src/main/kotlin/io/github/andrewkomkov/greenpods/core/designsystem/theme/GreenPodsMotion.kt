package io.github.andrewkomkov.greenpods.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Material 3 Expressive motion, read from the theme.
 *
 * These used to be hand-written springs, because `MotionScheme` was `internal` in
 * material3 1.4.0 and the values had to be reproduced from the spec by hand. On
 * 1.5.0-alpha the real scheme is public, so this is now a thin alias over
 * `MaterialTheme.motionScheme` — call sites keep their names, and the numbers come from
 * the design system rather than from a comment claiming to match it.
 *
 * The distinction the scheme draws is worth keeping in mind at the call site.
 * **Spatial** springs move things and are under-damped; the overshoot is the point.
 * **Effects** springs carry colour and alpha, where an overshoot would read as a glitch,
 * so they do not bounce.
 */
object GreenPodsMotion {
    /** Quick reactions: a button responding to a press. */
    @Composable
    @ReadOnlyComposable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    /** The default for most movement. */
    @Composable
    @ReadOnlyComposable
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** Large or playful movement that should feel weighty. */
    @Composable
    @ReadOnlyComposable
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

    /** Colour, alpha and other non-spatial changes — no overshoot. */
    @Composable
    @ReadOnlyComposable
    fun <T> effects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()
}
