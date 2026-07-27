package com.zaneschepke.tunnel.backend.dns

import android.net.Network
import androidx.annotation.Keep
import com.zaneschepke.tunnel.model.DnsBootstrapResult
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

@Keep
internal object NativeDnsResolver {

    @OptIn(ExperimentalAtomicApi::class)
    private val underlayNetwork = AtomicReference<Network?>(null)
    @OptIn(ExperimentalAtomicApi::class) private val underlayNetworkHandle = AtomicLong(0L)

    private const val NATIVE_RESOLUTION_TIMEOUT_MILLIS = 7_000L

    private val callbacks = ConcurrentHashMap<Long, (String) -> Unit>()
    @OptIn(ExperimentalAtomicApi::class) private val nextId = AtomicLong(0)

    private external fun startBootstrapResolution(
        id: Long,
        host: String,
        protocol: String,
        resolvedUpstream: String,
        originalUpstream: String,
        bypass: Int,
    )

    @JvmStatic private external fun setUnderlayNetworkHandleNative(handle: Long)

    @OptIn(ExperimentalAtomicApi::class)
    @JvmStatic
    fun setUnderlayNetwork(network: Network?) {
        underlayNetwork.store(network)
        val handle = network?.networkHandle ?: 0L
        val previous = underlayNetworkHandle.exchange(handle)
        if (previous != handle) {
            setUnderlayNetworkHandleNative(handle)
            Timber.d("Underlay network handle updated: $previous to $handle")
        }
    }

    @Keep
    @JvmStatic
    fun onResolutionComplete(id: Long, result: String) {
        val callback = callbacks.remove(id)
        callback?.invoke(result)
    }

    @OptIn(ExperimentalAtomicApi::class)
    @Keep
    @JvmStatic
    fun lookupOnUnderlayNetwork(host: String, networkFamily: String): String {
        val network = underlayNetwork.load()
        if (network == null) {
            Timber.w("lookupOnUnderlayNetwork: no underlay Network for $host")
            return ""
        }
        return try {
            val addrs = network.getAllByName(host)
            val filtered =
                when (networkFamily) {
                    "ip4" -> addrs.filterIsInstance<Inet4Address>()
                    "ip6" -> addrs.filterIsInstance<Inet6Address>()
                    else -> addrs.toList()
                }
            filtered.mapNotNull { it.hostAddress }.joinToString("\n")
        } catch (e: Exception) {
            Timber.e(e, "lookupOnUnderlayNetwork failed for $host")
            ""
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun resolveHostBootstrap(
        host: String,
        protocol: String,
        resolvedUpstream: String,
        originalUpstream: String,
        bypass: Boolean,
    ): DnsBootstrapResult =
        withContext(Dispatchers.IO) {
            val id = nextId.incrementAndFetch()
            val bypassOption = if (bypass) 1 else 0

            try {
                val rawResult =
                    withTimeout(NATIVE_RESOLUTION_TIMEOUT_MILLIS.milliseconds) {
                        suspendCancellableCoroutine { continuation ->
                            callbacks[id] = { raw -> continuation.resumeWith(Result.success(raw)) }

                            continuation.invokeOnCancellation {
                                val removed = callbacks.remove(id)
                                if (removed != null) {
                                    Timber.d("DNS bootstrap cancelled for host=$host id=$id")
                                }
                            }

                            startBootstrapResolution(
                                id = id,
                                host = host,
                                protocol = protocol,
                                resolvedUpstream = resolvedUpstream,
                                originalUpstream = originalUpstream,
                                bypass = bypassOption,
                            )
                        }
                    }

                if (rawResult.startsWith("ERR|")) {
                    throw RuntimeException(rawResult.removePrefix("ERR|"))
                }

                val parts = rawResult.split(";")
                val v4 =
                    parts
                        .firstOrNull { it.startsWith("v4=") }
                        ?.removePrefix("v4=")
                        ?.takeIf { it.isNotBlank() }
                        ?.split(",") ?: emptyList()

                val v6 =
                    parts
                        .firstOrNull { it.startsWith("v6=") }
                        ?.removePrefix("v6=")
                        ?.takeIf { it.isNotBlank() }
                        ?.split(",") ?: emptyList()

                DnsBootstrapResult(ipv4 = v4, ipv6 = v6)
            } catch (e: TimeoutCancellationException) {
                callbacks.remove(id)
                Timber.e(e, "DNS bootstrap timed out for host=$host after 7 seconds")
                throw RuntimeException("DNS bootstrap timed out for $host", e)
            } catch (e: Exception) {
                callbacks.remove(id)
                Timber.w(e, "DNS bootstrap failed for host=$host")
                throw e
            }
        }
}
