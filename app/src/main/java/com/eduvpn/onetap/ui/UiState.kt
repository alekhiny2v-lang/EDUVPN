/*
 * EDUVPN - one-tap OpenVPN client.
 */
package com.eduvpn.onetap.ui

import com.eduvpn.onetap.pipeline.PreparedConnection

/**
 * Everything the single screen needs to render. Immutable, so it can be emitted
 * from a coroutine and read on the main thread without locking.
 */
data class UiState(
    val phase: Phase = Phase.IDLE,
    val statusText: String = STATUS_IDLE,
    val countryLine: String = "",
    val detailLine: String = "",
    val buttonEnabled: Boolean = true,
    val busy: Boolean = false,
) {

    /** Drives the button colour, label and the progress indicator. */
    enum class Phase { IDLE, FETCHING, CONNECTING, CONNECTED, ERROR }

    val isConnected: Boolean get() = phase == Phase.CONNECTED

    companion object {
        const val STATUS_IDLE: String = "Idle"
        const val STATUS_FETCHING: String = "Fetching best server…"
        const val STATUS_SELECTING: String = "Picking the fastest relay…"
        const val STATUS_CONNECTING: String = "Connecting…"
        const val STATUS_CONNECTED: String = "Connected"
        const val STATUS_DISCONNECTING: String = "Disconnecting…"

        fun idle() = UiState()

        fun fetching() = UiState(
            phase = Phase.FETCHING,
            statusText = STATUS_FETCHING,
            buttonEnabled = false,
            busy = true,
        )

        fun selecting() = fetching().copy(statusText = STATUS_SELECTING)

        fun connecting(connection: PreparedConnection) = UiState(
            phase = Phase.CONNECTING,
            statusText = STATUS_CONNECTING,
            countryLine = connection.server.displayCountry,
            detailLine = "${connection.server.pingMs} ms · " +
                "${connection.profile.remoteHost}:${connection.profile.remotePort} " +
                "· ${connection.profile.transport.uppercase()}",
            buttonEnabled = false,
            busy = true,
        )

        fun connected(connection: PreparedConnection) = UiState(
            phase = Phase.CONNECTED,
            statusText = STATUS_CONNECTED,
            countryLine = connection.server.displayCountry,
            detailLine = "${connection.server.pingMs} ms · " +
                "${connection.profile.remoteHost}:${connection.profile.remotePort}",
            buttonEnabled = true,
        )

        fun error(message: String) = UiState(
            phase = Phase.ERROR,
            statusText = message,
            buttonEnabled = true,
        )
    }
}
