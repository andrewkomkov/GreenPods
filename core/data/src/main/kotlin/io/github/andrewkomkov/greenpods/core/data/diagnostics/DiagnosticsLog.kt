package io.github.andrewkomkov.greenpods.core.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What a diagnostic line is about, so the UI can group and colour it. */
enum class DiagnosticCategory {
    /** A transport probe and its outcome. */
    TRANSPORT,

    /** A packet the decoders could not interpret. This is the interesting one. */
    UNKNOWN_TRAFFIC,

    /** Scanner lifecycle: started, stopped, failed, permission missing. */
    SCAN,

    /** An accessory whose model id is not in the registry. */
    UNKNOWN_DEVICE,

    /** Update checks. */
    UPDATE,
}

data class DiagnosticEvent(
    val atEpochMillis: Long,
    val category: DiagnosticCategory,
    val message: String,
    /** Hex dump or stack detail. Shown collapsed. */
    val detail: String = "",
)

/**
 * A bounded, in-memory record of everything GreenPods did not understand.
 *
 * This exists because the protocol is still being reverse-engineered: an unrecognised
 * control command is not noise to be swallowed, it is the next thing to decode. The
 * log is the mechanism that turns a user's unusual firmware into a bug report worth
 * having.
 *
 * Deliberately not persisted — it can contain device addresses and serial numbers, and
 * its value is entirely in the current session.
 */
class DiagnosticsLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

    /** Newest first. */
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun record(
        category: DiagnosticCategory,
        message: String,
        detail: String = "",
    ) {
        val event = DiagnosticEvent(clock(), category, message, detail)
        _events.update { current -> (listOf(event) + current).take(capacity) }
    }

    fun clear() {
        _events.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200

        /** Renders bytes the way every protocol note in this project writes them. */
        fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
    }
}
