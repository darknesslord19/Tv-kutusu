package com.tvkutu.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Telefon kumandası: uygulama açıkken yerel ağda çalışan küçük HTTP sunucusu.
 *
 * - Yalnızca `/s/<kod>` altı çalışır; kod cihazda kalıcıdır (telefondaki yer imi çalışmaya devam eder).
 * - `GET /s/<kod>` telefon sayfasını, `POST /s/<kod>/api/<işlem>` JSON yanıtı döndürür.
 * - Hiçbir veri internete gönderilmez; her şey aynı Wi‑Fi ağı içinde kalır.
 */
class RemoteServer(
    val code: String,
    private val page: () -> String,
    /** İşlem adı + JSON gövde → JSON yanıt. Sunucu iş parçacığından çağrılır. */
    private val handler: (action: String, body: Map<String, Any?>) -> Map<String, Any?>,
    /**
     * Wi‑Fi eşitleme uç noktaları (`/sync/<işlem>`): işlem, istek başlıkları (küçük harf;
     * ayrıca "x-remote-addr") ve JSON gövde → JSON yanıt.
     */
    private val syncHandler: ((action: String, headers: Map<String, String>, body: Map<String, Any?>) -> Map<String, Any?>)? = null,
) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning get() = socket?.isClosed == false
    val port get() = socket?.localPort ?: -1

    /** Tercih edilen portu dener, doluysa rastgele bir port alır. Dinlenen portu döndürür. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(preferredPort))
        } catch (e: BindException) {
            ss.bind(InetSocketAddress(0))
        }
        socket = ss
        val p = Executors.newFixedThreadPool(4) { r -> Thread(r, "tvkutu-remote").apply { isDaemon = true } }
        pool = p
        Thread({ acceptLoop(ss, p) }, "tvkutu-remote-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(ss: ServerSocket, p: ExecutorService) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: SocketException) {
                break
            }
            try {
                p.execute { runCatching { client.use { handle(it) } } }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val name = h.substringBefore(':').trim().lowercase()
            if (headers.size < 40) headers[name] = h.substringAfter(':', "").trim()
            if (name == "content-length") contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        headers["x-remote-addr"] = s.inetAddress?.hostAddress.orEmpty()

        val base = "/s/$code"
        when {
            path == "/favicon.ico" -> respond(out, 404, "text/plain", "")
            path.startsWith("/sync/") && syncHandler != null -> {
                val action = path.removePrefix("/sync/").trim('/')
                if (!action.matches(Regex("[a-z]{1,16}")) || contentLength !in 0..MAX_BODY) {
                    respondJson(out, 400, mapOf("error" to "Geçersiz istek")); return
                }
                val body = if (contentLength > 0) {
                    try {
                        Json.parseObject(String(readBytes(input, contentLength), Charsets.UTF_8))
                    } catch (e: Exception) {
                        respondJson(out, 400, mapOf("error" to "Geçersiz istek")); return
                    }
                } else emptyMap()
                val result = try {
                    syncHandler.invoke(action, headers, body)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Beklenmeyen hata"))
                }
                respondJson(out, 200, result)
            }
            path == base || path == "$base/" -> {
                if (method == "GET") respond(out, 200, "text/html", page()) else respond(out, 405, "text/plain", "")
            }
            path.startsWith("$base/api/") -> {
                val action = path.removePrefix("$base/api/").trim('/')
                if (!action.matches(Regex("[a-zA-Z]{1,32}"))) {
                    respondJson(out, 404, mapOf("error" to "Bilinmeyen işlem")); return
                }
                if (contentLength !in 0..MAX_BODY) {
                    respondJson(out, 413, mapOf("error" to "İstek çok büyük")); return
                }
                val body = if (method == "POST" && contentLength > 0) {
                    try {
                        Json.parseObject(String(readBytes(input, contentLength), Charsets.UTF_8))
                    } catch (e: Exception) {
                        respondJson(out, 400, mapOf("error" to "Geçersiz istek")); return
                    }
                } else emptyMap()
                val result = try {
                    handler(action, body)
                } catch (e: Exception) {
                    mapOf("error" to (e.message ?: "Beklenmeyen hata"))
                }
                respondJson(out, 200, result)
            }
            else -> respond(out, 403, "text/html", expiredPage())
        }
    }

    private fun expiredPage() = """
        <!doctype html><html lang="tr"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>TvKutu</title>
        <style>body{margin:0;background:#0e1116;color:#eceff4;font:16px/1.5 system-ui,sans-serif}
        main{max-width:520px;margin:0 auto;padding:40px 20px}b{color:#3ddc97}</style></head>
        <body><main><b>TvKutu</b><h1>Bağlantı geçersiz</h1>
        <p>Bu adres artık kullanılmıyor. TV'de <b>Hesap değiştir</b> ekranındaki karekodu yeniden okut.</p></main></body></html>
    """.trimIndent()

    private fun respondJson(out: OutputStream, status: Int, body: Map<String, Any?>) =
        respond(out, status, "application/json", Json.write(body))

    private fun respond(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8_192) return null
        }
        return buf.toString(Charsets.UTF_8.name())
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(data, read, n - read)
            if (r == -1) break
            read += r
        }
        return if (read == n) data else data.copyOf(read)
    }

    companion object {
        const val MAX_BODY = 2_000_000

        /** TV'nin yerel ağdaki IPv4 adresi (Wi-Fi veya kablolu). */
        fun localIpv4(): String? {
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
                .filter { (_, a) -> !a.isLoopbackAddress && !a.isLinkLocalAddress }
            return (candidates.firstOrNull { (n, a) -> a.isSiteLocalAddress && (n.startsWith("wlan") || n.startsWith("eth")) }
                ?: candidates.firstOrNull { (_, a) -> a.isSiteLocalAddress }
                ?: candidates.firstOrNull())?.second?.hostAddress
        }
    }
}
