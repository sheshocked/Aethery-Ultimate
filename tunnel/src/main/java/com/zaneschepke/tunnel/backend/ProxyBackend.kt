package com.zaneschepke.tunnel.backend

import androidx.annotation.Keep
import timber.log.Timber

@Keep
internal object ProxyBackend {
    external fun awgStartProxy(
        ifName: String,
        config: String,
        uapiPath: String,
        bypass: Int,
        dnsConfigJson: String?,
    ): Int

    external fun awgUpdateProxyTunnelPeers(handle: Int, settings: String): Int

    external fun awgTurnProxyTunnelOff(handle: Int)

    external fun awgGetProxyConfig(handle: Int): String

    fun setSocketProtector(sp: SocketProtector?) {
        Timber.d("setSocketProtector called with ${if (sp != null) "protector" else "null"}")
        awgSetSocketProtector(sp)
    }

    private external fun awgSetSocketProtector(sp: SocketProtector?)
}
