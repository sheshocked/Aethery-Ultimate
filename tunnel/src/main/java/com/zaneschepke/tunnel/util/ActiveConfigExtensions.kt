package com.zaneschepke.tunnel.util

import com.zaneschepke.tunnel.model.DnsBootstrapResult
import com.zaneschepke.tunnel.model.ResolvedHost
import com.zaneschepke.wireguardautotunnel.parser.ActiveConfig

fun ActiveConfig.findEndpointMismatches(
    freshDns: Map<PublicKey, DnsBootstrapResult>,
    preferIpv6: Boolean,
): Map<PublicKey, ResolvedHost> {
    val currentEndpoints = peers.associateBy { it.publicKey }

    return freshDns
        .mapNotNull { (pubKey, dnsResult) ->
            val current = currentEndpoints[pubKey] ?: return@mapNotNull null
            val currentHost = current.host ?: return@mapNotNull null

            // IP4P is IPv4 only so we skip it in the IPv6 recovery
            val hasIp4p = dnsResult.ipv6.any { DnsHostUtils.decodeIp4p(it) != null }
            if (hasIp4p && preferIpv6) return@mapNotNull null

            val freshAddress =
                if (preferIpv6 && dnsResult.ipv6.isNotEmpty()) {
                    dnsResult.ipv6.first()
                } else {
                    dnsResult.ipv4.firstOrNull() ?: dnsResult.ipv6.firstOrNull()
                } ?: return@mapNotNull null

            if (freshAddress != currentHost) {
                pubKey to ResolvedHost(host = freshAddress)
            } else {
                null
            }
        }
        .toMap()
}
