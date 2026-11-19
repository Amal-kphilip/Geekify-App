package com.geekify.android.player.extraction

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PlaybackNetworkState(
    val connected: Boolean = true,
    val metered: Boolean = false,
    val transport: String = "unknown",
    val generation: Long = 0L
) {
    fun healthScope(): String = "$transport:${if (metered) "metered" else "unmetered"}"
}

/**
 * Observes Android connectivity transitions (Wi-Fi <-> cellular, offline <-> online) so
 * [com.geekify.android.player.StreamResolver] and [com.geekify.android.player.PlaybackService]
 * can recover from network drops without restarting valid streams unnecessarily.
 */
class PlaybackNetworkMonitor(context: Context) : Closeable {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val generationCounter = AtomicLong(0L)
    private val registered = AtomicBoolean(false)
    private val _state = MutableStateFlow(readCurrentState())
    val state: StateFlow<PlaybackNetworkState> = _state.asStateFlow()

    val current: PlaybackNetworkState get() = _state.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish()
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm?.registerNetworkCallback(request, callback)
            registered.set(true)
        }
    }

    override fun close() {
        if (registered.compareAndSet(true, false)) {
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }

    private fun publish() {
        val next = readCurrentState()
        val prev = _state.value
        if (prev.connected != next.connected || prev.transport != next.transport || prev.metered != next.metered) {
            _state.value = next.copy(generation = generationCounter.incrementAndGet())
        }
    }

    private fun readCurrentState(): PlaybackNetworkState {
        val manager = cm ?: return PlaybackNetworkState(connected = true)
        val active = manager.activeNetwork ?: return PlaybackNetworkState(connected = false, transport = "none")
        val caps = manager.getNetworkCapabilities(active)
            ?: return PlaybackNetworkState(connected = false, transport = "none")
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return PlaybackNetworkState(
            connected = hasInternet,
            metered = manager.isActiveNetworkMetered,
            transport = transport,
            generation = generationCounter.get()
        )
    }
}
