package com.zaneschepke.tunnel.backend.dns

import com.zaneschepke.networkmonitor.StableNetworkEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class DnsUnderlayNetworkBridge(private val stableNetworkEngine: StableNetworkEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // keep native in sync with the current active network handle
    init {
        scope.launch {
            stableNetworkEngine.stableState
                .distinctUntilChangedBy { it?.state?.activeNetwork?.key() }
                .collect { snapshot ->
                    val network = snapshot?.state?.activeNetwork?.network
                    NativeDnsResolver.setUnderlayNetwork(network)
                }
        }
    }
}
