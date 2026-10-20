package app.almanak.web

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

data class HttpResponse(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun text(status: Int, s: String, type: String = "text/plain; charset=utf-8") = HttpResponse(status, type, s.toByteArray())
        fun json(s: String, status: Int = 200) = text(status, s, "application/json; charset=utf-8")
    }
}

/**
 * A deliberately tiny HTTP/1.1 server (one request per connection) for the phone setup page. It runs only while
 * the TV settings screen is open. Every request must carry the pairing [token] that is only shown in the QR code,
 * so another device on the LAN can't change settings.
 */
class SetupServer(private val handler: suspend (HttpRequest) -> HttpResponse) {
    val token: String = randomToken()
    private var socket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    val port: Int get() = socket?.localPort ?: -1

    fun start(preferredPort: Int = DEFAULT_PORT) {
        if (socket != null) return
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrElse { ServerSocket(0) } // port taken: any free one (the QR carries it)
        socket = s
        val sc = CoroutineScope(Dispatchers.IO + Job())
        scope = sc
        sc.launch {
            while (isActive) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch { serve(client) }
            }
        }
        Log.i("Almanak", "setup server on port ${s.localPort}")
    }

    fun stop() {
        runCatching { socket?.close() }
        scope?.cancel()
        socket = null
        scope = null
    }

    private suspend fun serve(client: Socket) = client.use { c ->
        c.soTimeout = 5_000
        val response = try {
            val req = parse(BufferedInputStream(c.getInputStream()))
            if (req == null) HttpResponse.text(400, "bad request")
            else if (!authorized(req)) HttpResponse.text(403, "Scan the QR code on the TV to open this page.")
            else handler(req)
        } catch (e: Exception) {
            Log.w("Almanak", "setup request failed: $e")
            HttpResponse.text(500, "error")
        }
        val out = c.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(response.body)
        out.flush()
    }

    /** Static, non-sensitive assets (fonts) are public: a CSS @font-face request can't carry the token header. */
    private fun authorized(r: HttpRequest) =
        r.path.startsWith("/fonts/") || r.query["t"] == token || r.headers["x-almanak-token"] == token

    companion object {
        const val DEFAULT_PORT = 8737
        private const val MAX_BODY = 64 * 1024

        private fun randomToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789" // no look-alikes
            val rnd = SecureRandom()
            return (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        internal fun parse(input: InputStream): HttpRequest? {
            fun line(): String? {
                val b = ByteArrayOutputStream()
                while (true) {
                    val ch = input.read()
                    if (ch == -1) return if (b.size() == 0) null else b.toString(Charsets.UTF_8.name())
                    if (ch == '\n'.code) return b.toString(Charsets.UTF_8.name()).trimEnd('\r')
                    if (b.size() > 8_192) return null
                    b.write(ch)
                }
            }
            val (method, target) = line()?.split(' ')?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: return null
            val headers = HashMap<String, String>()
            while (true) {
                val h = line() ?: return null
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            return HttpRequest(method.uppercase(), path, query, headers, String(body, 0, read, Charsets.UTF_8))
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "Error"
        }

        /**
         * The TV's address on the local network. Skips VPN tunnels: with a VPN on the TV the default route is a
         * tunnel, which a phone on the Wi-Fi can't reach.
         */
        fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
