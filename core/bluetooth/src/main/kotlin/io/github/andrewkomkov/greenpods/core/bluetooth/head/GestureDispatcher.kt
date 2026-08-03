package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding
import io.github.andrewkomkov.greenpods.core.model.HeadGestureEvent

/**
 * Decides whether a detected head movement should actually do anything.
 *
 * Split out from [HeadGestureDetector] because the two answer different questions: the
 * detector asks "did the head move like that?", the dispatcher asks "did the user ask
 * for that to mean something, and was the movement clean enough to trust?".
 *
 * The confidence gate matters most for destructive bindings — rejecting a call on a
 * sloppy shake is worse than missing the gesture entirely — which is why the threshold
 * is per binding rather than global.
 */
object GestureDispatcher {
    /**
     * Returns the action to perform, or null when the gesture is unbound, the binding
     * is disabled, the action is [GestureAction.NONE], or the detection was not
     * confident enough.
     */
    fun resolve(
        event: HeadGestureEvent,
        bindings: List<HeadGestureBinding>,
        gesturesEnabled: Boolean = true,
    ): GestureAction? {
        if (!gesturesEnabled) return null
        val binding = bindings.firstOrNull { it.gesture == event.gesture } ?: return null
        if (!binding.enabled) return null
        if (binding.action == GestureAction.NONE) return null
        if (event.confidence < binding.minimumConfidence) return null
        return binding.action
    }
}
