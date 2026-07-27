package com.zaneschepke.tunnel.backend

interface NativeTunnelCallback {
    fun handleNativeStatusChange(handle: Int, code: Int)
}
