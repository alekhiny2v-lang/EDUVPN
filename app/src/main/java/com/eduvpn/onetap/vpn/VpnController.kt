/*
 * EDUVPN - one-tap OpenVPN client.
 */
package com.eduvpn.onetap.vpn

import com.eduvpn.onetap.pipeline.PreparedConnection

/**
 * Abstraction over the OpenVPN engine.
 *
 * Keeping the engine behind an interface is what lets the app survive the two
 * very different ways of getting OpenVPN into an Android build:
 *  - [IcsOpenVpnController]: the ics-openvpn sources vendored as a library
 *    module. Full control, in-process, no other app required.
 *  - the AIDL remote API of an installed "OpenVPN for Android" (see
 *    `alternative/aidl-integration/`). No NDK build, but needs the other app.
 *
 * Everything the UI cares about is funnelled through [State], so the Activity
 * never touches OpenVPN types.
 */
interface VpnController {

    /** Coarse tunnel state, derived from the engine's own connection levels. */
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, PAUSED, FAILED }

    /** Register for state changes. Safe to call once per Activity lifecycle. */
    fun attach()

    /** Unregister. Must be paired with [attach]. */
    fun detach()

    /**
     * Turns a [PreparedConnection] into a live tunnel.
     *
     * @return false when the profile could not even be handed to the engine;
     *         connection *progress* still arrives through [StateListener].
     */
    fun start(connection: PreparedConnection): Boolean

    /** Tears the tunnel down. Idempotent. */
    fun stop()

    fun isTunnelActive(): Boolean

    /** Last engine log line, for the debug/detail row in the UI. */
    fun lastLogLine(): String

    fun interface StateListener {
        fun onTunnelStateChanged(state: State, detail: String)
    }

    /** Convenience so the controller can be constructed with a lambda. */
    var stateListener: StateListener?
}
