/*
 * EDUVPN - alternative integration: AIDL remote control.
 *
 * This file is NOT part of the default build. See alternative/aidl-integration/README.md
 * for when to use it and how to wire it in.
 *
 * Trade-off versus the default IcsOpenVpnController:
 *   + no NDK / CMake / swig build, no vendored sources, much smaller APK
 *   + the remote-control AIDL surface is documented by ics-openvpn as usable
 *     from an external app without inheriting its GPL terms
 *   - the user must install "OpenVPN for Android" (de.blinkt.openvpn) separately
 *   - Android 11+ needs a <queries> entry to see that package
 */
package com.eduvpn.onetap.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.eduvpn.onetap.pipeline.PreparedConnection
import de.blinkt.openvpn.api.IOpenVPNAPIService
import de.blinkt.openvpn.api.IOpenVPNStatusCallback

/**
 * Drives an *installed* OpenVPN for Android app over its AIDL remote API.
 *
 * `IOpenVPNAPIService.startVPN(String inlineconfig)` accepts the profile as an
 * inline string, which is exactly what [com.eduvpn.onetap.domain.OvpnConfigDecoder]
 * produces - so the whole fetch/parse/rank/decode pipeline is reused unchanged.
 */
class RemoteOpenVpnController(
    private val context: Context,
) : VpnController {

    override var stateListener: VpnController.StateListener? = null

    private var service: IOpenVPNAPIService? = null

    private val statusCallback = object : IOpenVPNStatusCallback.Stub() {
        /** Called on a binder thread. `level` mirrors ConnectionStatus.name(). */
        override fun newStatus(uuid: String?, state: String?, message: String?, level: String?) {
            val mapped = when (level) {
                "LEVEL_CONNECTED" -> VpnController.State.CONNECTED
                "LEVEL_START",
                "LEVEL_CONNECTING_SERVER_REPLIED",
                "LEVEL_CONNECTING_NO_SERVER_REPLY_YET",
                -> VpnController.State.CONNECTING
                "LEVEL_VPNPAUSED" -> VpnController.State.PAUSED
                "LEVEL_AUTH_FAILED", "LEVEL_NONETWORK", "LEVEL_NOTCONNECTED" ->
                    VpnController.State.DISCONNECTED
                else -> VpnController.State.CONNECTING
            }
            stateListener?.onTunnelStateChanged(mapped, message ?: state ?: "")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IOpenVPNAPIService.Stub.asInterface(binder)
            runCatching { service?.registerStatusCallback(statusCallback) }
                .onFailure { Log.w(TAG, "could not register status callback", it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun attach() {
        // Must match the <queries> entry in the manifest.
        val intent = Intent().setComponent(
            ComponentName(REMOTE_PACKAGE, REMOTE_SERVICE),
        )
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) Log.w(TAG, "OpenVPN for Android is not installed or not visible")
    }

    override fun detach() {
        runCatching { service?.unregisterStatusCallback(statusCallback) }
        service = null
        runCatching { context.unbindService(connection) }
    }

    override fun start(connection: PreparedConnection): Boolean = try {
        val api = service ?: run {
            stateListener?.onTunnelStateChanged(
                VpnController.State.FAILED,
                "OpenVPN for Android is not connected. Install de.blinkt.openvpn.",
            )
            return false
        }
        api.startVPN(connection.profile.ovpn)
        stateListener?.onTunnelStateChanged(VpnController.State.CONNECTING, "starting tunnel")
        true
    } catch (error: RemoteException) {
        Log.e(TAG, "startVPN failed", error)
        stateListener?.onTunnelStateChanged(VpnController.State.FAILED, error.message ?: "startVPN failed")
        false
    }

    override fun stop() {
        runCatching { service?.disconnect() }
            .onFailure { Log.w(TAG, "disconnect failed", it) }
    }

    override fun isTunnelActive(): Boolean = false // the remote app owns this state

    override fun lastLogLine(): String = ""

    private companion object {
        const val TAG = "EDUVPN.remote"
        const val REMOTE_PACKAGE = "de.blinkt.openvpn"
        const val REMOTE_SERVICE = "de.blinkt.openvpn.api.ExternalOpenVPNService"
    }
}
