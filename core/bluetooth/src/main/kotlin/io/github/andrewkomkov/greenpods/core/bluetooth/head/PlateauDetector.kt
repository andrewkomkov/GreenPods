package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample
import io.github.andrewkomkov.greenpods.core.model.OrientationField

/**
 * A run of samples during which the head stayed still enough to count as holding a pose.
 *
 * [medians] rather than means: a single jitter sample must not move the answer, and the
 * whole point of a plateau is that the middle of it is more trustworthy than its edges.
 *
 * [spans] is the evidence for "steady" — max minus min inside the segment, per field, in raw
 * units. Carried out of the detector because a refusal has to be able to say *how far* the
 * pose moved, and "you moved" without a number is not something anyone can act on.
 */
data class HeldSegment(
    val medians: Map<OrientationField, Float>,
    val spans: Map<OrientationField, Int>,
    val durationMillis: Long,
    val sampleCount: Int,
) {
    fun median(field: OrientationField): Float = medians[field] ?: 0f

    val widestSpan: Int get() = spans.values.maxOrNull() ?: 0
}

/**
 * Finds the stretch of a pose during which the values actually held still.
 *
 * Pure, and deliberately **not** a reuse of [HeadGestureDetector]. The two look alike and
 * mean opposite things: the gesture detector fires on movement *past* a threshold, this one
 * fires on the *absence* of movement. More decisively, every entry point of the gesture
 * detector takes a `HeadPose` in degrees — which puts it downstream of the very constant
 * calibration exists to replace. Measuring through it would make the result depend on the
 * uncalibrated scale, and that circularity produces a plausible number rather than an
 * obvious failure.
 *
 * Two rules define a hold, and both matter:
 *
 * - **Every field must stay within tolerance**, not just the one the pose is about. A pose
 *   held perfectly in yaw while the head drifts in pitch is not a held pose, and the
 *   difference is exactly what the axis-assignment check depends on.
 * - **The countdown is not trusted.** The wizard bounds *when* samples are collected; this
 *   finds the plateau inside them. People start late, settle slowly, and relax early.
 */
class PlateauDetector(
    private val config: Config = Config(),
) {
    data class Config(
        /**
         * How far a field may wander, in raw units, and still count as held.
         *
         * **Provisional.** 900 comes from this feature's own requirements checklist, which
         * records that plateaus in the motivating session were found at that tolerance. That
         * session was captured through a decoder bug that has since been fixed, so this is a
         * starting point and not a measurement — and it is a setting precisely so that
         * correcting it stays a measurement rather than a rebuild.
         *
         * The number that felt right before reading the checklist was 150, which would have
         * reported every pose as unheld and looked like a broken detector.
         */
        val toleranceUnits: Int = DEFAULT_TOLERANCE_UNITS,
        /** How long a stretch must stay within tolerance before it counts. */
        val minimumHoldMillis: Long = DEFAULT_MINIMUM_HOLD_MILLIS,
        /** Below this, a median is not describing anything. */
        val minimumSamples: Int = DEFAULT_MINIMUM_SAMPLES,
    )

    /**
     * The longest held stretch in [samples], or null if there was none.
     *
     * Longest rather than first: a wearer who settles, drifts, then settles again for longer
     * has given a better measurement the second time, and taking the first would throw it
     * away.
     */
    fun longestHold(samples: List<Timed>): HeldSegment? {
        if (samples.size < config.minimumSamples) return null

        var best: HeldSegment? = null
        var start = 0

        // A sliding window that only ever grows from the right and shrinks from the left, so
        // each sample is considered a bounded number of times rather than the whole run
        // being re-scanned per candidate.
        for (end in samples.indices) {
            while (start < end && !withinTolerance(samples, start, end)) {
                start++
            }
            val candidate = segmentOf(samples, start, end) ?: continue
            if (best == null || candidate.durationMillis > best.durationMillis) {
                best = candidate
            }
        }
        return best
    }

    private fun withinTolerance(
        samples: List<Timed>,
        start: Int,
        end: Int,
    ): Boolean =
        OrientationField.entries.all { field ->
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (index in start..end) {
                val value = field.of(samples[index].sample).toInt()
                if (value < min) min = value
                if (value > max) max = value
            }
            max - min <= config.toleranceUnits
        }

    private fun segmentOf(
        samples: List<Timed>,
        start: Int,
        end: Int,
    ): HeldSegment? {
        val count = end - start + 1
        if (count < config.minimumSamples) return null

        val duration = samples[end].atMillis - samples[start].atMillis
        if (duration < config.minimumHoldMillis) return null

        val window = samples.subList(start, end + 1)
        return HeldSegment(
            medians = OrientationField.entries.associateWith { field -> median(window, field) },
            spans = OrientationField.entries.associateWith { field -> span(window, field) },
            durationMillis = duration,
            sampleCount = count,
        )
    }

    private fun median(
        window: List<Timed>,
        field: OrientationField,
    ): Float {
        val sorted = window.map { field.of(it.sample).toInt() }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toFloat()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    private fun span(
        window: List<Timed>,
        field: OrientationField,
    ): Int {
        val values = window.map { field.of(it.sample).toInt() }
        return (values.max() - values.min())
    }

    /**
     * Why a stretch of samples did not count, in words a wearer can act on.
     *
     * Produced from the same samples the detector refused, so the sentence carries the
     * measurement rather than a guess about it.
     */
    fun refusal(samples: List<Timed>): String {
        if (samples.size < config.minimumSamples) {
            return "only ${samples.size} samples arrived — the stream may have stopped"
        }
        val worst =
            OrientationField.entries.maxByOrNull { field ->
                span(samples, field)
            } ?: return "no orientation samples arrived"
        val widest = span(samples, worst)
        if (widest > config.toleranceUnits) {
            return "never settled: $worst moved $widest units over the whole hold, " +
                "past the ${config.toleranceUnits} allowed"
        }
        val duration = samples.last().atMillis - samples.first().atMillis
        return "held for only ${duration}ms, less than the ${config.minimumHoldMillis}ms required"
    }

    /** One sample with the moment it arrived. */
    data class Timed(
        val sample: HeadTrackingSample,
        val atMillis: Long,
    )

    companion object {
        /** See [Config.toleranceUnits] — provisional, from the motivating session. */
        const val DEFAULT_TOLERANCE_UNITS = 900

        /** At the stream's 25 Hz this is fifty samples, enough for a median to mean something. */
        const val DEFAULT_MINIMUM_HOLD_MILLIS = 2_000L

        const val DEFAULT_MINIMUM_SAMPLES = 10
    }
}
