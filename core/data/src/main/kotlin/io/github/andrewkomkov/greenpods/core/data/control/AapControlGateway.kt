package io.github.andrewkomkov.greenpods.core.data.control

import android.annotation.SuppressLint
import android.content.Context
import io.github.andrewkomkov.greenpods.core.bluetooth.aap.AapSession
import io.github.andrewkomkov.greenpods.core.data.PodRepository
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticCategory
import io.github.andrewkomkov.greenpods.core.data.diagnostics.DiagnosticsLog
import io.github.andrewkomkov.greenpods.core.data.transport.BondedPodResolver
import io.github.andrewkomkov.greenpods.core.model.NoiseControlMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * The real control path: an [AapSession] over an L2CAP channel to a bonded accessory.
 *
 * On most phones this never connects, and that is designed for rather than worked
 * around. [connect] reports success as a Boolean, decoded events are folded straight
 * into the repository, and a dropped channel clears the overlay so a stale
 * noise-control mode is never shown as current.
 */
class AapControlGateway(
    context: Context,
    private val repository: PodRepository,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val session: AapSession = AapSession(),
    private val resolver: BondedPodResolver = BondedPodResolver(context),
) : PodControlGateway {
    private var connectedAddress: String? = null
    private var readerJob: Job? = null

    /** True when a channel was opened and the event stream is running. */
    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (connectedAddress == address && readerJob?.isActive == true) return true
        disconnect()

        // The address here comes from an advertisement, which uses a rotating private
        // address — the channel has to be opened to the paired classic address instead.
        val resolution = resolver.resolve(address)
        val device = (resolution as? BondedPodResolver.Resolution.Resolved)?.device ?: return false

        connectedAddress = address
        readerJob =
            scope.launch {
                session
                    .events(device)
                    .catch { error ->
                        diagnostics.record(
                            DiagnosticCategory.TRANSPORT,
                            "Apple protocol channel closed",
                            error.message.orEmpty(),
                        )
                    }.onCompletion { repository.clearOverlay(address) }
                    .collect { event -> repository.onAapEvent(address, event) }
            }
        return true
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        connectedAddress?.let(repository::clearOverlay)
        connectedAddress = null
    }

    override suspend fun setNoiseControlMode(
        address: String,
        mode: NoiseControlMode,
    ): Boolean = withChannel(address) { session.setNoiseControlMode(mode) }

    override suspend fun setAdaptiveNoiseStrength(
        address: String,
        percent: Int,
    ): Boolean = withChannel(address) { session.setAdaptiveNoiseStrength(percent) }

    override suspend fun setConversationalAwareness(
        address: String,
        enabled: Boolean,
    ): Boolean = withChannel(address) { session.setConversationalAwareness(enabled) }

    override suspend fun setListeningModeCycle(
        address: String,
        modes: Set<NoiseControlMode>,
    ): Boolean = withChannel(address) { session.setListeningModeCycle(modes) }

    private suspend fun withChannel(
        address: String,
        write: suspend () -> Boolean,
    ): Boolean {
        if (!connect(address)) return false
        return write()
    }
}
