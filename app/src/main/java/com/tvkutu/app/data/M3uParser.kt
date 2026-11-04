package com.tvkutu.app.data

/**
 * Genişletilmiş M3U (#EXTM3U) ayrıştırıcı.
 * Desteklenenler: tvg-id, tvg-name, tvg-logo, group-title, #EXTGRP,
 * #EXTVLCOPT:http-user-agent / http-referrer, başlıkta url-tvg / x-tvg-url.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(text: String, defaultGroup: String = "Diğer"): Playlist {
        val channels = ArrayList<Channel>()
        var epgUrl: String? = null

        var pendingName: String? = null
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingGroup: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        val usedKeys = HashSet<String>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttrs(line)
                    val tvg = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                    if (!tvg.isNullOrBlank()) {
                        // Birden çok link virgülle ayrılmış olabilir; ilkini al.
                        epgUrl = tvg.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    }
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val comma = firstCommaOutsideQuotes(body)
                    val attrPart = if (comma >= 0) body.substring(0, comma) else body
                    pendingName = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    pendingAttrs = parseAttrs(attrPart)
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingGroup = line.substringAfter(':').trim().ifEmpty { null }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val k = opt.substringBefore('=').trim().lowercase()
                    val v = opt.substringAfter('=', "").trim()
                    when (k) {
                        "http-user-agent" -> userAgent = v
                        "http-referrer", "http-referer" -> referrer = v
                    }
                }

                line.startsWith("#") -> Unit // bilinmeyen etiketleri yok say

                else -> {
                    // URL satırı. Bazı listeler "url|User-Agent=...&Referer=..." biçimi kullanır.
                    var url = line
                    var ua = userAgent
                    var ref = referrer
                    val pipe = line.indexOf('|')
                    if (pipe > 0) {
                        url = line.substring(0, pipe)
                        line.substring(pipe + 1).split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim().lowercase()
                            val v = kv.substringAfter('=', "").trim()
                            when (k) {
                                "user-agent" -> ua = v
                                "referer", "referrer" -> ref = v
                            }
                        }
                    }

                    val attrs = pendingAttrs
                    val tvgName = attrs["tvg-name"]?.ifBlank { null }
                    val name = pendingName?.ifBlank { null } ?: tvgName ?: url
                    val group = attrs["group-title"]?.ifBlank { null }
                        ?: pendingGroup ?: defaultGroup
                    val tvgId = attrs["tvg-id"]?.ifBlank { null }

                    var key = tvgId?.let { "id:$it" } ?: "n:$name|$url"
                    if (!usedKeys.add(key)) {
                        key = "n:$name|$url"
                        var n = 2
                        while (!usedKeys.add(key)) key = "n:$name|$url#${n++}"
                    }

                    channels += Channel(
                        key = key,
                        name = name,
                        url = url,
                        logo = attrs["tvg-logo"]?.ifBlank { null },
                        group = group,
                        tvgId = tvgId,
                        tvgName = tvgName,
                        userAgent = ua,
                        referrer = ref,
                    )

                    pendingName = null
                    pendingAttrs = emptyMap()
                    pendingGroup = null
                    userAgent = null
                    referrer = null
                }
            }
        }
        return Playlist(channels, epgUrl)
    }

    private fun parseAttrs(s: String): Map<String, String> =
        attrRegex.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
