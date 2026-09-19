/*
 * EDUVPN - one-tap OpenVPN client.
 *
 * Plain Kotlin (no android.* imports) so it can be exercised on a plain JVM.
 */
package com.eduvpn.onetap.domain

import com.eduvpn.onetap.data.model.VpnGateServer

/**
 * Turns the parsed, ranked relay list into the ordered candidate queue that the
 * one-tap flow will actually try.
 *
 * "Dead server" filtering: VPN Gate is a public, volunteer-run relay pool and a
 * meaningful share of the listed hosts are offline at any moment. The fields that
 * identify those are:
 *  - `Ping == 0` - the probe never got a reply (the strongest signal);
 *  - blank `IP` or blank `OpenVPN_ConfigData_Base64` - nothing to dial;
 *  - optionally a `Score` floor, since `Score == 0` means "never verified".
 *
 * Two ranking strategies are provided:
 *  - [Strategy.LOWEST_PING] - the literal "fastest response time" answer, with
 *    score as the tie-break. Best raw latency, but can land on a busy host.
 *  - [Strategy.BEST_QUALITY] - latency, reliability score and throughput combined
 *    into one normalised number. More stable over many taps.
 *
 * Both return a *queue*, not a single winner: relays die between the CSV being
 * generated and the user tapping connect, so the caller walks down the list.
 */
object ServerSelector {

    enum class Strategy { LOWEST_PING, BEST_QUALITY }

    /**
     * @param ranked   candidates, best first, capped at [maxCandidates].
     * @param rejected how many input rows were filtered out as dead.
     */
    data class Selection(
        val ranked: List<VpnGateServer>,
        val rejected: Int,
        val inputCount: Int,
    ) {
        val best: VpnGateServer? get() = ranked.firstOrNull()
        val isEmpty: Boolean get() = ranked.isEmpty()
    }

    // Weights for Strategy.BEST_QUALITY. Lower normalised score == better host.
    private const val WEIGHT_PING = 0.55
    private const val WEIGHT_SCORE = 0.30
    private const val WEIGHT_SPEED = 0.15

    fun select(
        servers: List<VpnGateServer>,
        strategy: Strategy = Strategy.LOWEST_PING,
        excludeHosts: Set<String> = emptySet(),
        maxCandidates: Int = 5,
        minScore: Long = 0L,
    ): Selection {
        val alive = servers.filter { server ->
            server.isReachable &&
                server.score >= minScore &&
                server.hostName !in excludeHosts
        }

        val ranked = when (strategy) {
            Strategy.LOWEST_PING -> alive.sortedWith(
                compareBy<VpnGateServer> { it.pingMs }
                    .thenByDescending { it.score },
            )
            Strategy.BEST_QUALITY -> {
                val maxPing = (alive.maxOfOrNull { it.pingMs } ?: 1).coerceAtLeast(1)
                val maxScore = (alive.maxOfOrNull { it.score } ?: 1L).coerceAtLeast(1L)
                val maxSpeed = (alive.maxOfOrNull { it.speedBps } ?: 1L).coerceAtLeast(1L)
                alive.sortedBy { server ->
                    // ping / speed: lower is better. score: higher is better.
                    val pingPart = server.pingMs.toDouble() / maxPing * WEIGHT_PING
                    val scorePart =
                        (1.0 - server.score.toDouble() / maxScore) * WEIGHT_SCORE
                    val speedPart =
                        (1.0 - server.speedBps.toDouble() / maxSpeed) * WEIGHT_SPEED
                    pingPart + scorePart + speedPart
                }
            }
        }

        return Selection(
            ranked = ranked.take(maxCandidates.coerceAtLeast(1)),
            rejected = servers.size - alive.size,
            inputCount = servers.size,
        )
    }
}
