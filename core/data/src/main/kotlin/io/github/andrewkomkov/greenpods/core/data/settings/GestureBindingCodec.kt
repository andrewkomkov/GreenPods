package io.github.andrewkomkov.greenpods.core.data.settings

import io.github.andrewkomkov.greenpods.core.model.GestureAction
import io.github.andrewkomkov.greenpods.core.model.HeadGesture
import io.github.andrewkomkov.greenpods.core.model.HeadGestureBinding

/**
 * Serialises gesture bindings to a single preference string.
 *
 * Six rows of data do not justify pulling in a serialization plugin and its compiler
 * plugin, so the format is a flat, human-readable list:
 *
 * ```
 * NOD:ACCEPT_CALL:1:0.70|SHAKE:REJECT_CALL:1:0.70
 * ```
 *
 * Decoding is deliberately forgiving. A preference file written by a future version
 * may name a gesture or action this build has never heard of; the right response is to
 * drop that row and keep the rest, not to lose every binding the user configured.
 */
object GestureBindingCodec {
    private const val ROW = '|'
    private const val FIELD = ':'

    fun encode(bindings: List<HeadGestureBinding>): String =
        bindings.joinToString(ROW.toString()) { binding ->
            listOf(
                binding.gesture.name,
                binding.action.name,
                if (binding.enabled) "1" else "0",
                "%.2f".format(java.util.Locale.ROOT, binding.minimumConfidence),
            ).joinToString(FIELD.toString())
        }

    /**
     * Returns the decoded bindings, filled out with defaults for any gesture the stored
     * value did not mention — so a new gesture added in a later release appears rather
     * than silently missing.
     */
    fun decode(raw: String?): List<HeadGestureBinding> {
        val stored =
            raw
                .orEmpty()
                .split(ROW)
                .mapNotNull(::decodeRow)
                .associateBy(HeadGestureBinding::gesture)

        return HeadGestureBinding.Defaults.map { default -> stored[default.gesture] ?: default }
    }

    private fun decodeRow(row: String): HeadGestureBinding? {
        val fields = row.split(FIELD)
        if (fields.size < 4) return null

        val gesture = HeadGesture.entries.firstOrNull { it.name == fields[0] } ?: return null
        val action = GestureAction.entries.firstOrNull { it.name == fields[1] } ?: return null
        val confidence = fields[3].toFloatOrNull() ?: return null

        return HeadGestureBinding(
            gesture = gesture,
            action = action,
            enabled = fields[2] == "1",
            minimumConfidence = confidence.coerceIn(0f, 1f),
        )
    }
}
