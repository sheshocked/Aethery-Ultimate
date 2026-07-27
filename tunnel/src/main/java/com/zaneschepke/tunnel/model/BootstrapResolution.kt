package com.zaneschepke.tunnel.model

import com.zaneschepke.tunnel.backend.dns.TunnelDnsConfig
import com.zaneschepke.tunnel.util.PublicKey

data class BootstrapResolution(
    val peers: Map<PublicKey, DnsBootstrapResult>,
    val tunnelDns: TunnelDnsConfig?,
)
