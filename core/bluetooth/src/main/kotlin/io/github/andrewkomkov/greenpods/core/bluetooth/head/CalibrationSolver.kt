package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.AxisCalibration
import io.github.andrewkomkov.greenpods.core.model.AxisVerdict
import io.github.andrewkomkov.greenpods.core.model.HeadAxis
import io.github.andrewkomkov.greenpods.core.model.OrientationField
import kotlin.math.abs

/**
 * How far each raw field moved between the neutral hold and one pose's hold.
 *
 * [dominant] is null when no field stood out — the ratio between the largest response and
 * the second largest fell below the separation threshold. Returning null rather than "the
 * bigger one" is the inconclusive rule expressed as a type: a caller cannot accidentally use
 * a winner that was not one.
 */
data class AxisResponse(
    val deltas: Map<OrientationField, Int>,
    val dominant: OrientationField?,
    val separation: Float,
) {
    fun delta(field: OrientationField): Int = deltas[field] ?: 0

    val largest: OrientationField? get() = deltas.maxByOrNull { abs(it.value) }?.key
}

/**
 * Turns held segments into verdicts.
 *
 * The only place in this project where a degrees-per-unit scale is ever computed, and it
 * refuses far more often than it computes.
 *
 * It takes the **whole run at once** rather than a pose at a time, because
 * [AxisVerdict.CrossCoupled] is a statement about the run and not about any single pose. One
 * pose moving three fields is ambiguous — the wearer may simply have moved. Three poses each
 * moving the same three fields is a model failure, and a solver handed one pose could not
 * tell those apart.
 *
 * On the hardware this project has, cross-coupling is the **expected** outcome: a scripted
 * capture found that nodding moves two fields, shaking moves a third most, and tilting moves
 * two others, so no field belongs to one axis. That claim is itself unpinned prose from a
 * single session — which is precisely why this solver exists. Its job is to produce the
 * labelled measurement that would settle it, and to say plainly when the per-axis model does
 * not fit rather than fitting a number to it anyway.
 */
class CalibrationSolver(
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * How much the largest response must beat the second largest to count as dominant.
         *
         * Below this the pose is inconclusive. Two fields responding comparably means the
         * measurement cannot say which one the axis lives in, and picking the larger would
         * turn a coin toss into a stored constant.
         */
        val separationRatio: Float = DEFAULT_SEPARATION_RATIO,
        /**
         * How small a response can be and still be a response at all.
         *
         * Below this the pose moved nothing worth measuring, which is a different failure
         * from moving several things at once.
         */
        val minimumDeltaUnits: Int = DEFAULT_MINIMUM_DELTA_UNITS,
        /** The range a human head's scale can plausibly fall in, in degrees per raw unit. */
        val plausibleRange: ClosedFloatingPointRange<Float> = DEFAULT_PLAUSIBLE_RANGE,
    )

    /** One pose's held segment, with the axis it was asked to exercise. */
    data class Held(
        val axis: HeadAxis,
        val segment: HeldSegment,
    )

    /**
     * Solves a whole run.
     *
     * [neutral] is required: every scale is a difference against it (the accessory's zero is
     * wherever the head happened to be when the stream started), so without it nothing can
     * be derived and every axis comes back as unheld.
     */
    fun solve(
        neutral: HeldSegment?,
        poses: List<Held>,
        skipped: Set<HeadAxis> = emptySet(),
        notHeld: Map<HeadAxis, String> = emptyMap(),
        atEpochMillis: Long = 0L,
    ): Map<HeadAxis, AxisCalibration> {
        val responses = poses.associate { it.axis to respond(neutral, it.segment) }
        val coupled = isCrossCoupled(responses)

        return HeadAxis.entries.associateWith { axis ->
            val verdict =
                when {
                    // Evidence beats absence, and the order of these two decides which.
                    //
                    // A pose the wearer never attempted is `Skipped`; one they attempted,
                    // failed, and *then* skipped past is `NotHeld` — with what moved and by
                    // how much. Checking `skipped` first would collapse the second into the
                    // first and throw away the measurement, because skipping is the only way
                    // out of a failed pose that does not retry it. "You chose not to do this"
                    // and "you did it and here is what went wrong" are different findings,
                    // and the second is the one this feature exists to record.
                    //
                    // A retry supersedes: `repeat()` clears the recorded refusal, so an axis
                    // is only ever described by its most recent attempt.
                    notHeld.containsKey(axis) -> AxisVerdict.NotHeld(notHeld.getValue(axis))

                    axis in skipped -> AxisVerdict.Skipped

                    neutral == null -> AxisVerdict.NotHeld(NO_NEUTRAL)

                    responses[axis] == null -> AxisVerdict.Uncalibrated

                    // Checked before the per-axis verdicts, because it is a finding about the
                    // whole run and outranks whatever any single pose appears to say.
                    coupled -> AxisVerdict.CrossCoupled(responses.getValue(axis).deltas)

                    else -> verdictFor(axis, responses.getValue(axis))
                }
            AxisCalibration(axis, verdict, atEpochMillis)
        }
    }

    /**
     * How far every field moved between the two holds.
     *
     * Differences, never absolutes. The accessory reports orientation relative to some origin
     * it chose when the stream began, so an absolute median says nothing about a head.
     */
    fun respond(
        neutral: HeldSegment?,
        pose: HeldSegment,
    ): AxisResponse {
        val deltas =
            OrientationField.entries.associateWith { field ->
                val from = neutral?.median(field) ?: 0f
                (pose.median(field) - from).toInt()
            }

        val ranked = deltas.entries.sortedByDescending { abs(it.value) }
        val largest = abs(ranked[0].value)
        val second = abs(ranked[1].value)

        val separation = if (second == 0) Float.MAX_VALUE else largest.toFloat() / second
        val dominant =
            ranked[0].key.takeIf {
                largest >= config.minimumDeltaUnits && separation >= config.separationRatio
            }

        return AxisResponse(deltas, dominant, separation)
    }

    /**
     * True when no pose in the run separated the fields.
     *
     * Requires **every** pose to have failed to produce a dominant field. One ambiguous pose
     * among three clean ones is that pose's problem — reported as inconclusive — not evidence
     * that the model is wrong.
     */
    private fun isCrossCoupled(responses: Map<HeadAxis, AxisResponse>): Boolean =
        responses.size >= MINIMUM_POSES_FOR_COUPLING && responses.values.all { it.dominant == null }

    private fun verdictFor(
        axis: HeadAxis,
        response: AxisResponse,
    ): AxisVerdict {
        val dominant =
            response.dominant
                ?: return AxisVerdict.Inconclusive(
                    response.deltas
                        .filterValues { abs(it) >= config.minimumDeltaUnits }
                        .keys
                        .toSet(),
                )

        val delta = abs(response.delta(dominant))
        val expected = OrientationField.currentlyMappedTo(axis)

        // A wrong axis assignment is louder than a wrong number, deliberately. A scale
        // derived on top of one would be worse than no calibration, because it looks
        // calibrated.
        if (dominant != expected) {
            return AxisVerdict.Mismatched(
                expectedField = expected,
                respondingField = dominant,
                deltaUnits = delta,
            )
        }

        val scale = axis.referenceDegrees / delta
        if (scale !in config.plausibleRange) {
            return AxisVerdict.Suspect(
                degreesPerUnit = scale,
                field = dominant,
                deltaUnits = delta,
                why =
                    "${axis.referenceDegrees.toInt()}° from $delta units implies " +
                        "%.4f°/unit, outside the plausible %.4f..%.4f"
                            .format(scale, config.plausibleRange.start, config.plausibleRange.endInclusive),
            )
        }

        return AxisVerdict.Measured(degreesPerUnit = scale, field = dominant, deltaUnits = delta)
    }

    companion object {
        /**
         * Twice as large, and no less.
         *
         * Chosen rather than measured, and small enough to be honest about: the fields are
         * cross-coupled on the hardware in hand, so there is no clean separation in any
         * capture from which to derive a threshold. A factor of two is the point at which
         * "one field moved and another followed it" stops being arguable.
         */
        const val DEFAULT_SEPARATION_RATIO = 2.0f

        /**
         * Below this a pose moved nothing worth measuring.
         *
         * Set to the plateau tolerance: a response smaller than the amount a *stationary*
         * head is allowed to wander is not a response, it is noise that happened to point
         * somewhere.
         */
        const val DEFAULT_MINIMUM_DELTA_UNITS = PlateauDetector.DEFAULT_TOLERANCE_UNITS

        /**
         * What a plausible scale looks like, in degrees per raw unit.
         *
         * Sanity rails around obvious nonsense, not a claim about the true value — which is
         * the whole open question this feature exists to measure. Read as what the **full
         * int16 range** would then mean:
         *
         * - Above `0.05`, the sensor's range would span more than 1 600° — over four
         *   complete turns of a neck that manages perhaps 180°.
         * - Below `0.0015`, it would span under 100°, which is less than the movement it has
         *   to encode.
         *
         * **These bounds were chosen to be reachable, and the first pair was not.** The
         * original `0.0005..0.5` could never fire: [minimumDeltaUnits] rejects any delta
         * under 900 before a scale is computed, so for a 90° pose the largest derivable scale
         * is `90/900 = 0.1`, and the smallest — bounded by the int16 range — is about
         * `0.0014`. Both sat inside the old range, which made the entire suspect path dead
         * code that looked like a safety check. A guard that cannot trigger is worse than no
         * guard, because it is read as protection.
         */
        val DEFAULT_PLAUSIBLE_RANGE = 0.0015f..0.05f

        private const val MINIMUM_POSES_FOR_COUPLING = 2

        private const val NO_NEUTRAL =
            "the neutral pose was never held, and every scale is measured against it"
    }
}
