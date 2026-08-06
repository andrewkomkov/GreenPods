package io.github.andrewkomkov.greenpods.core.bluetooth.head

import io.github.andrewkomkov.greenpods.core.model.HeadTrackingSample

/**
 * Sample runs for the calibration tests.
 *
 * **Every sequence in this file is synthetic, and that is stated rather than implied.**
 *
 * The project's rule is that decoders are pinned against captures from real hardware, and
 * this file deliberately does not pretend to be one. The capture the calibration spec was
 * originally written around — 2 876 samples over 143 s — is not in this repository, and it
 * was taken through a decoder bug that has since been fixed, so pinning a new detector to it
 * would pin the detector to values read from moving offsets.
 *
 * The real frames in `head-tracking-varint-boundary.txt` remain the only capture used for
 * anything touching the decode path; they are exercised by `HeadTrackingOffsetTest`.
 *
 * A run of the calibration wizard is what produces the first genuinely labelled series, and
 * when it exists it lands in `head-tracking-poses.txt` with a provenance header. At that
 * point these synthetic sequences stay — they cover cases a person cannot reliably perform,
 * such as holding perfectly still — but they stop being the only thing here.
 */
object HeadCalibrationFixtures {
    private const val SAMPLE_INTERVAL_MILLIS = 40L

    /** A steady hold: every field pinned, with only the jitter asked for. */
    fun steady(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
        count: Int = 60,
        jitter: Int = 0,
        startMillis: Long = 0L,
    ): List<PlateauDetector.Timed> =
        (0 until count).map { index ->
            // Alternating rather than random, so a failing run is reproducible and a reader
            // can work out what the detector saw.
            val wobble = if (index % 2 == 0) jitter else -jitter
            PlateauDetector.Timed(
                sample =
                    HeadTrackingSample(
                        orientation1 = (o1 + wobble).toShort(),
                        orientation2 = (o2 + wobble).toShort(),
                        orientation3 = (o3 + wobble).toShort(),
                        horizontalAcceleration = 0,
                        verticalAcceleration = 0,
                    ),
                atMillis = startMillis + index * SAMPLE_INTERVAL_MILLIS,
            )
        }

    /** A drift with no plateau anywhere: every sample further from the last. */
    fun drifting(
        count: Int = 60,
        stepUnits: Int = 200,
        startMillis: Long = 0L,
    ): List<PlateauDetector.Timed> =
        (0 until count).map { index ->
            PlateauDetector.Timed(
                sample =
                    HeadTrackingSample(
                        orientation1 = (index * stepUnits).toShort(),
                        orientation2 = 0,
                        orientation3 = 0,
                        horizontalAcceleration = 0,
                        verticalAcceleration = 0,
                    ),
                atMillis = startMillis + index * SAMPLE_INTERVAL_MILLIS,
            )
        }

    /**
     * A settle-then-hold: noise first, then a clean plateau.
     *
     * The shape a real pose actually has, and the reason the detector must not trust the
     * countdown — a wearer takes a moment to arrive at the pose they were asked for.
     */
    fun settleThenHold(
        o1: Int = 0,
        o2: Int = 0,
        o3: Int = 0,
        noiseCount: Int = 15,
        holdCount: Int = 60,
    ): List<PlateauDetector.Timed> {
        val noise = drifting(count = noiseCount, stepUnits = 500)
        val hold =
            steady(
                o1 = o1,
                o2 = o2,
                o3 = o3,
                count = holdCount,
                startMillis = noiseCount * SAMPLE_INTERVAL_MILLIS,
            )
        return noise + hold
    }
}
