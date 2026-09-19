/*
 * EDUVPN - one-tap OpenVPN client.
 */
package com.eduvpn.onetap.data.remote

import com.eduvpn.onetap.pipeline.FetchOutcome
import com.eduvpn.onetap.pipeline.ServerListSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the VPN Gate relay list as raw CSV.
 *
 * The endpoint is `https://www.vpngate.net/api/iphone/`, which answers with a
 * ~1.3 MB CRLF-delimited CSV for roughly 100 relays. Two properties of that
 * endpoint shape this class:
 *
 *  - It is a single academic volunteer server that rate-limits and occasionally
 *    returns HTML error pages, so every endpoint is retried with backoff and a
 *    list of endpoints is walked before giving up.
 *  - The payload is large, so it is streamed with a hard byte cap instead of
 *    being slurped into memory blindly.
 *
 * Cleartext: the fallbacks below are plain HTTP. Android blocks cleartext by
 * default from API 28 on, which is why `res/xml/network_security_config.xml`
 * allow-lists exactly these hosts - see the manifest for the wiring.
 *
 * @param endpoints tried in order. Only the hosts verified to answer on
 *                  2026-09-19 are enabled by default.
 */
class VpnGateApi(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
    private val retryDelaysMs: LongArray = longArrayOf(0L, 750L, 2_000L),
    connectTimeoutMs: Long = 15_000L,
    readTimeoutMs: Long = 45_000L,
    callTimeoutMs: Long = 90_000L,
) : ServerListSource {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        // The response is gzip-friendly and the OkHttp interceptor chain adds
        // transparent gzip handling by default; do not set Accept-Encoding here.
        .build()

    override suspend fun fetchServerListCsv(): FetchOutcome = withContext(Dispatchers.IO) {
        val failures = ArrayList<String>()

        for (endpoint in endpoints) {
            for ((attempt, delayMs) in retryDelaysMs.withIndex()) {
                if (delayMs > 0) delay(delayMs)
                val outcome = runCatching { request(endpoint) }
                val success = outcome.getOrNull()
                if (success != null) return@withContext success

                failures.add(
                    "${shortHost(endpoint)} attempt ${attempt + 1}: " +
                        describe(outcome.exceptionOrNull() ?: IOException("unknown error")),
                )
            }
        }

        FetchOutcome.Failure(
            "Could not reach the server list.\n" + failures.joinToString("\n"),
        )
    }

    /** Single HTTP round trip. Throws on any non-usable response. */
    private fun request(endpoint: String): FetchOutcome {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/csv, text/plain, */*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from ${shortHost(endpoint)}")
            }
            val body = response.body

            // Stream with a cap: a hostile or broken mirror must not be able to
            // make us allocate an unbounded buffer. Read in chunks rather than
            // calling readBytes(n), which would pre-allocate the whole cap.
            body.byteStream().use { stream ->
                val sink = java.io.ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
                val chunk = ByteArray(CHUNK_BYTES)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (sink.size() + read > maxResponseBytes) {
                        throw IOException("response from ${shortHost(endpoint)} exceeds the size cap")
                    }
                    sink.write(chunk, 0, read)
                }
                val csv = sink.toString("UTF-8")
                if (!looksLikeServerList(csv)) {
                    throw IOException("response from ${shortHost(endpoint)} is not a server list")
                }
                return FetchOutcome.Success(csv, endpoint)
            }
        }
    }

    /**
     * Cheap structural guard, so an HTML error page fails here with a useful
     * message instead of two layers down in the CSV parser.
     */
    private fun looksLikeServerList(body: String): Boolean {
        val head = body.lineSequence().take(5).joinToString("\n")
        return head.contains("#HostName") || head.contains("OpenVPN_ConfigData_Base64")
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "timed out"
        is java.net.UnknownHostException -> "host not found (no network or DNS blocked)"
        is javax.net.ssl.SSLException -> "TLS error: ${error.message}"
        is IOException -> error.message ?: "I/O error"
        else -> error.toString()
    }

    private fun shortHost(endpoint: String): String =
        endpoint.removePrefix("https://").removePrefix("http://").substringBefore('/')

    companion object {
        /**
         * Verified working on 2026-09-19:
         *  1. the official endpoint over TLS,
         *  2. the same endpoint over plain HTTP (some carriers MITM the TLS one),
         *  3. an hourly-refreshed mirror of the official CSV, for networks where
         *     vpngate.net itself is blocked.
         *
         * VPN Gate also advertises additional relay mirrors on its website
         * (`vgateapi*.openvpn.jp` and similar). They were not reachable while
         * this list was written, so they are deliberately not defaults - append
         * any you trust to this list, or pass your own via the constructor.
         */
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://www.vpngate.net/api/iphone/",
            "http://www.vpngate.net/api/iphone/",
            "https://raw.githubusercontent.com/GeorgeXie2333/vpngate-list-mirror/main/data/vpngate.csv",
        )

        /** The real payload is ~1.3 MB; 8 MB leaves plenty of headroom. */
        const val MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024

        /** Sized to hold a typical response in one or two reallocations. */
        private const val INITIAL_BUFFER_BYTES = 1_500_000
        private const val CHUNK_BYTES = 64 * 1024

        private const val USER_AGENT: String =
            "EDUVPN/1.0 (Android; one-tap OpenVPN client)"
    }
}
