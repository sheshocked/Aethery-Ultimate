package com.zaneschepke.tunnel.backend

import androidx.annotation.Keep
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.inject

@Keep
internal object TunnelStatusBridge : KoinComponent {
    private val callback: NativeTunnelCallback by inject()

    @Keep
    @JvmStatic
    fun onStatusChanged(handle: Int, code: Int) {
        callback.handleNativeStatusChange(handle, code)
    }
}
