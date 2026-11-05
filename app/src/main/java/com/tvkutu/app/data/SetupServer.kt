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
    /** Mevcut linkler (ad, adres) — form bunlarla dolu açılır. */
    private val currentSources: List<Pair<String, String>>,
    private val currentEpg: String,
    private val onSubmit: (entries: List<Pair<String, String>>, epg: String) -> Unit,
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
                val sub = toSubmission(parseFormList(body))
                val error = validate(sub)
                if (error != null) {
                    respond(out, 200, "text/html", formPage(expected, sub.entries, sub.epg, error))
                } else {
                    finished = true
                    onSubmit(sub.entries, sub.epg)
                    val list = sub.entries.joinToString("") { (n, u) ->
                        "<li><b>${esc(n.ifEmpty { "Adsız" })}</b><br><span class=\"muted small2\">${esc(u)}</span></li>"
                    }
                    respond(out, 200, "text/html", page(
                        "Gönderildi ✓",
                        "<p>${sub.entries.size} link TV'ye ulaştı. Listeler TV'de yükleniyor; bu sayfayı kapatabilirsin.</p><ul class=\"sent\">$list</ul>"
                    ))
                }
            }
            else -> respond(out, 405, "text/plain", "")
        }
    }

    // ---- HTML ----

    private fun formPage(
        action: String,
        entries: List<Pair<String, String>> = currentSources,
        epg: String = currentEpg,
        error: String? = null,
    ): String {
        val rows = (entries + ("" to "")).joinToString("\n") { (n, u) -> rowHtml(n, u) }
        return page("TvKutu kurulumu", """
            <p class="muted">Bir ya da birden çok playlist linki ekle, <b>Gönder</b>'e dokun. Her linkin kategorileri TV'de ayrı ayrı görünür.</p>
            ${if (error != null) "<div class=\"err\">${esc(error)}</div>" else ""}
            <form method="post" action="$action" autocomplete="off">
              <h2>Playlist linkleri</h2>
              <div id="rows">
              $rows
              </div>
              <template id="tpl">${rowHtml("", "")}</template>
              <button type="button" class="ghost wide" onclick="addRow()">＋ Link ekle</button>

              <details>
                <summary>Toplu ekle (her satıra bir link)</summary>
                <textarea name="bulk" rows="5" placeholder="https://…/liste1.m3u&#10;Spor https://…/liste2.m3u8"></textarea>
                <p class="hint">Satırın başına isterseniz bir ad yazabilirsiniz: <code>Spor https://…</code></p>
              </details>

              <h2>Yayın akışı (EPG) <span class="muted">— isteğe bağlı</span></h2>
              <textarea name="epg" rows="2" placeholder="https://…/epg.xml.gz">${esc(epg)}</textarea>
              <p class="hint">Birden çok EPG linkini alt alta yazabilirsin. Boş bırakırsan listelerdeki <code>url-tvg</code> linkleri kullanılır.</p>

              <button type="submit" class="primary">Gönder</button>
            </form>
            <p class="muted small">TvKutu yalnızca bir oynatıcıdır; kanal içermez. Yasal olarak abone olduğun sağlayıcının linklerini kullan.</p>
            <script>
              function addRow(){
                const t=document.getElementById('tpl').content.firstElementChild.cloneNode(true);
                document.getElementById('rows').appendChild(t);
                t.querySelector('.u').focus();
              }
              function delRow(b){
                const rows=document.querySelectorAll('#rows .src');
                const r=b.closest('.src');
                if(rows.length>1){ r.remove(); } else { r.querySelectorAll('input').forEach(i=>i.value=''); }
              }
              async function paste(b){
                const i=b.closest('.src').querySelector('.u');
                try{ const t=await navigator.clipboard.readText(); if(t) i.value=t.trim(); }catch(e){ i.focus(); }
              }
            </script>
        """.trimIndent())
    }

    private fun rowHtml(name: String, url: String) = """
        <div class="src">
          <div class="row">
            <input class="n" name="name" type="text" placeholder="Ad (ör. Spor, Ulusal)" value="${esc(name)}">
            <button type="button" class="ghost del" onclick="delRow(this)" aria-label="Sil">✕</button>
          </div>
          <div class="row">
            <input class="u" name="url" type="text" inputmode="url" autocapitalize="off" spellcheck="false" placeholder="https://…/playlist.m3u" value="${esc(url)}">
            <button type="button" class="ghost" onclick="paste(this)">Yapıştır</button>
          </div>
        </div>
    """.trimIndent()

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
          h2{font-size:17px;margin:26px 0 10px}
          .src{background:#151920;border:1px solid #232833;border-radius:14px;padding:10px;margin-bottom:10px}
          .src .row+.row{margin-top:8px}
          .src .n{padding:10px 12px;font-size:15px}
          .del{padding:0 14px}
          .ghost.wide{width:100%;padding:13px;margin-top:2px;color:#3ddc97;border-color:#2f6b52}
          textarea{width:100%;padding:12px;border-radius:12px;border:1px solid #2b313d;background:#181c23;color:#eceff4;font:15px/1.4 ui-monospace,monospace;resize:vertical}
          textarea:focus{outline:none;border-color:#3ddc97}
          details{margin-top:14px}summary{color:#9aa3b2;cursor:pointer;padding:6px 0}
          .sent{padding-left:18px}.sent li{margin:8px 0}.small2{font-size:13px;word-break:break-all}
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
        data class Submission(val entries: List<Pair<String, String>>, val epg: String)

        /** application/x-www-form-urlencoded gövdesini sıralı (ad, değer) listesine çevirir. */
        fun parseFormList(body: String): List<Pair<String, String>> =
            body.split('&').filter { it.isNotEmpty() }.map { kv ->
                val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                k to v
            }

        private val urlRegex = Regex("""(?i)\bhttps?://\S+""")

        /**
         * Form alanlarını linklere çevirir: her "name" kendisinden sonraki "url" ile eşleşir.
         * Bir url alanına birden çok link yapıştırılmışsa hepsi ayrı link olur.
         * "bulk" alanındaki her satır ayrı bir linktir ("Ad https://…" biçimi de olur).
         */
        fun toSubmission(form: List<Pair<String, String>>): Submission {
            val entries = ArrayList<Pair<String, String>>()
            var pendingName = ""
            var epg = ""
            fun addAll(name: String, text: String) {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return
                val urls = urlRegex.findAll(trimmed).map { it.value.trimEnd(',', ';') }.toList()
                if (urls.isEmpty()) {
                    entries += name.trim() to trimmed // geçersiz; doğrulama uyaracak
                } else {
                    urls.forEachIndexed { i, u ->
                        val n = if (urls.size == 1) name.trim() else if (name.isBlank()) "" else "${name.trim()} ${i + 1}"
                        entries += n to u
                    }
                }
            }
            for ((k, v) in form) {
                when (k) {
                    "name" -> pendingName = v
                    "url", "playlist" -> { addAll(pendingName, v); pendingName = "" }
                    "bulk" -> v.lines().forEach { line ->
                        val m = urlRegex.find(line)
                        if (m != null) addAll(line.substring(0, m.range.first).trim().trimEnd('|', ',', ':', '-').trim(), line.substring(m.range.first))
                        else if (line.isNotBlank()) addAll("", line)
                    }
                    "epg" -> epg = v.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                }
            }
            // Aynı link iki kez eklendiyse birini at.
            val unique = entries.distinctBy { it.second }
            return Submission(unique, epg)
        }

        fun validate(sub: Submission): String? {
            fun ok(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)
            val bad = sub.entries.firstOrNull { !ok(it.second) }
            val badEpg = sub.epg.lines().map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { !ok(it) }
            return when {
                sub.entries.isEmpty() -> "En az bir playlist linki gir."
                bad != null -> "Bu link http:// veya https:// ile başlamalı: ${bad.second.take(60)}"
                sub.entries.size > 20 -> "En fazla 20 link eklenebilir."
                badEpg != null -> "EPG linki http:// veya https:// ile başlamalı: ${badEpg.take(60)}"
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
