/*
 * EDUVPN - one-tap OpenVPN client.
 */
package com.eduvpn.onetap

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eduvpn.onetap.data.remote.VpnGateApi
import com.eduvpn.onetap.domain.OvpnConfigDecoder
import com.eduvpn.onetap.pipeline.OneTapPipeline
import com.eduvpn.onetap.pipeline.PipelineResult
import com.eduvpn.onetap.pipeline.PreparedConnection
import com.eduvpn.onetap.ui.UiState
import com.eduvpn.onetap.vpn.IcsOpenVpnController
import com.eduvpn.onetap.vpn.VpnController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the one-tap flow and the UI state.
 *
 * It is a [AndroidViewModel] rather than a plain ViewModel because the OpenVPN
 * controller needs a [android.content.Context]; it holds the *application*
 * context only, so it does not leak the Activity.
 *
 * State is exposed as a single immutable [UiState] so the screen can be rebuilt
 * from scratch (rotation, process restart) without any extra bookkeeping.
 */
class OneTapViewModel(application: Application) : AndroidViewModel(application) {

    private val api = VpnGateApi()
    private val pipeline = OneTapPipeline(api)
    private val controller: VpnController = IcsOpenVpnController(application)

    private val _uiState = MutableStateFlow(UiState.idle())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The connection currently driving the tunnel, if any. */
    private var active: PreparedConnection? = null

    /** Relays already tried this session, so a retry never picks the same host. */
    private val triedHosts = LinkedHashSet<String>()

    /**
     * Remaining candidates for the current tap, in ranked order. These are still
     * raw relays - each one is decoded only if the relay ahead of it fails.
     */
    private var fallbackQueue: ArrayDeque<com.eduvpn.onetap.data.model.VpnGateServer> = ArrayDeque()

    private val engineListener = VpnController.StateListener { state, detail ->
        // The engine calls back on its own management thread.
        viewModelScope.launch { handleTunnelState(state, detail) }
    }

    init {
        controller.stateListener = engineListener
        controller.attach()
        // Reflect whatever the tunnel is already doing (e.g. after a rotation).
        syncWithEngine()
    }

    override fun onCleared() {
        controller.detach()
        super.onCleared()
    }

    // ------------------------------------------------------------- public actions

    /**
     * Entry point for the connect button. Runs
     * fetch -> parse -> rank -> decode -> hand to OpenVPN.
     *
     * The caller must have already resolved `VpnService.prepare`; see
     * [MainActivity.onConnectClicked].
     */
    fun connect() {
        if (_uiState.value.phase == UiState.Phase.CONNECTED) return

        triedHosts.clear()
        fallbackQueue.clear()
        _uiState.value = UiState.fetching()

        viewModelScope.launch {
            _uiState.value = UiState.selecting()
            when (val result = pipeline.prepareConnection(excludeHosts = emptySet())) {
                is PipelineResult.Failure -> {
                    Log.w(TAG, "pipeline failed at ${result.stage}: ${result.message}")
                    _uiState.value = UiState.error(stageMessage(result))
                }
                is PipelineResult.Success -> startPrepared(result.connection)
            }
        }
    }

    /** Entry point for the disconnect button. */
    fun disconnect() {
        fallbackQueue.clear()
        _uiState.value = _uiState.value.copy(
            statusText = UiState.STATUS_DISCONNECTING,
            buttonEnabled = false,
            busy = true,
        )
        controller.stop()
        // The engine reports LEVEL_NOTCONNECTED; if it does not, fall back after
        // a short grace period so the button never sticks in "Disconnecting…".
        viewModelScope.launch {
            kotlinx.coroutines.delay(DISCONNECT_GRACE_MS)
            if (!controller.isTunnelActive()) {
                active = null
                _uiState.value = UiState.idle()
            }
        }
    }

    /** Called after the system VPN consent dialog is dismissed. */
    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) connect() else _uiState.value = UiState.error(MSG_PERMISSION_DENIED)
    }

    // ------------------------------------------------------------------ internals

    private fun startPrepared(connection: PreparedConnection) {
        active = connection
        triedHosts.add(connection.server.hostName)
        fallbackQueue = ArrayDeque(connection.fallbacks)
        _uiState.value = UiState.connecting(connection)

        if (!controller.start(connection)) {
            // The controller already reported FAILED through the listener.
            return
        }
        // Guard against a relay that accepts the TCP handshake and then goes
        // silent: give up and move to the next candidate.
        viewModelScope.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            val current = _uiState.value
            if (current.phase == UiState.Phase.CONNECTING) {
                Log.w(TAG, "no tunnel state after ${CONNECT_TIMEOUT_MS}ms, trying next relay")
                tryNextFallback("relay did not respond in time")
            }
        }
    }

    /** Decodes and dials the next ranked relay, or reports failure. */
    private fun tryNextFallback(reason: String) {
        val parent = active
        while (fallbackQueue.isNotEmpty()) {
            val next = fallbackQueue.removeFirst()
            if (next.hostName in triedHosts) continue
            val decoded = OvpnConfigDecoder.decode(next.configBase64)
            if (decoded !is OvpnConfigDecoder.Result.Success) continue
            if (parent == null) break

            Log.i(TAG, "previous relay failed ($reason), trying ${next.hostName}")
            startPrepared(
                parent.copy(
                    server = next,
                    profile = decoded,
                    fallbacks = fallbackQueue.toList(),
                ),
            )
            return
        }
        active = null
        _uiState.value = UiState.error(
            "No relay could be reached ($reason). Try again in a moment.",
        )
    }

    /** Maps engine callbacks onto the UI state. */
    private fun handleTunnelState(state: VpnController.State, detail: String) {
        val connection = active
        _uiState.value = when (state) {
            VpnController.State.CONNECTED ->
                if (connection != null) UiState.connected(connection)
                else _uiState.value.copy(phase = UiState.Phase.CONNECTED, statusText = UiState.STATUS_CONNECTED)

            VpnController.State.CONNECTING ->
                if (connection != null) UiState.connecting(connection)
                else _uiState.value.copy(phase = UiState.Phase.CONNECTING, busy = true)

            VpnController.State.PAUSED ->
                _uiState.value.copy(statusText = "Paused", busy = false, buttonEnabled = true)

            VpnController.State.DISCONNECTED -> {
                // A drop while we were still connecting means this relay is bad:
                // move down the queue instead of surfacing an error immediately.
                if (_uiState.value.phase == UiState.Phase.CONNECTING && fallbackQueue.isNotEmpty()) {
                    tryNextFallback(detail)
                    return
                }
                active = null
                UiState.idle()
            }

            VpnController.State.FAILED -> {
                if (fallbackQueue.isNotEmpty()) {
                    tryNextFallback(detail)
                    return
                }
                active = null
                UiState.error(detail.ifBlank { "Connection failed" })
            }
        }
    }

    private fun syncWithEngine() {
        if (!controller.isTunnelActive()) return
        _uiState.value = UiState(
            phase = UiState.Phase.CONNECTED,
            statusText = UiState.STATUS_CONNECTED,
            detailLine = controller.lastLogLine(),
        )
    }

    private fun stageMessage(failure: PipelineResult.Failure): String = when (failure.stage) {
        com.eduvpn.onetap.pipeline.PipelineStage.FETCH -> failure.message
        com.eduvpn.onetap.pipeline.PipelineStage.PARSE ->
            "The server list could not be read. ${failure.message}"
        com.eduvpn.onetap.pipeline.PipelineStage.SELECT ->
            "Every listed relay looks offline. ${failure.message}"
        com.eduvpn.onetap.pipeline.PipelineStage.DECODE ->
            "No usable VPN profile in the list. ${failure.message}"
    }

    private companion object {
        const val TAG = "EDUVPN.vm"
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val DISCONNECT_GRACE_MS = 4_000L
        const val MSG_PERMISSION_DENIED = "VPN permission is required to connect."
    }
}
