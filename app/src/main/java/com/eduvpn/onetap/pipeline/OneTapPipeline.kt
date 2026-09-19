/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Plain Kotlin (no android.* imports) so it can be exercised on a plain JVM.
 */
package com.eduvpn.onetap.pipeline

import com.eduvpn.onetap.data.model.VpnGateServer
import com.eduvpn.onetap.domain.OvpnConfigDecoder

/**
 * The one-tap flow, expressed without any Android types.
 *
 * ```
 *  tap  ->  fetch CSV  ->  parse  ->  filter/rank  ->  base64 decode + harden
 *                                                                  |
 *                                                     PreparedConnection
 *                                                                  |
 *                                              MainActivity hands it to the
 *                                              OpenVPN controller (Android side)
 * ```
 *
 * Everything up to the dotted line is pure Kotlin, which is why this class can
 * be driven by a real snapshot of the VPN Gate feed in a JVM test with no
 * emulator involved.
 */

/** Fetches the raw server-list CSV. Implemented by `data/remote/VpnGateApi`. */
interface ServerListSource {
    suspend fun fetchServerListCsv(): FetchOutcome
}

sealed interface FetchOutcome {
    /** @param csv raw response body. @param sourceUrl endpoint that answered. */
    data class Success(val csv: String, val sourceUrl: String) : FetchOutcome
    data class Failure(val reason: String) : FetchOutcome
}

/** Which stage of the pipeline gave up. Drives the error copy in the UI. */
enum class PipelineStage { FETCH, PARSE, SELECT, DECODE }

/** Everything needed to open the tunnel, plus the queue to fall back to. */
data class PreparedConnection(
    val server: VpnGateServer,
    val profile: OvpnConfigDecoder.Result.Success,
    val fallbacks: List<VpnGateServer>,
    val sourceUrl: String,
    val candidatesConsidered: Int,
    val rejectedServers: Int,
)

sealed interface PipelineResult {
    data class Success(val connection: PreparedConnection) : PipelineResult
    data class Failure(val stage: PipelineStage, val message: String) : PipelineResult
}

/**
 * Runs the pipeline. Stateless apart from the injected collaborators, so a
 * single instance can be reused for every tap.
 */
class OneTapPipeline(
    private val source: ServerListSource,
    private val strategy: com.eduvpn.onetap.domain.ServerSelector.Strategy =
        com.eduvpn.onetap.domain.ServerSelector.Strategy.LOWEST_PING,
    private val maxCandidates: Int = 5,
) {

    /**
     * @param excludeHosts relays already tried in this session, so a retry after a
     *                     failed tunnel does not pick the same dead host again.
     */
    suspend fun prepareConnection(excludeHosts: Set<String> = emptySet()): PipelineResult {
        val fetch = source.fetchServerListCsv()
        if (fetch is FetchOutcome.Failure) {
            return PipelineResult.Failure(PipelineStage.FETCH, fetch.reason)
        }
        fetch as FetchOutcome.Success

        val report = try {
            com.eduvpn.onetap.data.parser.VpnGateCsvParser.parse(fetch.csv)
        } catch (e: com.eduvpn.onetap.data.parser.VpnGateCsvParser.InvalidFeedException) {
            return PipelineResult.Failure(PipelineStage.PARSE, e.message ?: "unparsable server list")
        } catch (e: Exception) {
            return PipelineResult.Failure(PipelineStage.PARSE, "parse error: ${e.message}")
        }

        val selection = com.eduvpn.onetap.domain.ServerSelector.select(
            servers = report.servers,
            strategy = strategy,
            excludeHosts = excludeHosts,
            maxCandidates = maxCandidates,
        )
        if (selection.isEmpty) {
            return PipelineResult.Failure(
                PipelineStage.SELECT,
                "no live servers in the list (${report.servers.size} listed, " +
                    "${selection.rejected} rejected as unreachable)",
            )
        }

        // Walk the queue: the top-ranked relay may still have a profile we cannot
        // decode, in which case the next candidate is the answer.
        var lastDecodeFailure: String? = null
        for ((index, candidate) in selection.ranked.withIndex()) {
            when (val decoded = OvpnConfigDecoder.decode(candidate.configBase64)) {
                is OvpnConfigDecoder.Result.Success -> return PipelineResult.Success(
                    PreparedConnection(
                        server = candidate,
                        profile = decoded,
                        fallbacks = selection.ranked.drop(index + 1),
                        sourceUrl = fetch.sourceUrl,
                        candidatesConsidered = report.servers.size,
                        rejectedServers = selection.rejected,
                    ),
                )
                is OvpnConfigDecoder.Result.Failure -> lastDecodeFailure = decoded.reason
            }
        }

        return PipelineResult.Failure(
            PipelineStage.DECODE,
            "none of the top ${selection.ranked.size} profiles decoded " +
                "(last error: $lastDecodeFailure)",
        )
    }
}
