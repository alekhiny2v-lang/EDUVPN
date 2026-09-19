/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Unit tests for the Android-free layers. Run with:
 *     ./gradlew :app:testDebugUnitTest
 *
 * The same ground is covered without Gradle by verification/run_tests.sh.
 */
package com.eduvpn.onetap

import com.eduvpn.onetap.data.parser.VpnGateCsvParser
import com.eduvpn.onetap.domain.Base64Compat
import com.eduvpn.onetap.domain.OvpnConfigDecoder
import com.eduvpn.onetap.domain.ServerSelector
import com.eduvpn.onetap.pipeline.FetchOutcome
import com.eduvpn.onetap.pipeline.OneTapPipeline
import com.eduvpn.onetap.pipeline.PipelineResult
import com.eduvpn.onetap.pipeline.PipelineStage
import com.eduvpn.onetap.pipeline.ServerListSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerPipelineTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing test resource fixtures/$name"
        }.bufferedReader(Charsets.UTF_8).readText()

    private class FakeSource(private val csv: String) : ServerListSource {
        override suspend fun fetchServerListCsv(): FetchOutcome =
            FetchOutcome.Success(csv, "fixture://$this")
    }

    // ------------------------------------------------------------- CSV parsing

    @Test
    fun `parses a real VPN Gate snapshot`() {
        val report = VpnGateCsvParser.parse(fixture("vpngate_real_sample.csv"))

        assertTrue("header should be recognised", report.headerRecognised)
        assertEquals(5, report.servers.size)
        assertEquals(0, report.skippedLines)

        val first = report.servers[0]
        assertEquals("public-vpn-45", first.hostName)
        assertEquals("219.100.37.9", first.ipAddress)
        assertEquals(2_927_964L, first.score)
        assertEquals(10, first.pingMs)
        assertEquals("Japan (JP)", first.displayCountry)
        assertTrue(first.isReachable)

        // The feed is CRLF-delimited; a stray \r in the Base64 column means the
        // line splitting is wrong.
        assertTrue(report.servers.none { it.configBase64.contains('\r') })
    }

    @Test
    fun `skips unusable rows instead of failing the whole feed`() {
        val report = VpnGateCsvParser.parse(fixture("edge_cases.csv"))

        assertEquals(9, report.totalDataLines)
        assertEquals(7, report.servers.size)
        assertEquals(2, report.skippedLines)

        // A comma inside a quoted Operator field must not shift the columns.
        val quoted = report.servers.first { it.hostName == "good-quoted" }
        assertEquals("10.0.0.1", quoted.ipAddress)
        assertEquals("JP", quoted.countryShort)

        val escaped = report.servers.first { it.hostName == "good-escapes" }
        assertEquals("10.0.0.7", escaped.ipAddress)

        val bad = report.servers.first { it.hostName == "bad-numbers" }
        assertEquals(0, bad.pingMs)
        assertFalse(bad.isReachable)
    }

    @Test(expected = VpnGateCsvParser.InvalidFeedException::class)
    fun `an HTML error page is rejected`() {
        VpnGateCsvParser.parse("<html><body>503 Service Unavailable</body></html>")
    }

    @Test(expected = VpnGateCsvParser.InvalidFeedException::class)
    fun `an empty body is rejected`() {
        VpnGateCsvParser.parse("")
    }

    @Test
    fun `parses without a header using canonical column order`() {
        val csv = "*vpn_servers\n" +
            "h,1.2.3.4,1000,20,500000,Japan,JP,1,1,1,1,2weeks,op,,QUJD\n*"
        val report = VpnGateCsvParser.parse(csv)
        assertFalse(report.headerRecognised)
        assertEquals("1.2.3.4", report.servers[0].ipAddress)
        assertEquals("QUJD", report.servers[0].configBase64)
    }

    @Test
    fun `csv splitter handles quotes and base64 punctuation`() {
        assertEquals(listOf("a", "b", "c"), VpnGateCsvParser.splitCsvLine("a,b,c"))
        assertEquals(listOf("a", "x,y", "c"), VpnGateCsvParser.splitCsvLine("a,\"x,y\",c"))
        assertEquals(
            listOf("a", "he said \"hi\"", "c"),
            VpnGateCsvParser.splitCsvLine("a,\"he said \"\"hi\"\"\",c"),
        )
        assertEquals(listOf("a", "", "c"), VpnGateCsvParser.splitCsvLine("a,,c"))
        val b64 = "ZGV2IHR1bg0K==+/"
        assertEquals(listOf("h", b64), VpnGateCsvParser.splitCsvLine("h,$b64"))
    }

    // ----------------------------------------------------------------- ranking

    @Test
    fun `dead relays are filtered and the rest ranked by ping`() {
        val parsed = VpnGateCsvParser.parse(fixture("edge_cases.csv"))
        val selection = ServerSelector.select(parsed.servers, ServerSelector.Strategy.LOWEST_PING)

        assertEquals(2, selection.rejected)
        assertEquals(
            listOf("hostile", "good-quoted", "good-escapes", "good-noca", "good-slow"),
            selection.ranked.map { it.hostName },
        )
        assertEquals(5, selection.best?.pingMs)
    }

    @Test
    fun `ping ties are broken by reliability score`() {
        val tied = listOf(
            server("low", ping = 10, score = 100),
            server("high", ping = 10, score = 9_999),
        )
        assertEquals(
            listOf("high", "low"),
            ServerSelector.select(tied).ranked.map { it.hostName },
        )
    }

    @Test
    fun `already-tried relays are excluded`() {
        val parsed = VpnGateCsvParser.parse(fixture("edge_cases.csv"))
        val selection = ServerSelector.select(
            parsed.servers,
            excludeHosts = setOf("hostile", "good-quoted"),
        )
        assertEquals("good-escapes", selection.best?.hostName)
    }

    @Test
    fun `candidate list is capped`() {
        val parsed = VpnGateCsvParser.parse(fixture("edge_cases.csv"))
        assertEquals(2, ServerSelector.select(parsed.servers, maxCandidates = 2).ranked.size)
    }

    // ----------------------------------------------------------------- base64

    @Test
    fun `base64 decoder agrees with the jdk on every real payload`() {
        val mime = java.util.Base64.getMimeDecoder()
        val report = VpnGateCsvParser.parse(fixture("vpngate_real_sample.csv"))
        for (server in report.servers) {
            val mine = Base64Compat.decode(server.configBase64)
            val jdk = mime.decode(server.configBase64)
            assertTrue("${server.hostName}: decoded to null", mine != null)
            assertTrue(
                "${server.hostName}: ${mine!!.size} vs ${jdk.size} bytes",
                mine.contentEquals(jdk),
            )
        }
    }

    @Test
    fun `base64 decoder covers every padding length`() {
        val encoder = java.util.Base64.getEncoder()
        for (length in 1..64) {
            val bytes = ByteArray(length) { (it * 31 + 7).toByte() }
            val encoded = encoder.encodeToString(bytes)
            assertEquals("length $length", encoded, Base64Compat.encode(bytes))
            assertTrue(
                "length $length",
                Base64Compat.decode(encoded)!!.contentEquals(bytes),
            )
        }
        val allBytes = ByteArray(256) { it.toByte() }
        assertTrue(Base64Compat.decode(encoder.encodeToString(allBytes))!!.contentEquals(allBytes))
    }

    @Test
    fun `base64 decoder ignores noise and rejects emptiness`() {
        assertNull(Base64Compat.decode(""))
        assertNull(Base64Compat.decode("  \n\t "))
        assertEquals("ABC", Base64Compat.decode("QUJD\n\r")!!.toString(Charsets.UTF_8))
    }

    // --------------------------------------------------------- config decoder

    @Test
    fun `every real profile decodes into a hardened client config`() {
        val report = VpnGateCsvParser.parse(fixture("vpngate_real_sample.csv"))
        for (server in report.servers) {
            val decoded = OvpnConfigDecoder.decode(server.configBase64)
            assertTrue("${server.hostName}: $decoded", decoded is OvpnConfigDecoder.Result.Success)
            decoded as OvpnConfigDecoder.Result.Success

            assertEquals(server.ipAddress, decoded.remoteHost)
            assertTrue(decoded.transport in setOf("tcp", "udp"))
            assertFalse(decoded.ovpn.contains("\r\n"))
            assertTrue(decoded.ovpn.contains("-----BEGIN CERTIFICATE-----"))
            assertTrue(decoded.ovpn.contains("</key>"))
            assertTrue(
                "${server.hostName}: remote-cert-tls not added",
                decoded.ovpn.lineSequence().any { it.trim() == "remote-cert-tls server" },
            )
        }
    }

    @Test
    fun `hostile directives are stripped from the profile`() {
        val parsed = VpnGateCsvParser.parse(fixture("edge_cases.csv"))
        val hostile = parsed.servers.first { it.hostName == "hostile" }
        val decoded = OvpnConfigDecoder.decode(hostile.configBase64) as OvpnConfigDecoder.Result.Success

        val active = decoded.ovpn.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            .map { it.substringBefore(' ') }
            .toSet()

        for (forbidden in listOf("up", "script-security", "auth-user-pass", "management")) {
            assertFalse("$forbidden survived sanitising", forbidden in active)
        }
        assertTrue("remote" in active)
        assertFalse(decoded.ovpn.contains("/tmp/evil.sh"))
    }

    @Test
    fun `profiles without a CA or a remote are rejected`() {
        val encoder = java.util.Base64.getEncoder()
        val noCa = encoder.encodeToString("dev tun\nproto tcp\nremote 1.2.3.4 443\nclient\n".toByteArray())
        val noRemote = encoder.encodeToString("dev tun\nclient\n<ca>\nX\n</ca>\n".toByteArray())

        assertTrue(OvpnConfigDecoder.decode(noCa) is OvpnConfigDecoder.Result.Failure)
        assertTrue(OvpnConfigDecoder.decode(noRemote) is OvpnConfigDecoder.Result.Failure)
        assertTrue(OvpnConfigDecoder.decode("") is OvpnConfigDecoder.Result.Failure)
        assertTrue(OvpnConfigDecoder.decode("!!!!") is OvpnConfigDecoder.Result.Failure)
    }

    // ---------------------------------------------------------------- pipeline

    @Test
    fun `one tap selects the lowest ping relay end to end`() = runTest {
        val result = OneTapPipeline(FakeSource(fixture("vpngate_real_sample.csv")))
            .prepareConnection()

        val connection = (result as? PipelineResult.Success)?.connection
            ?: fail("pipeline failed: $result")
        assertEquals("public-vpn-45", connection.server.hostName)
        assertEquals(10, connection.server.pingMs)
        assertEquals("219.100.37.9", connection.profile.remoteHost)
        assertEquals(4, connection.fallbacks.size)
        assertEquals(
            connection.fallbacks.map { it.pingMs }.sorted(),
            connection.fallbacks.map { it.pingMs },
        )
    }

    @Test
    fun `a previously tried relay is not selected again`() = runTest {
        val result = OneTapPipeline(FakeSource(fixture("vpngate_real_sample.csv")))
            .prepareConnection(excludeHosts = setOf("public-vpn-45"))
        assertEquals(
            "public-vpn-196",
            (result as PipelineResult.Success).connection.server.hostName,
        )
    }

    @Test
    fun `failures are attributed to the right pipeline stage`() = runTest {
        val network = OneTapPipeline(object : ServerListSource {
            override suspend fun fetchServerListCsv() =
                FetchOutcome.Failure("timeout after 15000 ms")
        }).prepareConnection()
        assertEquals(PipelineStage.FETCH, (network as PipelineResult.Failure).stage)

        val empty = OneTapPipeline(FakeSource("")).prepareConnection()
        assertEquals(PipelineStage.PARSE, (empty as PipelineResult.Failure).stage)

        val html = OneTapPipeline(FakeSource("<html>503</html>")).prepareConnection()
        assertEquals(PipelineStage.PARSE, (html as PipelineResult.Failure).stage)

        val allDead = "*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort," +
            "NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n" +
            "h1,1.1.1.1,10,0,1,Japan,JP,1,1,1,1,2weeks,op,,QUJD\n*\n"
        val dead = OneTapPipeline(FakeSource(allDead)).prepareConnection()
        assertEquals(PipelineStage.SELECT, (dead as PipelineResult.Failure).stage)
    }

    private fun server(name: String, ping: Int, score: Long) = com.eduvpn.onetap.data.model.VpnGateServer(
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
}
