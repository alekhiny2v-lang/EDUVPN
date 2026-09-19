/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Plain Kotlin (no android.* imports) so it can be exercised on a plain JVM.
 */
package com.eduvpn.onetap.data.parser

import com.eduvpn.onetap.data.model.VpnGateServer

/**
 * Parser for the VPN Gate "iPhone" CSV feed.
 *
 * Design notes, all of them driven by what the real endpoint actually returns
 * (verified against the live feed on 2026-09-19: 100 rows, every row exactly
 * 15 comma separated fields, CRLF line endings):
 *
 * 1. The payload is framed by marker lines: it starts with `*vpn_servers` and
 *    ends with a line that is just `*`. Both must be skipped.
 * 2. Line endings are CRLF. Splitting on `\n` alone leaves a trailing `\r` on
 *    every field, so the last column silently stops being valid Base64.
 *    [CharSequence.lineSequence] handles `\r\n`, `\n` and `\r`, and each line is
 *    additionally trimmed of `\r` defensively.
 * 3. Column order has changed over the life of this API. Columns are therefore
 *    located **by header name**, and fixed indices are only a fallback for when
 *    the header line is missing or unrecognised.
 * 4. Text columns (`Operator`, `Message`) can legally contain commas, so fields
 *    are split with an RFC 4180 aware splitter rather than `String.split(',')`.
 * 5. The feed is served over plain HTTP and can be truncated mid-flight, so a
 *    malformed line is skipped and counted instead of aborting the whole parse.
 */
object VpnGateCsvParser {

    /** Column count of the current upstream schema. */
    const val EXPECTED_COLUMN_COUNT: Int = 15

    /** Canonical (fallback) indices, used when the header row is absent. */
    private const val IDX_HOSTNAME = 0
    private const val IDX_IP = 1
    private const val IDX_SCORE = 2
    private const val IDX_PING = 3
    private const val IDX_SPEED = 4
    private const val IDX_COUNTRY_LONG = 5
    private const val IDX_COUNTRY_SHORT = 6
    private const val IDX_SESSIONS = 7
    private const val IDX_LOG_TYPE = 11
    private const val IDX_CONFIG = 14

    /** Header names, matched case-insensitively and with the leading `#` removed. */
    private val HEADER_NAMES: Map<String, String> = mapOf(
        "hostname" to "hostname",
        "ip" to "ip",
        "ipaddress" to "ip",
        "score" to "score",
        "ping" to "ping",
        "speed" to "speed",
        "countrylong" to "countryLong",
        "countryshort" to "countryShort",
        "numvpnsessions" to "sessions",
        "logtype" to "logType",
        "openvpn_configdata_base64" to "config",
    )

    /** Outcome of a parse: the usable rows plus enough counters to log a problem. */
    data class ParseReport(
        val servers: List<VpnGateServer>,
        val totalDataLines: Int,
        val skippedLines: Int,
        val headerRecognised: Boolean,
    ) {
        val isEmpty: Boolean get() = servers.isEmpty()
    }

    /** Thrown only for a response that is not a server list at all. */
    class InvalidFeedException(message: String) : IllegalArgumentException(message)

    /**
     * Parses a raw VPN Gate CSV response.
     *
     * @throws InvalidFeedException when the payload is empty or contains no
     *         recognisable server rows at all (as opposed to a partially
     *         corrupt feed, which is reported through [ParseReport.skippedLines]).
     */
    fun parse(rawCsv: String): ParseReport {
        val text = rawCsv.stripUtf8Bom()
        if (text.isBlank()) throw InvalidFeedException("Server list response was empty")

        var columns: Map<String, Int>? = null
        var dataLines = 0
        var skipped = 0
        val servers = ArrayList<VpnGateServer>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r', '\n').trim()
            if (line.isEmpty()) continue
            // Marker lines: "*vpn_servers" at the top, "*" at the bottom.
            if (line.startsWith('*')) continue
            if (line.startsWith('#')) {
                if (columns == null) columns = readHeader(line)
                continue
            }

            dataLines++
            val fields = splitCsvLine(line)
            val server = fields.toServer(columns)
            if (server == null) skipped++ else servers.add(server)
        }

        if (servers.isEmpty()) {
            throw InvalidFeedException(
                "No usable server rows in response ($dataLines data line(s), $skipped skipped)",
            )
        }
        return ParseReport(
            servers = servers,
            totalDataLines = dataLines,
            skippedLines = skipped,
            headerRecognised = columns != null,
        )
    }

    // ---------------------------------------------------------------- internals

    private fun readHeader(headerLine: String): Map<String, Int> {
        val map = HashMap<String, Int>(HEADER_NAMES.size)
        splitCsvLine(headerLine).forEachIndexed { index, rawName ->
            val key = rawName.trim().removePrefix("#").trim().lowercase()
            HEADER_NAMES[key]?.let { canonical -> map[canonical] = index }
        }
        // Require at least the two columns we cannot live without.
        return if (map.containsKey("ip") && map.containsKey("config")) map else emptyMap()
    }

    /** Maps a split row onto a [VpnGateServer]; returns null when the row is unusable. */
    private fun List<String>.toServer(columns: Map<String, Int>?): VpnGateServer? {
        val minColumns = if (columns.isNullOrEmpty()) EXPECTED_COLUMN_COUNT else columns.size
        if (size < minColumns) return null

        fun field(key: String, fallbackIndex: Int): String {
            val index = columns?.get(key) ?: fallbackIndex
            return if (index in indices) this[index].trim() else ""
        }

        val ip = field("ip", IDX_IP)
        val config = field("config", IDX_CONFIG)
        // Without an address or a profile the row can never become a connection.
        if (ip.isBlank() || config.isBlank()) return null

        return VpnGateServer(
            hostName = field("hostname", IDX_HOSTNAME),
            ipAddress = ip,
            score = field("score", IDX_SCORE).toLongOrNull() ?: 0L,
            pingMs = field("ping", IDX_PING).toIntOrNull() ?: 0,
            speedBps = field("speed", IDX_SPEED).toLongOrNull() ?: 0L,
            countryLong = field("countryLong", IDX_COUNTRY_LONG),
            countryShort = field("countryShort", IDX_COUNTRY_SHORT),
            sessionCount = field("sessions", IDX_SESSIONS).toIntOrNull() ?: 0,
            logType = field("logType", IDX_LOG_TYPE),
            configBase64 = config,
        )
    }

    /**
     * RFC 4180 field splitter. Handles quoted fields and `""` escaped quotes so
     * that an `Operator` value such as `"Daiyuu Nobori, Japan"` survives intact.
     */
    internal fun splitCsvLine(line: String): List<String> {
        val fields = ArrayList<String>(EXPECTED_COLUMN_COUNT)
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                inQuotes -> when {
                    char == '"' -> {
                        if (index + 1 < line.length && line[index + 1] == '"') {
                            current.append('"') // escaped quote
                            index++
                        } else {
                            inQuotes = false
                        }
                    }
                    else -> current.append(char)
                }
                // A quote only opens a quoted field at the very start of a field.
                char == '"' && current.isEmpty() -> inQuotes = true
                char == ',' -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields.add(current.toString())
        return fields
    }

    /** Removes a UTF-8 byte order mark if the mirror added one. */
    private fun String.stripUtf8Bom(): String =
        if (isNotEmpty() && this[0] == '\uFEFF') substring(1) else this
}
