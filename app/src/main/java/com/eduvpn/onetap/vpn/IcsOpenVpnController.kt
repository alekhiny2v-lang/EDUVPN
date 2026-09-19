/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * This file is the only place that talks to the ics-openvpn library.
 * See docs/IMPLEMENTATION_GUIDE.md step 2 for how the library module is added;
 * the dependency the task brief assumed (`de.blinkt.openvpn:ics-openvpn`) is not
 * published to Maven Central, so the sources are vendored instead.
 */
package com.eduvpn.onetap.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.eduvpn.onetap.pipeline.PreparedConnection
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/**
 * Drives a vendored ics-openvpn (OpenVPN for Android, v0.7.65) in-process.
 *
 * Connect path, in the order the library actually requires it:
 *  1. [ConfigParser.parseConfig] + [ConfigParser.convertProfile] turn the
 *     decoded `.ovpn` text into a [VpnProfile].
 *  2. [ProfileManager.setTemporaryProfile] persists it and bumps its version.
 *     This has to happen *before* the start intent is built, because
 *     `VpnProfile.getStartServiceIntent` stamps the current version into the
 *     intent and `OpenVPNService.fetchVPNProfile` waits for that version.
 *  3. [VPNLaunchHelper.startOpenVpn] raises the foreground service.
 *
 * The system VPN consent dialog is handled by the Activity (`VpnService.prepare`)
 * rather than here, because the library's own fallback for a missing permission
 * lives in its `ui` source set - which is not part of a `main`-only library
 * module - and only posts a notification the user may never see.
 */
class IcsOpenVpnController(
    private val context: Context,
) : VpnController {

    override var stateListener: VpnController.StateListener? = null

    private var boundService: IOpenVPNServiceInternal? = null
    private var connection: ServiceConnection? = null

    /**
     * Bridges the library's callback into our [VpnController.State].
     *
     * The Java signature declares `Intent Intent` but
     * `VpnStatus.updateStateString(state, msg, resid, level)` forwards `null`,
     * so the parameter must be nullable here or Kotlin throws on every
     * state change that does not carry an intent.
     */
    private val engineListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String,
            logmessage: String,
            localizedResId: Int,
            level: ConnectionStatus,
            intent: Intent?,
        ) {
            val mapped = when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> VpnController.State.CONNECTED
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                ConnectionStatus.LEVEL_START,
                -> VpnController.State.CONNECTING
                ConnectionStatus.LEVEL_VPNPAUSED -> VpnController.State.PAUSED
                // VPN Gate relays are certificate-only; if the engine ever asks
                // for credentials there is nothing this app can supply.
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> VpnController.State.FAILED
                ConnectionStatus.LEVEL_AUTH_FAILED,
                ConnectionStatus.LEVEL_NONETWORK,
                ConnectionStatus.LEVEL_NOTCONNECTED,
                ConnectionStatus.UNKNOWN_LEVEL,
                -> VpnController.State.DISCONNECTED
            }
            val detail = logmessage.ifBlank { state }
            stateListener?.onTunnelStateChanged(mapped, detail)
        }

        override fun setConnectedVPN(uuid: String?) {
            Log.d(TAG, "engine connected profile uuid=$uuid")
        }
    }

    override fun attach() {
        VpnStatus.addStateListener(engineListener)
        bindToService()
    }

    override fun detach() {
        VpnStatus.removeStateListener(engineListener)
        unbindFromService()
    }

    override fun start(connection: PreparedConnection): Boolean = try {
        val profile = buildProfile(connection) ?: return false

        // Order matters: persist first, then build the start intent.
        ProfileManager.setTemporaryProfile(context, profile)
        VPNLaunchHelper.startOpenVpn(profile, context, START_REASON, true)
        stateListener?.onTunnelStateChanged(VpnController.State.CONNECTING, START_REASON)
        true
    } catch (error: Exception) {
        Log.e(TAG, "could not hand the profile to OpenVPN", error)
        stateListener?.onTunnelStateChanged(
            VpnController.State.FAILED,
            error.message ?: "OpenVPN rejected the profile",
        )
        false
    }

    override fun stop() {
        val service = boundService
        if (service == null) {
            // Not bound yet: ask the service to stop and bind on demand.
            bindToService(stopOnConnect = true)
            return
        }
        runCatching { service.stopVPN(false) }
            .onFailure { Log.w(TAG, "stopVPN failed", it) }
    }

    override fun isTunnelActive(): Boolean = VpnStatus.isVPNActive()

    override fun lastLogLine(): String = VpnStatus.getLastCleanLogMessage(context)

    // ---------------------------------------------------------------- internals

    /**
     * Parses the hardened `.ovpn` text into the library's profile type.
     * Returns null (after reporting) when the profile cannot be represented.
     */
    private fun buildProfile(connection: PreparedConnection): VpnProfile? {
        val parser = ConfigParser()
        return try {
            StringReader(connection.profile.ovpn).use { reader -> parser.parseConfig(reader) }
            parser.convertProfile().apply {
                mName = profileName(connection)
            }
        } catch (error: ConfigParser.ConfigParseError) {
            Log.e(TAG, "OpenVPN config rejected by ConfigParser", error)
            stateListener?.onTunnelStateChanged(
                VpnController.State.FAILED,
                "Config rejected: ${error.message}",
            )
            null
        } catch (error: Exception) {
            Log.e(TAG, "failed to build profile", error)
            stateListener?.onTunnelStateChanged(
                VpnController.State.FAILED,
                error.message ?: "Profile build failed",
            )
            null
        }
    }

    private fun profileName(connection: PreparedConnection): String =
        "EDUVPN ${connection.server.displayCountry} (${connection.server.pingMs} ms)"

    /**
     * `OpenVPNService.onBind` only hands out its control binder when the intent
     * carries the `START_SERVICE` action - binding with a bare intent silently
     * returns null and `stopVPN` becomes unreachable.
     */
    private fun bindToService(stopOnConnect: Boolean = false) {
        if (connection != null) return
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = IOpenVPNServiceInternal.Stub.asInterface(binder)
                if (stopOnConnect) {
                    runCatching { boundService?.stopVPN(false) }
                    unbindFromService()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        val intent = Intent(context, OpenVPNService::class.java)
            .setAction(OpenVPNService.START_SERVICE)
        val bound = runCatching { context.bindService(intent, serviceConnection, 0) }
            .getOrDefault(false)
        connection = serviceConnection.takeIf { bound }
        if (!bound) Log.d(TAG, "OpenVPNService is not running; nothing to bind to")
    }

    private fun unbindFromService() {
        val serviceConnection = connection ?: return
        connection = null
        boundService = null
        runCatching { context.unbindService(serviceConnection) }
    }

    private companion object {
        const val TAG = "EDUVPN.vpn"
        const val START_REASON = "EDUVPN one-tap connect"
    }
}
