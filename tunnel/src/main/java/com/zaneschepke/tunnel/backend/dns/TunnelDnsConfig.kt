package com.zaneschepke.tunnel.backend.dns

import com.zaneschepke.tunnel.model.DnsBootstrapResult
import com.zaneschepke.tunnel.util.BackendException
import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class TunnelDnsConfig(
    // doh, dot, plain, local,
    val defaultTransport: String,
    // when not empty, we assume split
    val localSuffixes: List<String> = emptyList(),
    // must be resolved first before passing to native
    val upstream: List<String> = emptyList(),
    // original hostname for SNI
    val serverName: String? = null,
    val fakeDns: String = FAKE_DNS,
) {
    fun needsResolve(): Boolean {
        if (defaultTransport == "local") return false
        if (upstream.isEmpty()) return true
        return upstream.any { !isPreResolvedEntry(it) }
    }

    fun resolveHost(): String? {
        return upstream.firstOrNull()?.let { hostFromEntry(it) }
    }

    fun withResolvedAddresses(ips: DnsBootstrapResult): TunnelDnsConfig {
        val host =
            resolveHost() ?: throw BackendException.ConfigMissingDNS("Host missing from upstream")
        val port = portFromUpstreamOrDefault()
        val path = dohPathFromUpstream()
        val out = ArrayList<String>()

        for (ip in ips.ipv4) {
            out +=
                when (defaultTransport) {
                    "doh" -> "https://$ip$path"
                    else -> "$ip:$port"
                }
        }
        for (raw in ips.ipv6) {
            val ip = raw.removePrefix("[").removeSuffix("]")
            out +=
                when (defaultTransport) {
                    "doh" -> "https://[$ip]$path"
                    else -> "[$ip]:$port"
                }
        }

        return copy(upstream = out, serverName = serverName?.takeIf { it.isNotBlank() } ?: host)
    }

    private fun isPreResolvedEntry(entry: String): Boolean {
        val e = entry.trim()
        if (e.isEmpty()) return false
        return when (defaultTransport) {
            "doh" -> {
                if (!e.startsWith("https://")) return false
                val h = runCatching { URI(e).host }.getOrNull() ?: return false
                isLiteralIp(h)
            }
            "dot",
            "plain" -> {
                val host = splitHostPort(e)?.first ?: return false
                isLiteralIp(host)
            }
            else -> true
        }
    }

    private fun hostFromEntry(entry: String): String? {
        val e = entry.trim()
        if (e.isEmpty()) return null
        if (e.startsWith("http://") || e.startsWith("https://")) {
            return runCatching { URI(e).host }.getOrNull()
        }
        return splitHostPort(e)?.first ?: e
    }

    private fun portFromUpstreamOrDefault(): Int {
        val first = upstream.firstOrNull()?.trim().orEmpty()
        when (defaultTransport) {
            "doh" -> {
                val p = runCatching { URI(first).port }.getOrNull() ?: -1
                return if (p > 0) p else 443
            }
            "dot",
            "plain" -> {
                val p = splitHostPort(first)?.second
                if (p != null && p > 0) return p
                return if (defaultTransport == "dot") 853 else 53
            }
            else -> return 53
        }
    }

    private fun dohPathFromUpstream(): String {
        if (defaultTransport != "doh") return "/dns-query"
        val first = upstream.firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return "/dns-query"
        return try {
            val uri = URI(if (first.contains("://")) first else "https://$first")
            val path = uri.path?.takeIf { it.isNotEmpty() } ?: "/dns-query"
            if (!uri.query.isNullOrEmpty()) "$path?${uri.query}" else path
        } catch (_: Exception) {
            "/dns-query"
        }
    }

    companion object {
        const val FAKE_DNS = "198.18.0.2"

        private fun isLiteralIp(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]")
            return isIpv4(h) || h.contains(':')
        }

        private fun isIpv4(s: String): Boolean {
            val p = s.split('.')
            if (p.size != 4) return false
            return p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }

        fun splitHostPort(value: String): Pair<String, Int?>? {
            val v = value.trim()
            if (v.startsWith("[")) {
                val end = v.indexOf(']')
                if (end <= 1) return null
                val host = v.substring(1, end)
                val rest = v.substring(end + 1)
                val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() else null
                return host to port
            }
            val idx = v.lastIndexOf(':')
            if (idx > 0 && v.indexOf(':') == idx) {
                return v.substring(0, idx) to v.substring(idx + 1).toIntOrNull()
            }
            return v to null
        }
    }
}
