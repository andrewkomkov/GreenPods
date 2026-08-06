@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.andrewkomkov.greenpods.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One choice out of a few, as a connected button group.
 *
 * This is Material 3 Expressive's own answer to the segmented control, and it replaces
 * the rows of `FilterChip`s this app used everywhere — noise control, the long-press
 * cycle, the scan cadence. Chips were the wrong component for the job twice over: they
 * are meant for filters and attributes rather than for a mutually exclusive setting, and
 * a wrapped row of them reads as a *list of tags* rather than as one control with several
 * positions. Ten of them stacked is what made this screen look like a dump of state.
 *
 * The group is connected — the outer corners are round, the inner ones square, and the
 * pressed button swells while its neighbours give way. That squeeze is the whole point of
 * the component: it makes a set of buttons feel like one physical switch, and it is
 * animation the framework does, not something reimplemented here.
 *
 * [enabled] false is for a transport gate, not for a grey-out. The buttons stay legible
 * and in place; the screen says why above them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (options.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val shapes =
                when {
                    // First and last are checked before middle, so a group of one — which
                    // no caller has, and which would look odd anyway — takes the leading
                    // shape rather than falling through to a flat-sided middle.
                    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()

                    index == options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()

                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }

            val text = label(option)

            ToggleButton(
                checked = selected(option),
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = shapes,
                // Weighted by label length rather than equally. Equal segments look tidy
                // until the labels are "Off" and "Transparency" in the same group, at
                // which point the long one is cut to "Transp…" — and a control that
                // hides what it does is worse than an uneven one. Segments of unequal
                // width still read as one control; a truncated label does not read at all.
                modifier = Modifier.weight(text.length.coerceAtLeast(MIN_WEIGHT).toFloat()),
                // Tighter than the default. A four-position group of ordinary English
                // words does not fit a phone at the default padding: "Off" was allotted
                // about a ninth of the row, almost all of which the padding took, and the
                // label rendered as a bare ellipsis. Two buttons on this screen said "…",
                // which is a control that has stopped being one.
                contentPadding = SegmentPadding,
            ) {
                Text(
                    text,
                    maxLines = 1,
                    // A last resort on a very narrow screen; the weights above are what
                    // is meant to keep it from happening.
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Below this, a short label like "Off" would be squeezed thinner than its own padding.
 *
 * Raised from 4 to 7: the floor has to be a share of the row that can still hold three
 * characters *and* the padding, and 4 was measured against the text alone.
 */
private const val MIN_WEIGHT = 7

/** Just enough to keep the label off the edge; the group is what carries the shape. */
private val SegmentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
