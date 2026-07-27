package com.zaneschepke.wireguardautotunnel.core.orchestration

import com.zaneschepke.tunnel.backend.Backend
import com.zaneschepke.tunnel.model.DnsBoostrapConfig
import com.zaneschepke.tunnel.model.DnsBoostrapMode
import com.zaneschepke.wireguardautotunnel.domain.enums.BootstrapDnsProtocol
import com.zaneschepke.wireguardautotunnel.domain.model.DnsSettings

class DnsSettingsCoordinator(private val backend: Backend) {

    suspend fun appyDnsSettings(dnsSettings: DnsSettings) {
        val mode =
            when (dnsSettings.bootstrapDnsProtocol) {
                BootstrapDnsProtocol.SYSTEM -> DnsBoostrapMode.System
                BootstrapDnsProtocol.DOH ->
                    DnsBoostrapMode.Custom(DnsBoostrapConfig.DoH(dnsSettings.bootstrapDnsEndpoint))
                BootstrapDnsProtocol.DOT ->
                    DnsBoostrapMode.Custom(DnsBoostrapConfig.DoT(dnsSettings.bootstrapDnsEndpoint))
                BootstrapDnsProtocol.UDP ->
                    DnsBoostrapMode.Custom(
                        DnsBoostrapConfig.Plain(dnsSettings.bootstrapDnsEndpoint)
                    )
            }

        backend.setBootstrapDnsMode(mode)
    }
}
