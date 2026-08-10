package io.github.andrewkomkov.greenpods.core.data.settings

import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.HeadCalibration
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import io.github.andrewkomkov.greenpods.core.model.PodModel
import java.util.Locale

/**
 * Serialises one accessory's head calibration to a single preference string.
 *
 * Three rows of data do not justify a serialization plugin, so the format is the flat,
 * human-readable list [GestureBindingCodec] already establishes:
 *
 * ```
 * YAW:MEASURED:O1:0.0143000:6290:0|PITCH:MISMATCHED:O3:0:10920:0|ROLL:NOT_HELD::0:0:0
 * ```
 *
 * Decoding is forgiving in the same way and for the same reason: a preference written by a
 * future version may name a verdict this build has never heard of, and dropping that row
 * beats losing the two axes that did decode.
 *
 * **One thing is deliberately *not* copied from the gesture codec.** That one fills every
 * gesture it was not told about from `Defaults`. This one must never do that. An axis the
 * stored value does not mention decodes to [AxisVerdict.Uncalibrated] — absent, not
 * defaulted — because a default scale is a fabricated constant re-entering through the back
 * door, and a made-up scale is indistinguishable from a measured one once it is past here.
 */
object HeadCalibrationCodec {
    private const val ROW = '|'
    private const val FIELD = ':'

    fun encode(calibration: HeadCalibration): String =
        HeadAxis.entries.joinToString(ROW.toString()) { axis ->
            val stored = calibration.forAxis(axis)
            listOf(
                axis.name,
                stored.verdict.token(),
                stored.respondingField?.name.orEmpty(),
                scaleOf(stored.verdict).format(),
                deltaOf(stored.verdict).toString(),
                if (isConfirmed(stored.verdict)) "1" else "0",
            ).joinToString(FIELD.toString())
        }

    fun decode(
        model: PodModel,
        raw: String?,
        measuredAtEpochMillis: Long = 0L,
    ): HeadCalibration {
        val stored =
            raw
                .orEmpty()
                .split(ROW)
                .mapNotNull(::decodeRow)
                .associateBy(AxisCalibration::axis)

        return HeadCalibration(
            model = model,
            // Unmentioned axes become `Uncalibrated`, never a default scale — see the note
            // on this object.
            axes = HeadAxis.entries.associateWith { axis -> stored[axis] ?: AxisCalibration(axis) },
            measuredAtEpochMillis = measuredAtEpochMillis,
        )
    }

    private fun decodeRow(row: String): AxisCalibration? {
        val fields = row.split(FIELD)
        if (fields.size < 6) return null

        val axis = HeadAxis.entries.firstOrNull { it.name == fields[0] } ?: return null
        val field = OrientationField.entries.firstOrNull { it.name == fields[2] }
        val scale = fields[3].toFloatOrNull() ?: return null
        val delta = fields[4].toIntOrNull() ?: return null
        val confirmed = fields[5] == "1"

        val verdict =
            when (fields[1]) {
                MEASURED -> {
                    field?.let { AxisVerdict.Measured(scale, it, delta) }
                }

                SUSPECT -> {
                    field?.let {
                        AxisVerdict.Suspect(scale, it, delta, why = SUSPECT_RESTORED, confirmed = confirmed)
                    }
                }

                // A restored mismatch keeps the responding field, which is the evidence, and
                // reconstructs the expected one from the declaration rather than storing it
                // — one source of truth for the current assignment, even across a restart.
                MISMATCHED -> {
                    field?.let {
                        AxisVerdict.Mismatched(OrientationField.currentlyMappedTo(axis), it, delta)
                    }
                }

                INCONCLUSIVE -> {
                    AxisVerdict.Inconclusive(emptySet())
                }

                CROSS_COUPLED -> {
                    AxisVerdict.CrossCoupled(emptyMap())
                }

                NOT_HELD -> {
                    AxisVerdict.NotHeld(NOT_HELD_RESTORED)
                }

                SKIPPED -> {
                    AxisVerdict.Skipped
                }

                UNCALIBRATED -> {
                    AxisVerdict.Uncalibrated
                }

                else -> {
                    null
                }
            } ?: return null

        return AxisCalibration(axis, verdict)
    }

    private fun AxisVerdict.token(): String =
        when (this) {
            is AxisVerdict.Measured -> MEASURED
            is AxisVerdict.Suspect -> SUSPECT
            is AxisVerdict.Mismatched -> MISMATCHED
            is AxisVerdict.Inconclusive -> INCONCLUSIVE
            is AxisVerdict.CrossCoupled -> CROSS_COUPLED
            is AxisVerdict.NotHeld -> NOT_HELD
            AxisVerdict.Skipped -> SKIPPED
            AxisVerdict.Uncalibrated -> UNCALIBRATED
        }

    private fun scaleOf(verdict: AxisVerdict): Float =
        when (verdict) {
            is AxisVerdict.Measured -> verdict.degreesPerUnit
            is AxisVerdict.Suspect -> verdict.degreesPerUnit
            else -> 0f
        }

    private fun deltaOf(verdict: AxisVerdict): Int =
        when (verdict) {
            is AxisVerdict.Measured -> verdict.deltaUnits
            is AxisVerdict.Suspect -> verdict.deltaUnits
            is AxisVerdict.Mismatched -> verdict.deltaUnits
            else -> 0
        }

    private fun isConfirmed(verdict: AxisVerdict): Boolean =
        verdict is AxisVerdict.Suspect && verdict.confirmed

    /**
     * Seven decimal places, not two.
     *
     * A scale is a number like 0.0143 degrees per unit, and the gesture codec's `%.2f` would
     * round every plausible calibration to zero — silently turning a measurement into a
     * refusal that still claims to be a measurement.
     */
    private fun Float.format(): String = "%.7f".format(Locale.ROOT, this)

    private const val MEASURED = "MEASURED"
    private const val SUSPECT = "SUSPECT"
    private const val MISMATCHED = "MISMATCHED"
    private const val INCONCLUSIVE = "INCONCLUSIVE"
    private const val CROSS_COUPLED = "CROSS_COUPLED"
    private const val NOT_HELD = "NOT_HELD"
    private const val SKIPPED = "SKIPPED"
    private const val UNCALIBRATED = "UNCALIBRATED"

    /**
     * The prose reasons are not persisted, and the restored text says so.
     *
     * Keeping a sentence written for one run and replaying it after a restart would present
     * a stale explanation as a fresh one. The verdict survives; the story of that particular
     * hold does not, and the export (which does carry it) is where that belongs.
     */
    private const val NOT_HELD_RESTORED = "not held during the run that produced this calibration"
    private const val SUSPECT_RESTORED = "outside the plausible range when it was measured"
}
