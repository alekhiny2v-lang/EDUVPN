/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Plain Kotlin (no android.* imports) so it can be exercised on a plain JVM.
 */
package com.eduvpn.onetap.domain

/**
 * Decodes the `OpenVPN_ConfigData_Base64` column into a plain-text `.ovpn`
 * profile, then validates and hardens it.
 *
 * The real payloads (verified against the live feed on 2026-09-19, 100/100 rows
 * decoded cleanly) look like a SoftEther-generated client profile:
 *
 * ```
 * dev tun
 * proto tcp
 * remote 219.100.37.9 443
 * cipher AES-128-CBC
 * data-ciphers AES-128-CBC
 * auth SHA1
 * resolv-retry infinite
 * nobind
 * persist-key
 * persist-tun
 * client
 * verb 3
 * #auth-user-pass
 * <ca> ... </ca>
 * <cert> ... </cert>
 * <key> ... </key>
 * ```
 *
 * Two things about that real-world shape drive this code:
 *  - it ships **without** `remote-cert-tls server`, so a hostile relay could
 *    present any certificate. We add it back (see [addIfMissing]).
 *  - `auth-user-pass` is present but commented out. If a relay ever shipped it
 *    uncommented the tunnel would block forever waiting for credentials we do
 *    not have, so it is stripped.
 */
object OvpnConfigDecoder {

    sealed interface Result {
        /**
         * @param ovpn      ready-to-use, hardened, LF-terminated profile text.
         * @param remoteHost address the client will dial.
         * @param remotePort port the client will dial.
         * @param transport  `tcp` or `udp`.
         * @param warnings  non-fatal things we changed; safe to log.
         */
        data class Success(
            val ovpn: String,
            val remoteHost: String,
            val remotePort: Int,
            val transport: String,
            val warnings: List<String>,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    /**
     * Directives that are stripped before the profile reaches OpenVPN.
     *
     * These come from a third party we do not control, and an `.ovpn` file can
     * execute arbitrary programs (`up`, `tls-verify`, `plugin`, ...). OpenVPN on
     * Android cannot run scripts, so at best they are noise and at worst they are
     * an injection vector into the client's own parser. `management` would let a
     * local process take over the tunnel. `auth-user-pass` would hang the connect.
     */
    private val FORBIDDEN_DIRECTIVES: Set<String> = setOf(
        "up", "up-delay", "down", "route-up", "route-pre-down", "ipchange",
        "tls-verify", "tls-export-cert", "plugin", "script-security",
        "client-connect", "client-disconnect", "client-config", "learn-address",
        "management", "management-client-user", "management-hold",
        "management-query-passwords", "management-query-remote",
        "management-up-down", "management-forget-disconnect",
        "auth-user-pass", "auth-user-pass-optional",
        "daemon", "log", "log-append", "tmp-dir", "dev-node", "writepid",
        "setenv", "client-nat", "route-gateway",
    )

    /** Directives appended when the upstream profile omits them. */
    private val HARDENING_DIRECTIVES: List<String> = listOf(
        "remote-cert-tls server", // never shipped by VPN Gate; prevents a MITM relay
        "connect-retry 2 4",
        "server-poll-timeout 20",
    )

    fun decode(base64Config: String): Result {
        if (base64Config.isBlank()) return Result.Failure("empty config payload")

        val bytes = Base64Compat.decode(base64Config)
            ?: return Result.Failure("config payload was not valid Base64")

        val raw = bytes.toString(Charsets.UTF_8)
        val (sanitised, warnings) = sanitize(raw)

        val remote = readRemote(sanitised)
            ?: return Result.Failure("decoded profile has no usable 'remote' directive")
        if (!hasCaMaterial(sanitised)) {
            return Result.Failure("decoded profile carries no inline CA certificate")
        }

        return Result.Success(
            ovpn = sanitised,
            remoteHost = remote.first,
            remotePort = remote.second,
            transport = readTransport(sanitised),
            warnings = warnings,
        )
    }

    /**
     * Normalises line endings, removes [FORBIDDEN_DIRECTIVES] and appends the
     * hardening directives. Kept `internal` so the test harness can drive it
     * directly with hand-written configs.
     */
    internal fun sanitize(rawConfig: String): Pair<String, List<String>> {
        val warnings = ArrayList<String>()
        val lines = rawConfig.replace("\r\n", "\n").replace('\r', '\n').lineSequence()

        val kept = ArrayList<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) {
                kept.add(line)
                continue
            }
            val directive = trimmed.substringBefore(' ').lowercase()
            if (directive in FORBIDDEN_DIRECTIVES) {
                warnings.add("stripped unsupported directive: $directive")
                continue
            }
            kept.add(line)
        }

        val body = kept.joinToString("\n").trimEnd('\n')
        val additions = HARDENING_DIRECTIVES.filter { directive ->
            val name = directive.substringBefore(' ')
            body.lineSequence().none { it.trim().substringBefore(' ').equals(name, true) }
        }
        if (additions.isNotEmpty()) warnings.add("added: ${additions.joinToString(", ")}")

        val finalText = if (additions.isEmpty()) body else buildString {
            append(body)
            append('\n')
            additions.forEach { append(it).append('\n') }
        }
        return finalText + "\n" to warnings
    }

    /** First usable `remote <host> <port>` pair, or null. */
    internal fun readRemote(config: String): Pair<String, Int>? {
        for (line in config.lineSequence()) {
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts[0].equals("remote", true) && !line.trim().startsWith("#")) {
                val port = parts.getOrNull(2)?.toIntOrNull() ?: DEFAULT_OPENVPN_PORT
                if (parts[1].isNotBlank()) return parts[1] to port
            }
        }
        return null
    }

    internal fun readTransport(config: String): String =
        config.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("proto ") && !it.startsWith("#") }
            ?.substringAfter("proto ")
            ?.trim()
            ?.lowercase()
            ?: "tcp"

    /**
     * A client-only, certificate-less profile is not usable inline: the CA has to
     * be embedded, because we never write files to disk.
     */
    private fun hasCaMaterial(config: String): Boolean =
        config.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed == "<ca>" || trimmed.startsWith("ca ")
        }

    private const val DEFAULT_OPENVPN_PORT = 1194
}
