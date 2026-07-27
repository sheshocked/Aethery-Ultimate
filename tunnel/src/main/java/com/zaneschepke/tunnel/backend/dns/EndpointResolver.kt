package com.zaneschepke.tunnel.backend.dns

import com.zaneschepke.networkmonitor.StableNetworkEngine
import com.zaneschepke.tunnel.model.BackendMode
import com.zaneschepke.tunnel.model.BootstrapResolution
import com.zaneschepke.tunnel.model.DnsBoostrapMode
import com.zaneschepke.tunnel.model.DnsBootstrapResult
import com.zaneschepke.tunnel.util.PublicKey
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import timber.log.Timber

class EndpointResolver(
    private val stableNetworkEngine: StableNetworkEngine,
    private val getDnsMode: () -> DnsBoostrapMode,
    private val isKillSwitchEnabled: () -> Boolean,
) {
    suspend fun resolve(
        mode: BackendMode,
        tunnelDnsConfig: TunnelDnsConfig? = null,
    ): BootstrapResolution = coroutineScope {
        val peersToResolve = mode.config.peers.filter { !it.isStaticallyConfigured }
        val peerResults = mutableMapOf<PublicKey, DnsBootstrapResult>()

        val dnsNeedsResolve = tunnelDnsConfig?.needsResolve() == true
        var resolvedDns: TunnelDnsConfig? = if (dnsNeedsResolve) null else tunnelDnsConfig

        // Nothing to do
        if (peersToResolve.isEmpty() && !dnsNeedsResolve) {
            return@coroutineScope BootstrapResolution(emptyMap(), tunnelDnsConfig)
        }

        stableNetworkEngine.stableState.first { it?.state?.activeNetwork?.network != null }

        var delayMs = 500L
        while (isActive) {
            val network =
                stableNetworkEngine.stableState.value?.state?.activeNetwork?.network
                    ?: run {
                        delay(100.milliseconds)
                        continue
                    }

            val dnsMode = getDnsMode()
            val bypassNeeded = mode is BackendMode.Vpn || isKillSwitchEnabled()
            val resolver: PeerResolver =
                when (dnsMode) {
                    is DnsBoostrapMode.System -> AndroidNetworkResolver(network)
                    is DnsBoostrapMode.Custom ->
                        CustomDnsResolver(dnsMode.config, bypassNeeded, network)
                }

            var progressed = false

            // Peers
            for (peer in peersToResolve) {
                if (peerResults.containsKey(peer.publicKey)) continue
                val host = peer.endpoint?.substringBeforeLast(":") ?: continue
                val result = resolver.resolve(host)
                if (result.ipv4.isNotEmpty() || result.ipv6.isNotEmpty()) {
                    peerResults[peer.publicKey] =
                        result.copy(
                            ipv6 = result.ipv6.map { if (it.startsWith("[")) it else "[$it]" }
                        )
                    progressed = true
                }
            }

            if (dnsNeedsResolve && resolvedDns == null) {
                val host = tunnelDnsConfig.resolveHost()
                if (host != null) {
                    val result = resolver.resolve(host)
                    if (result.ipv4.isNotEmpty() || result.ipv6.isNotEmpty()) {
                        resolvedDns = tunnelDnsConfig.withResolvedAddresses(result)
                        Timber.d("Tunnel DNS upstream resolved: $host")
                        progressed = true
                    }
                }
            }

            val peersDone =
                peersToResolve.isEmpty() ||
                    peerResults.keys.containsAll(peersToResolve.map { it.publicKey })
            val dnsDone = !dnsNeedsResolve || resolvedDns != null

            if (peersDone && dnsDone) {
                Timber.d(
                    "Bootstrap resolve complete (peers=${peerResults.size}, dns=${resolvedDns != null})"
                )
                return@coroutineScope BootstrapResolution(peerResults, resolvedDns)
            }

            if (!progressed) {
                delay(delayMs.milliseconds)
                delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF)
            } else {
                delayMs = 500L
            }
        }

        BootstrapResolution(peerResults, resolvedDns)
    }

    companion object {
        private const val MAX_BACKOFF = 30_000L
    }
}
