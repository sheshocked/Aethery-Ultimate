package com.zaneschepke.tunnel.util

import android.os.Build
import com.zaneschepke.tunnel.model.DnsBootstrapResult
import com.zaneschepke.tunnel.model.DnsConfig
import com.zaneschepke.tunnel.model.ResolvedHost
import com.zaneschepke.wireguardautotunnel.parser.Config
import com.zaneschepke.wireguardautotunnel.parser.InterfaceSection
import com.zaneschepke.wireguardautotunnel.parser.PeerSection
import java.net.Inet4Address
import java.net.InetAddress
import timber.log.Timber

/** Parses a CIDR string and returns the address + prefix length */
internal fun String.parseInetNetwork(): Pair<InetAddress, Int> {
    val slashIndex = lastIndexOf('/')
    val rawAddress: String
    val rawMask: String?

    if (slashIndex >= 0) {
        rawAddress = substring(0, slashIndex).trim()
        rawMask = substring(slashIndex + 1).trim()
    } else {
        rawAddress = trim()
        rawMask = null
    }

    val address =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.net.InetAddresses.parseNumericAddress(rawAddress)
        } else {
            InetAddress.getByName(rawAddress)
        }

    val maxMask = if (address is Inet4Address) 32 else 128
    val mask = rawMask?.toIntOrNull() ?: maxMask

    if (mask !in 0..maxMask) {
        throw IllegalArgumentException("Invalid network mask: $rawMask (must be 0-$maxMask)")
    }

    return address to mask
}

internal fun String.parseDns(): DnsConfig {
    val servers = mutableListOf<InetAddress>()
    val domains = mutableListOf<String>()

    split(",").forEach { item ->
        val trimmed = item.trim()
        if (trimmed.isBlank()) return@forEach

        try {
            val ip =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.InetAddresses.parseNumericAddress(trimmed)
                } else {
                    InetAddress.getByName(trimmed)
                }
            servers.add(ip)
        } catch (_: Exception) {
            domains.add(trimmed)
        }
    }

    return DnsConfig(servers, domains)
}

internal fun Config.buildResolvedPeers(hostMap: Map<PublicKey, ResolvedHost>): List<PeerSection> {
    return this.peers.map { peer ->
        val resolved = hostMap[peer.publicKey] ?: return@map peer

        val port =
            resolved.forcedPort?.toString()
                ?: peer.endpoint?.substringAfterLast(":")
                ?: return@map peer

        peer.copy(endpoint = "${resolved.host}:$port")
    }
}

internal fun Map<PublicKey, DnsBootstrapResult>.toHostMap(
    preferIpv6: Boolean
): Map<PublicKey, ResolvedHost> =
    mapNotNull { (pubKey, result) ->
            val ip4p = result.ipv6.firstNotNullOfOrNull { DnsHostUtils.decodeIp4p(it) }

            // IP4P support
            if (ip4p != null) {
                val (ipv4, port) = ip4p
                Timber.i("IP4P detected for peer!")
                return@mapNotNull pubKey to ResolvedHost(host = ipv4, forcedPort = port)
            }

            // Normal path
            val host =
                if (preferIpv6) {
                    result.ipv6.firstOrNull() ?: result.ipv4.firstOrNull()
                } else {
                    result.ipv4.firstOrNull() ?: result.ipv6.firstOrNull()
                }

            host?.let { pubKey to ResolvedHost(it) }
        }
        .toMap()

fun InterfaceSection.parseDnsServersOnly(): List<String> =
    dns?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

fun String.ensurePort53(): String = ensureDnsPort(53)

fun String.ensureDnsPort(defaultPort: Int): String {
    val s = trim()
    if (s.isEmpty()) return s

    // [IPv6] or [IPv6]:port
    if (s.startsWith("[")) {
        val end = s.indexOf(']')
        if (end <= 1) return s
        val rest = s.substring(end + 1)
        if (rest.startsWith(":") && rest.drop(1).toIntOrNull() != null) return s
        return "$s:$defaultPort"
    }

    // IPv4 or hostname with port (single colon)
    val colon = s.lastIndexOf(':')
    if (colon > 0 && s.indexOf(':') == colon) {
        val port = s.substring(colon + 1).toIntOrNull()
        if (port != null && port in 1..65535) return s
    }

    // bare IPv6 without brackets — uncommon in conf; leave unchanged
    if (s.contains(':')) return s

    return "$s:$defaultPort"
}
