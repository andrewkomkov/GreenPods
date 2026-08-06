package io.github.andrewkomkov.greenpods.core.designsystem.theme

import androidx.compose.ui.unit.dp

/** Layout constants that more than one module has to agree on. */
object GreenPodsSpacing {
    /**
     * Room a scrolling screen leaves at its bottom so the floating toolbar never covers
     * its last control.
     *
     * It belongs to the **content**, not to the screen's bounds. Reserving it by shrinking
     * the viewport was tried and is what made the toolbar stop floating: the content simply
     * ended above the bar and the strip underneath showed bare page colour, so the bar read
     * as sitting in a blank margin rather than hovering over anything. Cards have to run on
     * beneath it for it to look like a bar that floats.
     *
     * The cost is that every scrolling screen has to apply it, and one that forgets puts a
     * control under the toolbar. That is the trade: a visible bug on one screen, rather
     * than a layout that quietly cannot look right on any.
     */
    val FloatingBarSpace = 92.dp
}
