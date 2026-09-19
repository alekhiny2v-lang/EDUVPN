/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Plain Kotlin. This file deliberately has NO android.* import so the whole
 * data/domain layer can be unit-tested on a plain JVM (see verification/).
 */
package com.eduvpn.onetap.data.model

/**
 * One row of the VPN Gate server list
 * (`https://www.vpngate.net/api/iphone/`).
 *
 * The upstream CSV header (verified against a live snapshot on 2026-09-19,
 * 15 columns, CRLF line endings) is:
 *
 * ```
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
 * Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 * ```
 *
 * @property hostName      Relay host name, e.g. `public-vpn-45`.
 * @property ipAddress     Relay IPv4 address, e.g. `219.100.37.9`.
 * @property score         Operator/reliability score reported by VPN Gate.
 * @property pingMs        ICMP round-trip time in milliseconds as measured by
 *                         VPN Gate. `0` means "not measured / unreachable",
 *                         which is the single most useful "dead server" signal.
 * @property speedBps      Reported throughput in bits per second.
 * @property countryLong   Full country name, e.g. `Korea Republic of`.
 * @property countryShort  ISO-3166 alpha-2 code, e.g. `KR`.
 * @property sessionCount  Number of current VPN sessions (congestion hint).
 * @property logType       Retention window advertised by the operator, e.g. `2weeks`.
 * @property configBase64  Base64 of the ready-to-use `.ovpn` profile.
 */
data class VpnGateServer(
    val hostName: String,
    val ipAddress: String,
    val score: Long,
    val pingMs: Int,
    val speedBps: Long,
    val countryLong: String,
    val countryShort: String,
    val sessionCount: Int,
    val logType: String,
    val configBase64: String,
) {

    /** A relay we can actually dial: it has an address, a profile and a measured RTT. */
    val isReachable: Boolean
        get() = ipAddress.isNotBlank() && pingMs > 0 && configBase64.isNotBlank()

    /** Human label for the UI, e.g. `Japan (JP)`. */
    val displayCountry: String
        get() = when {
            countryLong.isNotBlank() && countryShort.isNotBlank() -> "$countryLong ($countryShort)"
            countryLong.isNotBlank() -> countryLong
            countryShort.isNotBlank() -> countryShort
            else -> UNKNOWN_COUNTRY
        }

    companion object {
        const val UNKNOWN_COUNTRY: String = "Unknown"
    }
}
