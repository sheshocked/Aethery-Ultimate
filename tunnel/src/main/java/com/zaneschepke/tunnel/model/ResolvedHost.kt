package com.zaneschepke.tunnel.model

import com.zaneschepke.tunnel.util.Host

data class ResolvedHost(val host: Host, val forcedPort: Int? = null)
