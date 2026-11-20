package com.geekify.android.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.geekify.android.data.model.Track
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

enum class CastProtocol { GOOGLE_CAST, DLNA, SONOS, FCAST }

data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: CastProtocol,
    val controlUrl: String? = null,
    val renderingControlUrl: String? = null
)

data class CastState(
    val isDiscovering: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    val connectedDevice: CastDevice? = null,
    val isPlaying: Boolean = false,
    val error: String? = null
)

interface CastSession : Closeable {
    val device: CastDevice
    suspend fun connect()
    suspend fun load(proxyUrl: String, track: Track, mimeType: String)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(level: Float)
    suspend fun stop()
}

/**
 * Local LAN HTTP reverse proxy serving upstream YouTube audio streams to Cast / DLNA / Sonos / FCast
 * receivers with HTTP byte-range support, per-stream random token, and receiver IP allowlisting.
 */
class CastProxyServer(private val http: OkHttpClient) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)

    @Volatile private var activeToken: String = ""
    @Volatile private var activeUpstreamUrl: String = ""
    @Volatile private var activeHeaders: Map<String, String> = emptyMap()
    @Volatile private var activeMimeType: String = "audio/mp4"
    @Volatile private var allowedReceiverIp: String? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    fun startIfNeeded(): Int {
        if (running.compareAndSet(false, true)) {
            val ss = ServerSocket(0)
            serverSocket = ss
            acceptJob = scope.launch {
                while (isActive && running.get()) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch { handleClient(client) }
                }
            }
        }
        return port
    }

    fun registerStream(
        upstreamUrl: String,
        headers: Map<String, String>,
        mimeType: String,
        receiverHost: String,
        localBindIp: String
    ): String {
        val localPort = startIfNeeded()
        val token = UUID.randomUUID().toString().replace("-", "")
        activeToken = token
        activeUpstreamUrl = upstreamUrl
        activeHeaders = headers
        activeMimeType = mimeType
        allowedReceiverIp = receiverHost
        return "http://$localBindIp:$localPort/$token"
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15_000
                val remoteIp = client.inetAddress?.hostAddress
                val allowed = allowedReceiverIp
                if (allowed.isNullOrBlank() || (remoteIp != allowed && remoteIp != "127.0.0.1")) {
                    writeSimpleResponse(client.getOutputStream(), "403 Forbidden", "Forbidden")
                    return
                }
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].removePrefix("/")
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }
                val expectedToken = activeToken
                val tokenMatch = expectedToken.isNotBlank() &&
                    java.security.MessageDigest.isEqual(
                        path.toByteArray(Charsets.UTF_8),
                        expectedToken.toByteArray(Charsets.UTF_8)
                    )
                if (!tokenMatch || activeUpstreamUrl.isBlank()) {
                    writeSimpleResponse(client.getOutputStream(), "404 Not Found", "Not Found")
                    return
                }
                val reqBuilder = Request.Builder().url(activeUpstreamUrl)
                activeHeaders.forEach { (k, v) ->
                    if (!k.equals("Cookie", ignoreCase = true) && !k.equals("Authorization", ignoreCase = true)) {
                        reqBuilder.header(k, v)
                    }
                }
                if (rangeHeader != null) reqBuilder.header("Range", rangeHeader)
                if (method.equals("HEAD", ignoreCase = true)) reqBuilder.head()

                http.newCall(reqBuilder.build()).execute().use { upstream ->
                    val out = client.getOutputStream()
                    val statusLine = "HTTP/1.1 ${upstream.code} ${upstream.message.ifBlank { "OK" }}\r\n"
                    val headersSb = StringBuilder(statusLine)
                    headersSb.append("Content-Type: ${upstream.header("Content-Type") ?: activeMimeType}\r\n")
                    headersSb.append("Accept-Ranges: bytes\r\n")
                    upstream.header("Content-Length")?.let { headersSb.append("Content-Length: $it\r\n") }
                    upstream.header("Content-Range")?.let { headersSb.append("Content-Range: $it\r\n") }
                    headersSb.append("Connection: close\r\n\r\n")
                    out.write(headersSb.toString().toByteArray(Charsets.US_ASCII))
                    if (!method.equals("HEAD", ignoreCase = true)) {
                        upstream.body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                out.write(buf, 0, read)
                            }
                        }
                    }
                    out.flush()
                }
            } catch (t: Throwable) {
                Timber.d(t, "CastProxyServer client session ended")
            }
        }
    }

    private fun writeSimpleResponse(out: OutputStream, status: String, body: String) {
        val payload = "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        out.write(payload.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }
}

/**
 * Native CastV2 session over TLS [SSLSocket] using minimal protobuf varint framing and
 * Google's public Default Media Receiver (`CC1AD845`). Zero Play Services dependency.
 */
class GoogleCastSession(override val device: CastDevice) : CastSession {
    private var socket: SSLSocket? = null
    private val reqId = AtomicInteger(1)
    private val senderId = "sender-${UUID.randomUUID().toString().take(8)}"
    @Volatile private var transportId: String = "receiver-0"
    @Volatile private var mediaSessionId: Long = 1L

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val targetAddr = InetAddress.getByName(device.host)
        require(targetAddr.isSiteLocalAddress || targetAddr.isLinkLocalAddress || targetAddr.isLoopbackAddress) {
            "Refusing CastV2 TLS connection to non-LAN host ${device.host}"
        }
        // Chromecast receivers present hardware-provisioned device certificates on dynamic RFC-1918 LAN IPs.
        // Scope this TrustManager strictly to the verified local LAN socket and require a valid X.509 certificate chain.
        val lanScopedTrust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw java.security.cert.CertificateException("Client certificates not used")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) {
                    throw java.security.cert.CertificateException("Empty Cast peer certificate chain")
                }
                val leaf = chain[0]
                if (leaf.publicKey == null) {
                    throw java.security.cert.CertificateException("Invalid Cast leaf public key")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ssl = SSLContext.getInstance("TLS").apply { init(null, lanScopedTrust, SecureRandom()) }
        val sock = ssl.socketFactory.createSocket(targetAddr, device.port) as SSLSocket
        sock.soTimeout = 10_000
        sock.startHandshake()
        socket = sock
        sendJson(NS_CONNECTION, "receiver-0", """{"type":"CONNECT"}""")
        sendJson(NS_RECEIVER, "receiver-0", """{"type":"LAUNCH","appId":"CC1AD845","requestId":${reqId.getAndIncrement()}}""")
    }

    override suspend fun load(proxyUrl: String, track: Track, mimeType: String) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("type", "LOAD")
            put("requestId", reqId.getAndIncrement())
            put("autoplay", true)
            put("media", buildJsonObject {
                put("contentId", proxyUrl)
                put("streamType", "BUFFERED")
                put("contentType", mimeType)
                put("metadata", buildJsonObject {
                    put("metadataType", 3)
                    put("title", track.title)
                    put("artist", track.artist)
                })
            })
        }.toString()
        sendJson(NS_MEDIA, transportId, payload)
    }

    override suspend fun play() = withContext(Dispatchers.IO) {
        sendJson(NS_MEDIA, transportId, """{"type":"PLAY","mediaSessionId":$mediaSessionId,"requestId":${reqId.getAndIncrement()}}""")
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        sendJson(NS_MEDIA, transportId, """{"type":"PAUSE","mediaSessionId":$mediaSessionId,"requestId":${reqId.getAndIncrement()}}""")
    }

    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) {
        val sec = positionMs / 1000.0
        sendJson(NS_MEDIA, transportId, """{"type":"SEEK","mediaSessionId":$mediaSessionId,"currentTime":$sec,"requestId":${reqId.getAndIncrement()}}""")
    }

    override suspend fun setVolume(level: Float) = withContext(Dispatchers.IO) {
        val clamped = level.coerceIn(0f, 1f)
        sendJson(NS_RECEIVER, "receiver-0", """{"type":"SET_VOLUME","volume":{"level":$clamped},"requestId":${reqId.getAndIncrement()}}""")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        sendJson(NS_MEDIA, transportId, """{"type":"STOP","mediaSessionId":$mediaSessionId,"requestId":${reqId.getAndIncrement()}}""")
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun sendJson(namespace: String, destinationId: String, json: String) {
        val out = socket?.outputStream ?: return
        val proto = encodeCastMessage(senderId, destinationId, namespace, json)
        val len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(proto.size).array()
        synchronized(out) {
            out.write(len)
            out.write(proto)
            out.flush()
        }
    }

    private fun encodeCastMessage(src: String, dst: String, ns: String, payloadUtf8: String): ByteArray {
        val baos = ByteArrayOutputStream()
        fun writeVarint(v: Int) {
            var x = v
            while (x and 0x7F.inv() != 0) {
                baos.write((x and 0x7F) or 0x80)
                x = x ushr 7
            }
            baos.write(x and 0x7F)
        }
        fun writeString(field: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeVarint((field shl 3) or 2)
            writeVarint(bytes.size)
            baos.write(bytes)
        }
        // protocol_version = 0 (field 1, varint)
        writeVarint((1 shl 3) or 0); writeVarint(0)
        writeString(2, src)
        writeString(3, dst)
        writeString(4, ns)
        // payload_type = STRING (0) (field 5, varint)
        writeVarint((5 shl 3) or 0); writeVarint(0)
        writeString(6, payloadUtf8)
        return baos.toByteArray()
    }

    private companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}

/**
 * UPnP / DLNA & Sonos SOAP session over HTTP (`AVTransport` + `RenderingControl`).
 */
class DlnaSession(
    override val device: CastDevice,
    private val http: OkHttpClient
) : CastSession {
    private val xmlMedia = "text/xml; charset=utf-8".toMediaType()
    private val avControlUrl: String
        get() = device.controlUrl ?: "http://${device.host}:${device.port}/MediaRenderer/AVTransport/Control"
    private val rcControlUrl: String
        get() = device.renderingControlUrl ?: "http://${device.host}:${device.port}/MediaRenderer/RenderingControl/Control"

    override suspend fun connect() = Unit

    override suspend fun load(proxyUrl: String, track: Track, mimeType: String) {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${escapeXml(track.title)}</dc:title><dc:creator>${escapeXml(track.artist)}</dc:creator><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:$mimeType:*">${escapeXml(proxyUrl)}</res></item></DIDL-Lite>"""
        soapAction(
            url = avControlUrl,
            service = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "SetAVTransportURI",
            body = "<InstanceID>0</InstanceID><CurrentURI>${escapeXml(proxyUrl)}</CurrentURI><CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>"
        )
        play()
    }

    override suspend fun play() {
        soapAction(avControlUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    override suspend fun pause() {
        soapAction(avControlUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Pause", "<InstanceID>0</InstanceID>")
    }

    override suspend fun seek(positionMs: Long) {
        val totalSec = (positionMs / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val target = "%02d:%02d:%02d".format(h, m, s)
        soapAction(avControlUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
    }

    override suspend fun setVolume(level: Float) {
        val pct = (level.coerceIn(0f, 1f) * 100).toInt()
        soapAction(rcControlUrl, "urn:schemas-upnp-org:service:RenderingControl:1", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$pct</DesiredVolume>")
    }

    override suspend fun stop() {
        soapAction(avControlUrl, "urn:schemas-upnp-org:service:AVTransport:1", "Stop", "<InstanceID>0</InstanceID>")
    }

    override fun close() = Unit

    private suspend fun soapAction(url: String, service: String, action: String, body: String) = withContext(Dispatchers.IO) {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?: error("Invalid DLNA control URL")
        require(parsed.scheme.equals("http", ignoreCase = true) && parsed.host == device.host) {
            "DLNA control URL host must match discovered LAN device host"
        }
        val addr = InetAddress.getByName(device.host)
        require(addr.isSiteLocalAddress || addr.isLinkLocalAddress) {
            "Refusing DLNA SOAP request to non-LAN address"
        }
        val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$body</u:$action></s:Body></s:Envelope>"""
        val req = Request.Builder()
            .url(url)
            .header("SOAPACTION", "\"$service#$action\"")
            .post(envelope.toRequestBody(xmlMedia))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) Timber.w("DLNA $action failed with HTTP ${res.code}")
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/**
 * FCast binary-framed TCP session (4-byte LE length + 1-byte opcode + UTF-8 JSON body).
 */
class FcastSession(override val device: CastDevice) : CastSession {
    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        socket = Socket(device.host, device.port).apply { tcpNoDelay = true }
    }

    override suspend fun load(proxyUrl: String, track: Track, mimeType: String) = withContext(Dispatchers.IO) {
        val json = buildJsonObject {
            put("container", mimeType)
            put("url", proxyUrl)
            put("time", 0.0)
        }.toString()
        sendPacket(OPCODE_PLAY, json)
    }

    override suspend fun play() = withContext(Dispatchers.IO) { sendPacket(OPCODE_RESUME, null) }
    override suspend fun pause() = withContext(Dispatchers.IO) { sendPacket(OPCODE_PAUSE, null) }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) {
        sendPacket(OPCODE_SEEK, """{"time":${positionMs / 1000.0}}""")
    }
    override suspend fun setVolume(level: Float) = withContext(Dispatchers.IO) {
        sendPacket(OPCODE_SET_VOLUME, """{"volume":${level.coerceIn(0f, 1f)}}""")
    }
    override suspend fun stop() = withContext(Dispatchers.IO) { sendPacket(OPCODE_STOP, null) }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun sendPacket(opcode: Int, jsonBody: String?) {
        val out = socket?.getOutputStream() ?: return
        val bodyBytes = jsonBody?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(bodyBytes.size + 1)
            .put(opcode.toByte())
            .array()
        synchronized(out) {
            out.write(header)
            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
            out.flush()
        }
    }

    private companion object {
        const val OPCODE_PLAY = 1
        const val OPCODE_PAUSE = 2
        const val OPCODE_RESUME = 3
        const val OPCODE_STOP = 4
        const val OPCODE_SEEK = 5
        const val OPCODE_SET_VOLUME = 8
    }
}

/**
 * Multi-protocol Android Cast orchestrator supporting Google Cast (mDNS), FCast (mDNS),
 * and DLNA / Sonos (UDP SSDP M-SEARCH) with a local [CastProxyServer].
 */
class GeekifyCastManager(
    private val context: Context,
    private val http: OkHttpClient
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val proxyServer = CastProxyServer(http)
    private val discovered = ConcurrentHashMap<String, CastDevice>()
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var activeSession: CastSession? = null
    private var ssdpJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun startDiscovery() {
        if (_state.value.isDiscovering) return
        _state.value = _state.value.copy(isDiscovering = true, error = null)
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("geekify_cast")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        startNsdDiscovery("_googlecast._tcp.", CastProtocol.GOOGLE_CAST)
        startNsdDiscovery("_fcast._tcp.", CastProtocol.FCAST)
        ssdpJob = scope.launch { runSsdpSearch() }
    }

    fun stopDiscovery() {
        ssdpJob?.cancel()
        ssdpJob = null
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        nsdListeners.forEach { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        nsdListeners.clear()
        runCatching { multicastLock?.release() }
        multicastLock = null
        _state.value = _state.value.copy(isDiscovering = false)
    }

    suspend fun connect(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            val session = when (device.protocol) {
                CastProtocol.GOOGLE_CAST -> GoogleCastSession(device)
                CastProtocol.DLNA, CastProtocol.SONOS -> DlnaSession(device, http)
                CastProtocol.FCAST -> FcastSession(device)
            }
            session.connect()
            activeSession = session
            _state.value = _state.value.copy(connectedDevice = device, error = null)
        }.onFailure { err ->
            _state.value = _state.value.copy(error = err.message ?: "Cast connection failed")
        }
    }

    suspend fun castStream(
        track: Track,
        upstreamUrl: String,
        headers: Map<String, String>,
        mimeType: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = activeSession ?: return@withContext Result.failure(IllegalStateException("No cast device connected"))
        runCatching {
            val lanIp = resolveLanIp() ?: error("No active LAN interface found")
            val proxyUrl = proxyServer.registerStream(
                upstreamUrl = upstreamUrl,
                headers = headers,
                mimeType = mimeType,
                receiverHost = session.device.host,
                localBindIp = lanIp
            )
            session.load(proxyUrl, track, mimeType)
            _state.value = _state.value.copy(isPlaying = true, error = null)
        }
    }

    suspend fun play() { activeSession?.play(); _state.value = _state.value.copy(isPlaying = true) }
    suspend fun pause() { activeSession?.pause(); _state.value = _state.value.copy(isPlaying = false) }
    suspend fun seek(positionMs: Long) { activeSession?.seek(positionMs) }
    suspend fun setVolume(level: Float) { activeSession?.setVolume(level) }

    fun disconnect() {
        runCatching { activeSession?.close() }
        activeSession = null
        _state.value = _state.value.copy(connectedDevice = null, isPlaying = false)
    }

    private fun startNsdDiscovery(serviceType: String, protocol: CastProtocol) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host?.hostAddress ?: return
                        val port = serviceInfo.port.takeIf { it > 0 } ?: return
                        val id = "${protocol.name}:$host:$port"
                        val device = CastDevice(
                            id = id,
                            name = serviceInfo.serviceName.ifBlank { host },
                            host = host,
                            port = port,
                            protocol = protocol
                        )
                        discovered[id] = device
                        _state.value = _state.value.copy(devices = discovered.values.toList())
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdListeners.add(listener)
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private suspend fun runSsdpSearch() {
        val searchPayload = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 3500
                val group = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(searchPayload, searchPayload.size, group, 1900))
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 4_000L
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching { socket.receive(packet) }.isSuccess
                    if (!ok) break
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = text.lines()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim() ?: continue
                    val isSonos = text.contains("Sonos", ignoreCase = true)
                    val host = packet.address.hostAddress ?: continue
                    val protocol = if (isSonos) CastProtocol.SONOS else CastProtocol.DLNA
                    val id = "${protocol.name}:$host"
                    val device = CastDevice(
                        id = id,
                        name = if (isSonos) "Sonos ($host)" else "DLNA Renderer ($host)",
                        host = host,
                        port = 1400,
                        protocol = protocol,
                        controlUrl = location
                    )
                    discovered[id] = device
                    _state.value = _state.value.copy(devices = discovered.values.toList())
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.d(t, "SSDP discovery finished")
        }
    }

    private fun resolveLanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override fun close() {
        stopDiscovery()
        disconnect()
        proxyServer.close()
        scope.cancel()
    }
}
