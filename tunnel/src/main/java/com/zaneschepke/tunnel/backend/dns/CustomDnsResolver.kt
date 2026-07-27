package com.zaneschepke.tunnel.backend.dns

import android.net.Network
import com.zaneschepke.tunnel.model.DnsBoostrapConfig
import com.zaneschepke.tunnel.model.DnsBootstrapResult
import com.zaneschepke.tunnel.util.DnsHostUtils
import timber.log.Timber

class CustomDnsResolver(
    private val dnsConfig: DnsBoostrapConfig,
    private val bypass: Boolean,
    network: Network,
) : PeerResolver {

    private val systemResolver = AndroidNetworkResolver(network)

    override suspend fun resolve(host: String): DnsBootstrapResult {
        val upstream = dnsConfig.upstream
        if (upstream.isNullOrBlank()) {
            Timber.w("Custom DNS mode selected but no upstream configured")
            return DnsBootstrapResult()
        }

        val resolvedUpstreams: List<String> =
            if (DnsHostUtils.needsResolution(upstream)) {
                Timber.d("Upstream DNS needs resolution, resolving via system resolver")
                val hostToResolve = DnsHostUtils.extractHost(upstream)
                val resolutionResult = systemResolver.resolve(hostToResolve)
                val ips = buildList {
                    addAll(resolutionResult.ipv4)
                    addAll(resolutionResult.ipv6.map { it.removeSurrounding("[", "]") })
                }
                if (ips.isEmpty()) {
                    Timber.w("Failed to resolve custom DNS upstream host: $upstream")
                    return DnsBootstrapResult()
                }
                ips.map { DnsHostUtils.replaceHostWithIP(upstream, it) }
            } else {
                listOf(upstream)
            }

        Timber.d("Using custom resolver with resolved upstreams $resolvedUpstreams")
        return try {
            NativeDnsResolver.resolveHostBootstrap(
                host = host,
                protocol = dnsConfig.protocol,
                resolvedUpstream = resolvedUpstreams.joinToString(),
                originalUpstream = upstream,
                bypass = bypass,
            )
        } catch (e: Exception) {
            Timber.w(e, "Custom DNS resolution failed for host=$host upstreams=$resolvedUpstreams")
            DnsBootstrapResult()
        }
    }
}
