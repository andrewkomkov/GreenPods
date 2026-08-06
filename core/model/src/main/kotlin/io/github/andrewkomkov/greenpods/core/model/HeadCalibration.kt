package io.github.andrewkomkov.greenpods.core.model

/**
 * The three axes of head movement, named for the movement rather than for the wire field.
 *
 * That distinction is the point. "The axis a person turns their head around" and "the field
 * this app reads for yaw" are different claims, and collapsing them is the mistake the
 * motivating capture could not rule out — see `specs/004-head-tracking-calibration`.
 *
 * [referenceDegrees] is **nominal**: it is what the wizard asks the wearer for, never what
 * their neck did. The app cannot observe the true angle, so this is the feature's accuracy
 * floor, and it is carried here so the number's status travels with it in code as well as in
 * prose.
 */
enum class HeadAxis(
    val referenceDegrees: Float,
) {
    /** Turning the head, chin toward a shoulder. */
    YAW(90f),

    /** Nodding, chin toward the chest. */
    PITCH(45f),

    /** Tilting, ear toward a shoulder. */
    ROLL(45f),
}

/**
 * Which raw field of [HeadTrackingSample] responded.
 *
 * Named `o1`..`o3` rather than yaw/pitch/roll for the same reason the decoder names them
 * that way: what these values mean is not settled, and a name that asserts it would be a
 * claim the project cannot back.
 */
enum class OrientationField {
    O1,
    O2,
    O3,
    ;

    fun of(sample: HeadTrackingSample): Short =
        when (this) {
            O1 -> sample.orientation1
            O2 -> sample.orientation2
            O3 -> sample.orientation3
        }

    companion object {
        /**
         * The assignment the app currently reads, declared **once**.
         *
         * Every mismatch check compares against this. Repeating the convention at a call
         * site would mean two places could disagree about what "currently mapped" means,
         * and the whole purpose of the check is to detect exactly that kind of drift.
         */
        fun currentlyMappedTo(axis: HeadAxis): OrientationField =
            when (axis) {
                HeadAxis.YAW -> O1
                HeadAxis.PITCH -> O2
                HeadAxis.ROLL -> O3
            }
    }
}

/**
 * One step of the wizard.
 *
 * [axis] is null for the neutral reference pose, which every other pose is measured against.
 */
data class CalibrationPose(
    val axis: HeadAxis?,
    val holdMillis: Long = DEFAULT_HOLD_MILLIS,
) {
    val isNeutral: Boolean get() = axis == null

    val referenceDegrees: Float get() = axis?.referenceDegrees ?: 0f

    companion object {
        /**
         * Long enough for a median to mean something at the stream's 25 Hz — fifty samples —
         * and short enough that a person can hold a pose for it without wobbling.
         */
        const val DEFAULT_HOLD_MILLIS = 2_000L

        /**
         * Neutral first, then one pose per axis.
         *
         * The order is not presentation. Every scale is derived as a difference from the
         * neutral hold, so a run without a usable neutral can produce no scale for any axis
         * — and finding that out at the end would waste the wearer's time.
         */
        val Sequence: List<CalibrationPose> =
            listOf(CalibrationPose(axis = null)) + HeadAxis.entries.map { CalibrationPose(it) }
    }
}

/**
 * What one axis's pose produced.
 *
 * A sealed hierarchy rather than a nullable scale, because the outcomes carry different
 * evidence and only two of them carry a number at all. That asymmetry is the model's whole
 * point: "we measured 0.0143°/unit for yaw" and "we could not measure yaw, because you moved"
 * have to be equally storable, equally displayable and equally exportable, or the rule about
 * not inventing values decays into a comment.
 *
 * Note what is **absent**: no variant below [Measured] and [Suspect] has a scale field. The
 * rule that a skipped or unheld axis produces no number is therefore enforced by the type,
 * not by a check somebody can forget at the fourth call site.
 */
sealed interface AxisVerdict {
    /** A scale, from a held pose whose responding field was the expected one. */
    data class Measured(
        val degreesPerUnit: Float,
        val field: OrientationField,
        val deltaUnits: Int,
    ) : AxisVerdict

    /**
     * A scale outside the plausible range for a human head.
     *
     * Storable only after explicit confirmation, and it keeps saying it is suspect
     * afterwards. [why] carries the arithmetic: "this looks wrong" without the numbers is an
     * opinion, and with them it is something the reader can check.
     */
    data class Suspect(
        val degreesPerUnit: Float,
        val field: OrientationField,
        val deltaUnits: Int,
        val why: String,
        val confirmed: Boolean = false,
    ) : AxisVerdict

    /** The pose moved a field other than the one currently mapped to this axis. */
    data class Mismatched(
        val expectedField: OrientationField,
        val respondingField: OrientationField,
        val deltaUnits: Int,
    ) : AxisVerdict

    /** Two or more fields responded comparably. Reported, never resolved by taking the larger. */
    data class Inconclusive(
        val contenders: Set<OrientationField>,
    ) : AxisVerdict

    /**
     * The fields do not vary independently, so a per-axis scale is not the right model at
     * all.
     *
     * On the evidence recorded in `docs/protocol-research.md` this is the **expected**
     * verdict on current hardware, not an edge case. It carries the whole response set
     * because that set is the measurement worth having — see the spec's "Why This Exists".
     */
    data class CrossCoupled(
        val responses: Map<OrientationField, Int>,
    ) : AxisVerdict

    /** No segment stayed within tolerance for long enough. */
    data class NotHeld(
        val reason: String,
    ) : AxisVerdict

    /** The wearer chose to skip this pose. */
    data object Skipped : AxisVerdict

    /** Never attempted. The state a fresh install is in. */
    data object Uncalibrated : AxisVerdict
}

/**
 * One axis's outcome, with where it came from.
 */
data class AxisCalibration(
    val axis: HeadAxis,
    val verdict: AxisVerdict = AxisVerdict.Uncalibrated,
    val measuredAtEpochMillis: Long = 0L,
) {
    /**
     * The scale, if and only if the verdict permits one — null otherwise.
     *
     * The single place that decides when a derived number may be used. A rule about when a
     * value is allowed is exactly the rule that gets forgotten once it is spread across
     * readers, so there is one reader and everything else asks it.
     */
    val appliedScale: Float?
        get() =
            when (val v = verdict) {
                is AxisVerdict.Measured -> v.degreesPerUnit
                is AxisVerdict.Suspect -> v.degreesPerUnit.takeIf { v.confirmed }
                else -> null
            }

    /** Which field responded, where anything did. Present even on a mismatch — that is the evidence. */
    val respondingField: OrientationField?
        get() =
            when (val v = verdict) {
                is AxisVerdict.Measured -> v.field
                is AxisVerdict.Suspect -> v.field
                is AxisVerdict.Mismatched -> v.respondingField
                else -> null
            }
}

/**
 * The set of axis calibrations belonging to one accessory model.
 *
 * Keyed by **model, not by address**. Two AirPods Pro 3 report the same units; an AirPods
 * Pro 2 and a Powerbeats Pro 2 do not necessarily, and applying one's constants to the other
 * would be inventing a measurement. `PodModel` is an enum, so the key survives firmware
 * updates and re-pairings in a way an address does not.
 */
data class HeadCalibration(
    val model: PodModel,
    val axes: Map<HeadAxis, AxisCalibration>,
    val measuredAtEpochMillis: Long = 0L,
) {
    fun forAxis(axis: HeadAxis): AxisCalibration = axes[axis] ?: AxisCalibration(axis)

    /** True when at least one axis carries a usable scale. */
    val hasAnyScale: Boolean get() = HeadAxis.entries.any { forAxis(it).appliedScale != null }

    companion object {
        fun uncalibrated(model: PodModel): HeadCalibration =
            HeadCalibration(
                model = model,
                axes = HeadAxis.entries.associateWith { AxisCalibration(it) },
            )
    }
}
