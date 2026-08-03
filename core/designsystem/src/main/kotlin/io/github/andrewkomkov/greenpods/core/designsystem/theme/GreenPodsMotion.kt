package io.github.andrewkomkov.greenpods.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Material 3 Expressive motion tokens, defined locally.
 *
 * Expressive motion replaces duration-and-easing with springs, so components
 * overshoot and settle instead of stopping dead. `MotionScheme` — the API that
 * normally carries these — is still `internal` in material3 1.4.0 and only becomes
 * public in 1.5.0-alpha, so the tokens are reproduced here rather than pinning the
 * whole app to an alpha dependency.
 *
 * Swap this file for `MaterialTheme.motionScheme` once 1.5.0 is stable; the spring
 * values are chosen to match, so behaviour should not visibly change.
 *
 * **Spatial** springs move things and are deliberately under-damped — the bounce is
 * the point. **Effects** springs handle colour and alpha, where overshoot would look
 * like a glitch, so they are critically damped.
 */
object GreenPodsMotion {
    /** Quick reactions: a button responding to a press. */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 800f)

    /** The default for most movement. */
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Large or playful movement that should feel weighty. */
    fun <T> slowSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 200f)

    /** Colour, alpha and other non-spatial changes — no overshoot. */
    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
}
