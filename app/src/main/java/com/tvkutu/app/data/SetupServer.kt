package com.tvkutu.app.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Telefondan kur" için yerel ağda çalışan küçük HTTP sunucusu.
 *
 * - Yalnızca /s/<kod> yolu çalışır; kod her açılışta rastgele üretilir ve QR'da yer alır.
 * - Telefon formu gönderince [onSubmit] çağrılır ve sunucu kendini kapatır.
 * - Hiçbir veri internete gönderilmez; her şey aynı Wi-Fi ağı içinde kalır.
 */
class SetupServer(
    private val currentPlaylist: String,
    private val currentEpg: String,
    private val onSubmit: (playlist: String, epg: String) -> Unit,
) {
    val code: String = randomCode()
    @Volatile private var socket: ServerSocket? = null
    @Volatile var finished = false
        private set

    /** Sunucuyu başlatır ve dinlenen portu döndürür. */
    fun start(port: Int = 0): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        socket = ss
        Thread({ acceptLoop(ss) }, "tvkutu-setup").apply { isDaemon = true }.start()
        return ss.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: SocketException) {
                break
            }
            runCatching { client.use { handle(it) } }
            if (finished) {
                stop()
                break
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 10_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", ignoreCase = true)) {
                contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val expected = "/s/$code"
        when {
            path == "/favicon.ico" -> respond(out, 404, "text/plain", "")
            path != expected -> respond(out, 403, "text/html", page(
                "Bağlantı geçersiz",
                "<p>Bu bağlantının süresi dolmuş. TV'de karekodu yeniden açıp tekrar okut.</p>"
            ))
            method == "GET" -> respond(out, 200, "text/html", formPage(expected))
            method == "POST" -> {
                if (contentLength !in 0..64_000) {
                    respond(out, 413, "text/plain", "Too large"); return
                }
                val body = String(readBytes(input, contentLength), Charsets.UTF_8)
                val form = parseForm(body)
                val playlist = form["playlist"].orEmpty().trim()
                val epg = form["epg"].orEmpty().trim()
                val error = validate(playlist, epg)
                if (error != null) {
                    respond(out, 200, "text/html", formPage(expected, playlist, epg, error))
                } else {
                    respond(out, 200, "text/html", page(
                        "Gönderildi ✓",
                        "<p>Linkler TV'ye ulaştı. Liste TV'de yükleniyor; bu sayfayı kapatabilirsin.</p>"
                    ))
                    finished = true
                    onSubmit(playlist, epg)
                }
            }
            else -> respond(out, 405, "text/plain", "")
        }
    }

    // ---- HTML ----

    private fun formPage(action: String, pl: String = currentPlaylist, epg: String = currentEpg, error: String? = null) =
        page("TvKutu kurulumu", """
            <p class="muted">Linkleri buraya yapıştır ve <b>Gönder</b>'e dokun. TV otomatik olarak listeyi yükleyecek.</p>
            ${if (error != null) "<div class=\"err\">${esc(error)}</div>" else ""}
            <form method="post" action="$action" autocomplete="off">
              <label for="pl">Playlist linki (m3u / m3u8)</label>
              <div class="row">
                <input id="pl" name="playlist" type="url" inputmode="url" placeholder="https://…/playlist.m3u" value="${esc(pl)}" required>
                <button type="button" class="ghost" onclick="pasteTo('pl')">Yapıştır</button>
              </div>
              <label for="epg">Yayın akışı / EPG linki <span class="muted">(isteğe bağlı)</span></label>
              <div class="row">
                <input id="epg" name="epg" type="url" inputmode="url" placeholder="https://…/epg.xml.gz" value="${esc(epg)}">
                <button type="button" class="ghost" onclick="pasteTo('epg')">Yapıştır</button>
              </div>
              <p class="hint">Boş bırakırsan listedeki <code>url-tvg</code> linki kullanılır.</p>
              <button type="submit" class="primary">Gönder</button>
            </form>
            <p class="muted small">TvKutu yalnızca bir oynatıcıdır; kanal içermez. Yasal olarak abone olduğun sağlayıcının linkini kullan.</p>
            <script>
              async function pasteTo(id){
                try { const t = await navigator.clipboard.readText(); if(t) document.getElementById(id).value = t.trim(); }
                catch(e){ document.getElementById(id).focus(); }
              }
            </script>
        """.trimIndent())

    private fun page(title: String, body: String) = """
        <!doctype html><html lang="tr"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>${esc(title)}</title>
        <style>
          :root{color-scheme:dark}
          *{box-sizing:border-box}
          body{margin:0;background:#0e1116;color:#eceff4;font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
          main{max-width:560px;margin:0 auto;padding:28px 18px 40px}
          .brand{color:#3ddc97;font-weight:700;font-size:15px;letter-spacing:.06em;text-transform:uppercase}
          h1{font-size:24px;margin:4px 0 12px}
          label{display:block;font-weight:600;margin:18px 0 6px}
          .row{display:flex;gap:8px}
          input{flex:1;min-width:0;padding:14px;border-radius:12px;border:1px solid #2b313d;background:#181c23;color:#eceff4;font-size:16px}
          input:focus{outline:none;border-color:#3ddc97}
          button{font:inherit;border-radius:12px;cursor:pointer}
          .ghost{padding:0 14px;border:1px solid #2b313d;background:transparent;color:#9aa3b2}
          .primary{width:100%;margin-top:22px;padding:15px;border:0;background:#3ddc97;color:#0e1116;font-weight:700;font-size:17px}
          .muted{color:#9aa3b2}.small{font-size:13px;margin-top:28px}.hint{color:#9aa3b2;font-size:13px;margin:6px 0 0}
          .err{background:#3a1c1f;border:1px solid #ff6b6b;color:#ffb3b3;padding:12px;border-radius:12px;margin-top:12px}
          code{background:#181c23;padding:1px 5px;border-radius:5px}
        </style></head>
        <body><main><div class="brand">TvKutu</div><h1>${esc(title)}</h1>$body</main></body></html>
    """.trimIndent()

    // ---- yardımcılar ----

    private fun respond(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            413 -> "Payload Too Large"; else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
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
        fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { it.isNotEmpty() }.associate { kv ->
                val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                k to v
            }

        fun validate(playlist: String, epg: String): String? {
            fun ok(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)
            return when {
                playlist.isEmpty() -> "Playlist linki boş olamaz."
                !ok(playlist) -> "Playlist linki http:// veya https:// ile başlamalı."
                epg.isNotEmpty() && !ok(epg) -> "EPG linki http:// veya https:// ile başlamalı."
                else -> null
            }
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        /** TV'nin yerel ağdaki IPv4 adresi (Wi-Fi veya kablolu). */
        fun localIpv4(): String? {
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
                .filter { (_, a) -> !a.isLoopbackAddress && !a.isLinkLocalAddress }
            // Önce wlan/eth gibi gerçek arayüzleri, sonra özel ağ adreslerini tercih et.
            return (candidates.firstOrNull { (n, a) -> a.isSiteLocalAddress && (n.startsWith("wlan") || n.startsWith("eth")) }
                ?: candidates.firstOrNull { (_, a) -> a.isSiteLocalAddress }
                ?: candidates.firstOrNull())?.second?.hostAddress
        }

        private fun randomCode(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}
