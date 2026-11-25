package com.notokens.monolog.connect

import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.Semaphore
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.notokens.monolog.data.SettingsStore
import org.json.JSONObject

internal data class LocalConnectState(val address: String, val pin: String, val paired: Boolean)

internal object LocalConnect {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<LocalConnectState?>(null)
    val state = mutableState.asStateFlow()
    private var active: LanSession? = null

    suspend fun start(context: Context, request: ConnectRequest) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        check(!SettingsStore(app).incognito.first()) { "Turn off incognito before sharing your journal." }
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: error("Connect your phone to Wi-Fi first.")
        val address = wifiAddress(manager, network) ?: error("Connect your phone to a private Wi-Fi network first.")
        val pairing = ConnectProtocol.pair(request)
        val listener = ServerSocket().apply {
            try { bind(InetSocketAddress(address.address, 0), 8) }
            catch (failure: Exception) { close(); throw failure }
            soTimeout = 1000
        }
        val session = LanSession(app, manager, network, address, pairing, listener)
        synchronized(this@LocalConnect) {
            active?.close()
            active = session
            mutableState.value = session.status
        }
        session.start()
    }

    @Synchronized
    fun stop() {
        active?.close()
        active = null
        mutableState.value = null
    }

    @Synchronized
    private fun ended(session: LanSession) {
        if (active === session) {
            active = null
            mutableState.value = null
        }
    }

    @Synchronized
    private fun paired(session: LanSession) {
        if (active === session) mutableState.value = session.status.copy(paired = true)
    }

    private class LanSession(
        private val context: Context,
        private val networkManager: ConnectivityManager,
        private val wifi: Network,
        private val address: LinkAddress,
        private val pairing: ConnectPairing,
        private val listener: ServerSocket
    ) {
        private val records = ConnectRecords(context)
        private val channel = pairing.channel
        private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        private val slots = Semaphore(4)
        private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val validity = SystemClock.elapsedRealtime() + 2 * 60 * 60_000L
        @Volatile private var touched = SystemClock.elapsedRealtime()
        @Volatile private var connected = false
        private val generation = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var lastRevision: Pair<Long, String>? = null
        val status = LocalConnectState("${address.address.hostAddress}:${listener.localPort}", pairing.pin, false)

        fun start() {
            workers.launch {
                SettingsStore(context).incognito.collect { if (it) close() }
            }
            workers.launch {
                records.changes.collect { generation.incrementAndGet() }
            }
            scope.launch {
                try {
                    while (!listener.isClosed && stillOnWifi()) {
                        val connection = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                        if (!sameSubnet(connection.inetAddress, address) || !slots.tryAcquire()) {
                            connection.close()
                            continue
                        }
                        if (!clients.add(connection) || listener.isClosed) {
                            connection.close()
                            slots.release()
                            continue
                        }
                        workers.launch {
                            try { connection.use { serve(it) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* Broken or malformed connections are closed. */ }
                            finally { clients.remove(connection); slots.release() }
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Closing the listener also wakes accept().
                } finally {
                    close()
                    ended(this@LanSession)
                    workers.coroutineContext[Job]?.join()
                    channel.key.fill(0)
                    records.clearReceipts(channel.session)
                }
            }
        }

        fun close() {
            runCatching { listener.close() }
            clients.forEach { runCatching { it.close() } }
            workers.cancel()
        }

        private fun stillOnWifi(): Boolean {
            val time = SystemClock.elapsedRealtime()
            return time < validity && time - touched < 5 * 60_000L &&
                !context.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
                wifiAddress(networkManager, wifi) == address
        }

        private suspend fun serve(socket: Socket) {
            socket.soTimeout = 2500
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val request = readRequest(input) ?: return
            if (request.headers["origin"] != ConnectRequest.ORIGIN ||
                request.headers["host"] != status.address || request.headers.containsKey("transfer-encoding") ||
                !stillOnWifi()) {
                respond(socket, 403, "application/octet-stream")
                return
            }
            if (request.method == "OPTIONS" && request.path in setOf("/v1/pair", "/v1/exchange")) {
                respond(socket, 204, "application/octet-stream")
                return
            }
            if (request.method == "GET" && request.path == "/v1/pair") {
                val body = JSONObject().put("session", channel.session).put("key", channel.phoneKey)
                    .put("expiresAt", pairing.expiresAt).toString().toByteArray()
                respond(socket, 200, "application/json", body)
                return
            }
            val size = request.headers["content-length"]?.toIntOrNull()
            if (request.method != "POST" || request.path != "/v1/exchange" || size == null || size !in 28..MAX_COMMAND_BYTES ||
                request.headers["content-type"]?.substringBefore(';') != "application/octet-stream") {
                respond(socket, 400, "application/octet-stream")
                return
            }
            val envelope = ByteArray(size).also(input::readFully)
            val command = try { JSONObject(ConnectProtocol.open(channel, "request", envelope).toString(Charsets.UTF_8)) }
            catch (_: AEADBadTagException) { respond(socket, 401, "application/octet-stream"); return }
            if (!stillOnWifi()) return
            touched = SystemClock.elapsedRealtime()
            if (!connected) {
                connected = true
                paired(this)
            }
            val operation = command.optString("op")
            val result = try {
                when (operation) {
                    "sync" -> sync(command.optString("since"))
                    "photo" -> records.photo(command.optString("id"))
                    "save", "delete" -> records.mutate(channel.session, pairing.expiresAt, command)
                    "disconnect" -> JSONObject().put("ok", true)
                    else -> ConnectRecords.error("Unknown request.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ConnectRecords.error("The phone could not finish this request.") }
            if (!stillOnWifi()) return
            val responsePurpose = "response|" + base64Url(envelope.copyOfRange(0, 12))
            respond(socket, 200, "application/octet-stream",
                ConnectProtocol.seal(channel, responsePurpose, result.toString().toByteArray()))
            if (operation == "disconnect") close()
        }

        private suspend fun sync(since: String): JSONObject {
            val current = generation.get()
            val cached = lastRevision
            if (cached != null && cached.first == current && cached.second == since) {
                return JSONObject().put("unchanged", true)
            }
            val result = records.snapshot(since)
            val revision = result.optJSONObject("snapshot")?.getString("revision") ?: since
            lastRevision = current to revision
            return result
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: DataInputStream): Request? {
        var remaining = 8192
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (remaining-- > 0) {
                val next = input.readUnsignedByte()
                if (next == 13) {
                    require(remaining-- > 0 && input.readUnsignedByte() == 10)
                    return bytes.toString(Charsets.US_ASCII.name())
                }
                require(next == 9 || next in 32..126)
                bytes.write(next)
            }
            throw java.io.IOException("Header limit")
        }
        val first = line().split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1") return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val text = line()
            if (text.isEmpty()) break
            val key = text.substringBefore(':').lowercase(Locale.ROOT)
            require(text.contains(':') && key.matches(Regex("[a-z0-9-]+")) && key !in headers)
            headers[key] = text.substringAfter(':').trim()
        }
        return Request(first[0], first[1], headers)
    }

    private fun respond(socket: Socket, status: Int, type: String, body: ByteArray = ByteArray(0)) {
        val reason = when (status) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; else -> "Forbidden"
        }
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Access-Control-Allow-Origin: ${ConnectRequest.ORIGIN}\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Cache-Control: no-store\r\nVary: Origin\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun wifiAddress(manager: ConnectivityManager, network: Network): LinkAddress? {
        val capability = manager.getNetworkCapabilities(network) ?: return null
        if (!capability.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capability.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && it.address.isSiteLocalAddress && it.prefixLength in 8..30
        }
    }

    private fun sameSubnet(peer: InetAddress, local: LinkAddress): Boolean {
        if (peer !is Inet4Address || !peer.isSiteLocalAddress) return false
        val remote = peer.address
        val home = local.address.address
        val fields = local.prefixLength
        for (index in 0 until fields) {
            val mask = 0x80 shr (index % 8)
            if ((remote[index / 8].toInt() xor home[index / 8].toInt()) and mask != 0) return false
        }
        return true
    }

    private const val MAX_COMMAND_BYTES = 1_650_000
}
