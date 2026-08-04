package io.github.andrewkomkov.greenpods.core.data.health

import io.github.andrewkomkov.greenpods.core.model.HeartRateReading

/**
 * One aligned window's worth of trusted readings, with the identity that makes it
 * idempotent in the health store.
 */
data class HeartRateBatch(
    /** The wall-clock-aligned start of the window, in epoch seconds. */
    val windowStartEpochSeconds: Long,
    /** `greenpods:hr:<windowStartEpochSeconds>` — deterministic in the window (R-6). */
    val clientRecordId: String,
    /** Rises on every flush, so the newest view of a window always wins. */
    val clientRecordVersion: Long,
    val samples: List<HeartRateReading>,
) {
    val startEpochMillis: Long get() = samples.minOf(HeartRateReading::measuredAtEpochMillis)

    /**
     * The last sample plus a second: a record covering a single instant is a record with
     * no duration, and health apps render those inconsistently.
     */
    val endEpochMillis: Long
        get() = samples.maxOf(HeartRateReading::measuredAtEpochMillis) + MILLIS_PER_SECOND

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * Groups trusted readings into one record per aligned 60-second window.
 *
 * One record per reading would be 3,600 rows an hour, each with its own identity, for
 * data that is a series by nature. One record per *session* would be worse: FR-019 wants
 * a reading stored once even if sensing is interrupted and resumed, and a session-derived
 * id changes every time sensing restarts.
 *
 * **Alignment to the wall clock is what makes the id deterministic.** The same second
 * always lands in the same window, so a session that stops and resumes inside a minute
 * recomputes the same `clientRecordId` and *updates* that record instead of writing a
 * second one beside it.
 *
 * **The version is a flush counter, not a sample count.** Health Connect applies an
 * update only when the version rises, and two flushes of one window can carry the same
 * number of samples — a session that stopped after 30 seconds and a later one covering
 * the other 30 both hold 30 of them. A count would leave whichever landed first, and say
 * nothing about it (SC-009).
 *
 * Pure, apart from the clock it is given for versioning.
 */
class HeartRateBatcher(
    private val windowSeconds: Long = WINDOW_SECONDS,
    /**
     * Supplies the version.
     *
     * Wall-clock milliseconds rather than a counter starting at zero, because a counter
     * that resets when the process does would stop rising exactly when it matters: after
     * a restart, an update to a window written before it would be silently ignored. The
     * value is forced strictly upward, so two flushes in the same millisecond still
     * differ.
     */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var windowStart: Long? = null
    private val buffer = mutableListOf<HeartRateReading>()
    private var lastVersion = 0L

    /** Readings held for the window in progress. Only interesting to tests and to `hr status`. */
    val pendingCount: Int get() = buffer.size

    /**
     * Takes one trusted reading.
     *
     * Returns the previous window's batch when this reading opens a new one, and null
     * otherwise. A reading that arrives out of order into an already-flushed window
     * simply starts that window again — which is correct, because the id is the same and
     * the write is an update.
     */
    fun add(reading: HeartRateReading): HeartRateBatch? {
        val window = windowOf(reading.measuredAtEpochMillis)
        val current = windowStart

        if (current != null && window != current) {
            val completed = buildBatch(current)
            buffer.clear()
            windowStart = window
            buffer += reading
            return completed
        }

        windowStart = window
        buffer += reading
        return null
    }

    /**
     * Emits the window in progress, however partial.
     *
     * A session ending at 00:40 must not discard forty seconds of readings waiting for a
     * minute boundary that will not arrive. Because the id comes from the window and not
     * from the flush, that partial write is *updated* rather than duplicated if sensing
     * resumes inside the same minute.
     */
    fun flush(): HeartRateBatch? {
        val current = windowStart ?: return null
        if (buffer.isEmpty()) return null
        val batch = buildBatch(current)
        buffer.clear()
        windowStart = null
        return batch
    }

    /** Drops everything held without emitting it. For a session that is being abandoned. */
    fun reset() {
        buffer.clear()
        windowStart = null
    }

    private fun buildBatch(window: Long): HeartRateBatch {
        lastVersion = maxOf(lastVersion + 1, clock())
        return HeartRateBatch(
            windowStartEpochSeconds = window,
            clientRecordId = "$CLIENT_RECORD_PREFIX$window",
            clientRecordVersion = lastVersion,
            samples = buffer.toList(),
        )
    }

    private fun windowOf(epochMillis: Long): Long =
        Math.floorDiv(epochMillis, MILLIS_PER_SECOND).let { seconds ->
            Math.floorDiv(seconds, windowSeconds) * windowSeconds
        }

    companion object {
        const val WINDOW_SECONDS = 60L
        const val CLIENT_RECORD_PREFIX = "greenpods:hr:"

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
