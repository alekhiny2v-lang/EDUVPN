/*
 * EDUVPN - one-tap OpenVPN client.
 */
package com.eduvpn.onetap

import de.blinkt.openvpn.core.ICSOpenVPNApplication

/**
 * Application entry point.
 *
 * The vendored ics-openvpn module ships its own `ICSOpenVPNApplication`, which
 * initialises the log cache and the locale helper the engine depends on. An
 * app can only have one `android:name` class, so this one subclasses it instead
 * of running next to it - dropping it means OpenVPN logs are never flushed and
 * `VpnStatus.getLastCleanLogMessage()` can return stale text.
 *
 * If you vendor a build of ics-openvpn that has no Application class, replace
 * the superclass with `android.app.Application` and delete the
 * `super.onCreate()` line.
 */
class EdUvpnApplication : ICSOpenVPNApplication() {

    override fun onCreate() {
        super.onCreate()
    }
}
