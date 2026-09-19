/*
 * EDUVPN - JVM verification harness.
 *
 * This is NOT part of the Android build. It compiles the *real* app sources from
 * app/src/main/java/com/eduvpn/onetap/{data,domain,pipeline} together with this
 * file and executes them against a byte-faithful extract of a live VPN Gate
 * response, so the fetch-parse-select-decode pipeline is exercised without an
 * emulator. Run it with:  verification/run_tests.sh
 *
 * Coroutines: OneTapPipeline.prepareConnection() is a suspend function. Rather
 * than pulling kotlinx-coroutines into the harness we drive it with a plain
 * kotlin.coroutines Continuation (runSuspend below), which is enough because the
 * fake source never actually suspends.
 */
package com.eduvpn.onetap.verification

import com.eduvpn.onetap.data.model.VpnGateServer
import com.eduvpn.onetap.data.parser.VpnGateCsvParser
import com.eduvpn.onetap.domain.Base64Compat
import com.eduvpn.onetap.domain.OvpnConfigDecoder
import com.eduvpn.onetap.domain.ServerSelector
import com.eduvpn.onetap.pipeline.FetchOutcome
import com.eduvpn.onetap.pipeline.OneTapPipeline
import com.eduvpn.onetap.pipeline.PipelineResult
import com.eduvpn.onetap.pipeline.PipelineStage
import com.eduvpn.onetap.pipeline.ServerListSource
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// --------------------------------------------------------------------- fixtures

private val FIXTURE_DIR: File = File(
    System.getenv("EDUVPN_FIXTURES") ?: "app/src/test/resources/fixtures",
)

private fun fixture(name: String): String {
    val file = File(FIXTURE_DIR, name)
    require(file.isFile) { "missing fixture ${file.absolutePath}" }
    return file.readText(Charsets.UTF_8)
}

private class FakeSource(private val csv: String) : ServerListSource {
    override suspend fun fetchServerListCsv(): FetchOutcome =
        FetchOutcome.Success(csv, "fixture://$FIXTURE_DIR")
}

private class FailingSource(private val reason: String) : ServerListSource {
    override suspend fun fetchServerListCsv(): FetchOutcome = FetchOutcome.Failure(reason)
}

/** Drives a non-suspending suspend call with stdlib only. */
private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    val continuation = object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    }
    block.startCoroutine(continuation)
    return (outcome ?: error("coroutine did not complete synchronously")).getOrThrow()
}

// ------------------------------------------------------------------- assertions

private class TestFailure(message: String) : AssertionError(message)

private fun check(condition: Boolean, message: () -> String) {
    if (!condition) throw TestFailure(message())
}

private fun <T> assertEquals(expected: T, actual: T, what: String) {
    if (expected != actual) throw TestFailure("$what: expected <$expected> but was <$actual>")
}

private val results = mutableListOf<Pair<String, Result<Unit>>>()

private fun test(name: String, body: () -> Unit) {
    results.add(name to runCatching(body))
}

// ------------------------------------------------------------------------ tests

/** Parser against a real, unmodified extract of the live feed. */
private fun testParserOnRealSample() = test("parser: real live snapshot") {
    val report = VpnGateCsvParser.parse(fixture("vpngate_real_sample.csv"))

    assertEquals(true, report.headerRecognised, "header recognised")
    assertEquals(5, report.servers.size, "parsed row count")
    assertEquals(0, report.skippedLines, "skipped rows")
    assertEquals(5, report.totalDataLines, "data line count")

    // Exact values from the snapshot the fixture was cut from.
    val first = report.servers[0]
    assertEquals("public-vpn-45", first.hostName, "row 0 host name")
    assertEquals("219.100.37.9", first.ipAddress, "row 0 ip")
    assertEquals(2_927_964L, first.score, "row 0 score")
    assertEquals(10, first.pingMs, "row 0 ping")
    assertEquals("Japan", first.countryLong, "row 0 country long")
    assertEquals("JP", first.countryShort, "row 0 country short")
    assertEquals(true, first.isReachable, "row 0 reachable")

    // CRLF must not leak into the trailing Base64 column.
    check(report.servers.none { it.configBase64.contains('\r') }) {
        "CRLF leaked into a Base64 field"
    }
    check(report.servers.all { it.configBase64.length > 1000 }) {
        "Base64 config looks truncated"
    }
}

/** Parser against every awkward shape the harness knows about. */
private fun testParserOnEdgeCases() = test("parser: malformed / hostile rows") {
    val report = VpnGateCsvParser.parse(fixture("edge_cases.csv"))

    // 9 data rows: 'no-config' (blank Base64) and 'truncated' (4 columns) are dropped.
    assertEquals(9, report.totalDataLines, "data line count")
    assertEquals(7, report.servers.size, "parsed rows")
    assertEquals(2, report.skippedLines, "skipped rows")

    val quoted = report.servers.first { it.hostName == "good-quoted" }
    // The RFC 4180 splitter must keep the comma inside the quotes.
    assertEquals("10.0.0.1", quoted.ipAddress, "quoted-field row kept its ip column")
    assertEquals("JP", quoted.countryShort, "quoted-field row kept its country column")

    val escaped = report.servers.first { it.hostName == "good-escapes" }
    assertEquals("10.0.0.7", escaped.ipAddress, "escaped-quote row kept its ip column")

    val badNumbers = report.servers.first { it.hostName == "bad-numbers" }
    assertEquals(0, badNumbers.pingMs, "non-numeric ping falls back to 0")
    assertEquals(0L, badNumbers.score, "non-numeric score falls back to 0")
    assertEquals(false, badNumbers.isReachable, "non-numeric ping row is not reachable")
}

/** Blank/garbage payloads must fail loudly rather than return an empty list. */
private fun testParserRejectsGarbage() = test("parser: empty and garbage payloads") {
    for (payload in listOf("", "   ", "*vpn_servers\r\n*\r\n", "<html>404</html>")) {
        val thrown = runCatching { VpnGateCsvParser.parse(payload) }.exceptionOrNull()
        check(thrown is VpnGateCsvParser.InvalidFeedException) {
            "payload ${payload.take(20)} should raise InvalidFeedException, got $thrown"
        }
    }
}

/** A feed with no header must still parse using the canonical column order. */
private fun testParserHeaderlessFallback() = test("parser: headerless fallback") {
    val csv = "*vpn_servers\n" +
        "public-vpn-1,1.2.3.4,1000,20,500000,Japan,JP,1,1,1,1,2weeks,op,,QUJD\n*"
    val report = VpnGateCsvParser.parse(csv)
    assertEquals(false, report.headerRecognised, "header not recognised")
    assertEquals(1, report.servers.size, "parsed rows")
    assertEquals("1.2.3.4", report.servers[0].ipAddress, "ip from fallback index")
    assertEquals("QUJD", report.servers[0].configBase64, "config from fallback index")
}

/** The RFC 4180 splitter itself. */
private fun testCsvSplitter() = test("parser: RFC 4180 field splitter") {
    assertEquals(listOf("a", "b", "c"), VpnGateCsvParser.splitCsvLine("a,b,c"), "plain")
    assertEquals(listOf("a", "x,y", "c"), VpnGateCsvParser.splitCsvLine("a,\"x,y\",c"), "quoted comma")
    assertEquals(
        listOf("a", "he said \"hi\"", "c"),
        VpnGateCsvParser.splitCsvLine("a,\"he said \"\"hi\"\"\",c"),
        "escaped quotes",
    )
    assertEquals(listOf("a", "", "c"), VpnGateCsvParser.splitCsvLine("a,,c"), "empty field")
    assertEquals(listOf("a", ""), VpnGateCsvParser.splitCsvLine("a,"), "trailing empty field")
    // Base64 punctuation must survive untouched.
    val b64 = "ZGV2IHR1bg0K==+/x"
    assertEquals(listOf("h", b64), VpnGateCsvParser.splitCsvLine("h,$b64"), "base64 chars intact")
}

/** Dead-server filtering and both ranking strategies. */
private fun testSelector() = test("selector: dead filtering and ordering") {
    val parsed = VpnGateCsvParser.parse(fixture("edge_cases.csv"))

    val byPing = ServerSelector.select(parsed.servers, ServerSelector.Strategy.LOWEST_PING)
    assertEquals(2, byPing.rejected, "dead rows rejected")
    // alive: hostile(5) good-quoted(12) good-escapes(25) good-noca(30) good-slow(95)
    assertEquals(
        listOf("hostile", "good-quoted", "good-escapes", "good-noca", "good-slow"),
        byPing.ranked.map { it.hostName },
        "lowest-ping ordering",
    )
    assertEquals(5, byPing.best?.pingMs, "best ping")

    val capped = ServerSelector.select(parsed.servers, maxCandidates = 2)
    assertEquals(2, capped.ranked.size, "candidate cap")
    assertEquals(listOf("hostile", "good-quoted"), capped.ranked.map { it.hostName }, "capped order")

    val excluded = ServerSelector.select(
        parsed.servers,
        excludeHosts = setOf("hostile", "good-quoted"),
    )
    assertEquals("good-escapes", excluded.best?.hostName, "excluded hosts skipped")

    // Tie on ping must be broken by the higher reliability score.
    val tied = listOf(
        server("low-score", ping = 10, score = 100),
        server("high-score", ping = 10, score = 9_999),
    )
    assertEquals(
        listOf("high-score", "low-score"),
        ServerSelector.select(tied).ranked.map { it.hostName },
        "ping tie broken by score",
    )

    // BEST_QUALITY must still put the fast, well-scored host first.
    val quality = ServerSelector.select(parsed.servers, ServerSelector.Strategy.BEST_QUALITY)
    check(quality.ranked.isNotEmpty()) { "quality ranking produced nothing" }
    check(quality.best!!.isReachable) { "quality best is not reachable" }
}

/** Base64 decoder equivalence with the JDK implementation, on real payloads. */
private fun testBase64AgainstJdk() = test("base64: matches java.util.Base64 on real payloads") {
    val report = VpnGateCsvParser.parse(fixture("vpngate_real_sample.csv"))
    val mime = java.util.Base64.getMimeDecoder()
    for (server in report.servers) {
        val mine = Base64Compat.decode(server.configBase64)
        val jdk = mime.decode(server.configBase64)
        check(mine != null) { "${server.hostName}: decoder returned null" }
        check(mine!!.contentEquals(jdk)) {
            "${server.hostName}: decoder output differs from java.util.Base64 " +
                "(${mine.size} vs ${jdk.size} bytes)"
        }
    }
    // Round-trip through our own encoder.
    for (server in report.servers) {
        val bytes = Base64Compat.decode(server.configBase64)!!
        check(Base64Compat.decode(Base64Compat.encode(bytes))!!.contentEquals(bytes)) {
            "${server.hostName}: encode/decode round-trip failed"
        }
    }
    // Lenient about whitespace, strict about emptiness.
    check(Base64Compat.decode("  \n ") == null) { "blank input should decode to null" }
    check(Base64Compat.decode("") == null) { "empty input should decode to null" }
    check(Base64Compat.decode("QUJD\n\r")!!.toString(Charsets.UTF_8) == "ABC") {
        "line breaks should be ignored"
    }

    // Exhaustive length sweep: every payload length mod 4 exercises a different
    // padding path, and each must agree byte-for-byte with the JDK decoder.
    // Length 0 is excluded - it is covered above, where null is the right answer.
    val mimeEncoder = java.util.Base64.getEncoder()
    for (length in 1..64) {
        val bytes = ByteArray(length) { (it * 31 + 7).toByte() }
        val encoded = mimeEncoder.encodeToString(bytes)
        val mine = Base64Compat.decode(encoded)
        check(mine != null) { "length $length: decoder returned null for '$encoded'" }
        check(mine!!.contentEquals(bytes)) {
            "length $length: decoder output differs from java.util.Base64"
        }
        check(Base64Compat.encode(bytes) == encoded) {
            "length $length: encoder disagrees with java.util.Base64"
        }
    }
    // All 256 byte values, to cover the whole alphabet including + and /.
    val allBytes = ByteArray(256) { it.toByte() }
    check(
        Base64Compat.decode(mimeEncoder.encodeToString(allBytes))!!.contentEquals(allBytes),
    ) { "full-alphabet payload did not round-trip" }
}

/** Every real profile must decode into a usable, hardened client config. */
private fun testDecoderOnRealConfigs() = test("decoder: real profiles decode and harden") {
    val report = VpnGateCsvParser.parse(fixture("vpngate_real_sample.csv"))
    for (server in report.servers) {
        val decoded = OvpnConfigDecoder.decode(server.configBase64)
        check(decoded is OvpnConfigDecoder.Result.Success) {
            "${server.hostName}: decode failed -> $decoded"
        }
        decoded as OvpnConfigDecoder.Result.Success

        // The decoded profile must address the same relay the CSV advertised.
        assertEquals(server.ipAddress, decoded.remoteHost, "${server.hostName}: remote host")
        check(decoded.remotePort in 1..65535) { "${server.hostName}: bad port ${decoded.remotePort}" }
        check(decoded.transport in setOf("tcp", "udp")) {
            "${server.hostName}: unexpected transport ${decoded.transport}"
        }
        // Real VPN Gate profiles ship CRLF and no remote-cert-tls; we fix both.
        check(!decoded.ovpn.contains("\r\n")) { "${server.hostName}: CRLF survived normalisation" }
        check(decoded.ovpn.lineSequence().any { it.trim() == "remote-cert-tls server" }) {
            "${server.hostName}: remote-cert-tls server was not added"
        }
        check(decoded.ovpn.contains("<ca>")) { "${server.hostName}: CA block lost" }
        check(decoded.ovpn.contains("<key>")) { "${server.hostName}: client key block lost" }
        // The Base64 must have decoded to real text, not to shifted garbage.
        check(decoded.ovpn.contains("-----BEGIN CERTIFICATE-----")) {
            "${server.hostName}: no PEM header - Base64 decode is misaligned"
        }
        // The tail of the profile must survive: VPN Gate configs end with </key>.
        check(decoded.ovpn.contains("</key>")) {
            "${server.hostName}: profile tail lost, </key> missing"
        }
        check(decoded.ovpn.none { it == '\uFFFD' }) {
            "${server.hostName}: replacement characters in decoded profile"
        }
        // Hardening directives are appended, so they must be the last lines.
        val lastLine = decoded.ovpn.trimEnd('\n').substringAfterLast('\n')
        assertEquals("server-poll-timeout 20", lastLine, "${server.hostName}: last line")
    }
}

/** Hostile directives must be stripped before OpenVPN ever sees the profile. */
private fun testDecoderStripsHostileDirectives() = test("decoder: strips hostile directives") {
    val parsed = VpnGateCsvParser.parse(fixture("edge_cases.csv"))
    val hostile = parsed.servers.first { it.hostName == "hostile" }
    val decoded = OvpnConfigDecoder.decode(hostile.configBase64)
    check(decoded is OvpnConfigDecoder.Result.Success) { "hostile profile should still decode" }
    decoded as OvpnConfigDecoder.Result.Success

    val active = decoded.ovpn.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        .map { it.substringBefore(' ') }
        .toSet()

    for (forbidden in listOf("up", "script-security", "auth-user-pass", "management")) {
        check(forbidden !in active) { "forbidden directive survived: $forbidden" }
    }
    check("remote" in active) { "remote directive was lost" }
    check(decoded.warnings.isNotEmpty()) { "expected warnings for the stripped directives" }
    check(decoded.ovpn.contains("/tmp/evil.sh").not()) { "script path still present in profile" }
}

/** Structurally broken profiles must be rejected, not passed to OpenVPN. */
private fun testDecoderRejectsBrokenProfiles() = test("decoder: rejects broken profiles") {
    val noCa = OvpnConfigDecoder.decode(
        java.util.Base64.getEncoder().encodeToString("dev tun\nproto tcp\nremote 1.2.3.4 443\nclient\n".toByteArray()),
    )
    check(noCa is OvpnConfigDecoder.Result.Failure) { "profile without a CA should be rejected" }

    val noRemote = OvpnConfigDecoder.decode(
        java.util.Base64.getEncoder().encodeToString("dev tun\nclient\n<ca>\nX\n</ca>\n".toByteArray()),
    )
    check(noRemote is OvpnConfigDecoder.Result.Failure) { "profile without remote should be rejected" }

    check(OvpnConfigDecoder.decode("") is OvpnConfigDecoder.Result.Failure) { "blank payload" }
    check(OvpnConfigDecoder.decode("!!!!") is OvpnConfigDecoder.Result.Failure) { "non-base64 payload" }
}

/** End-to-end: one tap on the real snapshot yields the lowest-ping relay. */
private fun testPipelineEndToEnd() = test("pipeline: end to end on the real snapshot") {
    val pipeline = OneTapPipeline(FakeSource(fixture("vpngate_real_sample.csv")))
    val result = runSuspend { pipeline.prepareConnection() }

    check(result is PipelineResult.Success) { "pipeline failed: $result" }
    val connection = (result as PipelineResult.Success).connection

    // Lowest ping in the sample is public-vpn-45 at 10 ms.
    assertEquals("public-vpn-45", connection.server.hostName, "selected relay")
    assertEquals(10, connection.server.pingMs, "selected relay ping")
    assertEquals("Japan (JP)", connection.server.displayCountry, "display country")
    assertEquals("219.100.37.9", connection.profile.remoteHost, "dialed host")
    assertEquals(4, connection.fallbacks.size, "fallback queue length")
    assertEquals(5, connection.candidatesConsidered, "candidates considered")
    assertEquals(0, connection.rejectedServers, "rejected servers")

    // The fallback queue must be ordered by ascending ping too.
    val pings = connection.fallbacks.map { it.pingMs }
    assertEquals(pings.sorted(), pings, "fallback queue ordering")
}

/** A relay that fails must not be retried; the queue moves on. */
private fun testPipelineSkipsTriedHosts() = test("pipeline: excludes already-tried hosts") {
    val pipeline = OneTapPipeline(FakeSource(fixture("vpngate_real_sample.csv")))
    val result = runSuspend { pipeline.prepareConnection(excludeHosts = setOf("public-vpn-45")) }
    check(result is PipelineResult.Success) { "pipeline failed: $result" }
    assertEquals(
        "public-vpn-196",
        (result as PipelineResult.Success).connection.server.hostName,
        "next best relay",
    )
}

/** Network and payload failures must surface as the right pipeline stage. */
private fun testPipelineFailureModes() = test("pipeline: failure modes") {
    val networkFailure = runSuspend {
        OneTapPipeline(FailingSource("timeout after 15000 ms")).prepareConnection()
    }
    check(networkFailure is PipelineResult.Failure) { "expected failure" }
    assertEquals(PipelineStage.FETCH, (networkFailure as PipelineResult.Failure).stage, "stage")

    val emptyBody = runSuspend {
        OneTapPipeline(FakeSource("")).prepareConnection()
    }
    assertEquals(PipelineStage.PARSE, (emptyBody as PipelineResult.Failure).stage, "empty body stage")

    val htmlError = runSuspend {
        OneTapPipeline(FakeSource("<html><body>503 Service Unavailable</body></html>"))
            .prepareConnection()
    }
    assertEquals(PipelineStage.PARSE, (htmlError as PipelineResult.Failure).stage, "html body stage")

    // A feed where every relay is dead must fail at SELECT, not silently pick one.
    val allDead = "*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort," +
        "NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n" +
        "h1,1.1.1.1,10,0,1,Japan,JP,1,1,1,1,2weeks,op,,QUJD\n*\n"
    val selectFailure = runSuspend { OneTapPipeline(FakeSource(allDead)).prepareConnection() }
    assertEquals(PipelineStage.SELECT, (selectFailure as PipelineResult.Failure).stage, "all-dead stage")
}

/** Ranking strategy switch must not break the pipeline. */
private fun testPipelineQualityStrategy() = test("pipeline: BEST_QUALITY strategy") {
    val pipeline = OneTapPipeline(
        source = FakeSource(fixture("vpngate_real_sample.csv")),
        strategy = ServerSelector.Strategy.BEST_QUALITY,
    )
    val result = runSuspend { pipeline.prepareConnection() }
    check(result is PipelineResult.Success) { "pipeline failed: $result" }
    val connection = (result as PipelineResult.Success).connection
    check(connection.server.isReachable) { "selected relay is not reachable" }
    check(connection.profile.remoteHost == connection.server.ipAddress) {
        "profile addresses a different host than the selected relay"
    }
}

/**
 * Optional stress test: point EDUVPN_FULL_SNAPSHOT at a complete VPN Gate CSV
 * response and every row gets parsed, ranked, decoded and cross-checked against
 * java.util.Base64. Skipped when the variable is unset, so the default run stays
 * hermetic.
 */
private fun testFullSnapshotIfAvailable() {
    val path = System.getenv("EDUVPN_FULL_SNAPSHOT")
    if (path.isNullOrBlank()) {
        results.add("full snapshot (skipped, EDUVPN_FULL_SNAPSHOT unset)" to Result.success(Unit))
        return
    }
    test("full snapshot: every row parses and decodes") {
        val csv = File(path).readText(Charsets.UTF_8)
        val report = VpnGateCsvParser.parse(csv)
        println("        rows parsed=${report.servers.size} skipped=${report.skippedLines}")

        val mime = java.util.Base64.getMimeDecoder()
        var decoded = 0
        for (server in report.servers) {
            check(server.configBase64.isNotBlank()) { "${server.hostName}: blank config" }
            val result = OvpnConfigDecoder.decode(server.configBase64)
            if (result is OvpnConfigDecoder.Result.Success) {
                decoded++
                check(Base64Compat.decode(server.configBase64)!!.contentEquals(mime.decode(server.configBase64))) {
                    "${server.hostName}: Base64 disagrees with java.util.Base64"
                }
            } else {
                println("        note: ${server.hostName} rejected -> $result")
            }
        }
        // Every VPN Gate relay ships a complete inline profile, so anything less
        // than a full decode rate means the parser or decoder is dropping data.
        assertEquals(report.servers.size, decoded, "decoded profiles")

        val selection = ServerSelector.select(report.servers, maxCandidates = 5)
        val best = selection.best ?: throw TestFailure("no live server in the full snapshot")
        val pings = selection.ranked.map { it.pingMs }
        assertEquals(pings.sorted(), pings, "full snapshot ranking order")
        println(
            "        best=${best.hostName} ping=${best.pingMs}ms rejected=${selection.rejected}",
        )
    }
}

// -------------------------------------------------------------------------- main

private fun server(name: String, ping: Int, score: Long): VpnGateServer = VpnGateServer(
    hostName = name,
    ipAddress = "10.0.0.1",
    score = score,
    pingMs = ping,
    speedBps = 1_000_000,
    countryLong = "Japan",
    countryShort = "JP",
    sessionCount = 1,
    logType = "2weeks",
    configBase64 = "QUJD",
)

fun main() {
    println("EDUVPN domain verification")
    println("fixtures: ${FIXTURE_DIR.absolutePath}")
    println("-".repeat(78))

    testParserOnRealSample()
    testParserOnEdgeCases()
    testParserRejectsGarbage()
    testParserHeaderlessFallback()
    testCsvSplitter()
    testSelector()
    testBase64AgainstJdk()
    testDecoderOnRealConfigs()
    testDecoderStripsHostileDirectives()
    testDecoderRejectsBrokenProfiles()
    testPipelineEndToEnd()
    testPipelineSkipsTriedHosts()
    testPipelineFailureModes()
    testPipelineQualityStrategy()
    testFullSnapshotIfAvailable()

    var failures = 0
    for ((name, outcome) in results) {
        outcome.fold(
            onSuccess = { println("PASS  $name") },
            onFailure = { error ->
                failures++
                println("FAIL  $name")
                println("        ${error.message}")
            },
        )
    }
    println("-".repeat(78))
    println("${results.size - failures}/${results.size} test groups passed")
    if (failures > 0) {
        println("RESULT: FAILED")
        kotlin.system.exitProcess(1)
    }
    println("RESULT: OK")
}
