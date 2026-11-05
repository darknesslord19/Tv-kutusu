package com.tvkutu.app.data

/**
 * Küçük, bağımlılıksız JSON okuyucu/yazıcı (telefon kumandası ve yedek dosyası için).
 * Nesneler Map<String, Any?>, diziler List<Any?>, sayılar Long/Double olarak okunur.
 */
object Json {

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> str(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(k.toString(), sb); sb.append(':'); write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(x, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.toList(), sb)
            else -> str(v.toString(), sb)
        }
    }

    private fun str(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c") // HTML içine gömülünce güvenli kalsın
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("JSON sonunda fazladan veri")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("JSON nesnesi bekleniyordu")

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("Beklenmeyen son")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Geçersiz JSON: '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Geçersiz JSON")
            i += word.length
            return v
        }

        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }

        fun string(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Kapanmamış metin")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun obj(): Map<String, Any?> {
            if (++depth > 64) throw IllegalArgumentException("JSON çok derin")
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s.getOrNull(i) == '}') { i++; depth--; return m }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') throw IllegalArgumentException("Anahtar bekleniyordu")
                val k = string()
                ws()
                if (s.getOrNull(i) != ':') throw IllegalArgumentException("':' bekleniyordu")
                i++
                ws()
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; depth--; return m }
                    else -> throw IllegalArgumentException("',' veya '}' bekleniyordu")
                }
            }
        }

        fun arr(): List<Any?> {
            if (++depth > 64) throw IllegalArgumentException("JSON çok derin")
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s.getOrNull(i) == ']') { i++; depth--; return l }
            while (true) {
                ws()
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; depth--; return l }
                    else -> throw IllegalArgumentException("',' veya ']' bekleniyordu")
                }
            }
        }
    }
}

/** Yedek dosyası biçimi: hesaplar (ve isteğe bağlı ayarlar). */
object Backup {
    data class Account(val name: String, val url: String, val color: String, val epg: String, val locked: Boolean)

    fun build(accounts: List<Source>, settings: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "app" to "TvKutu",
        "v" to 1,
        "createdMs" to System.currentTimeMillis(),
        "accounts" to accounts.map {
            linkedMapOf("name" to it.name, "url" to it.url, "color" to it.color, "epg" to it.epg, "locked" to it.locked)
        },
        "settings" to settings,
    )

    /** Yedeği okur; geçersizse açıklayıcı bir hata fırlatır. */
    fun parse(data: Any?): Pair<List<Account>, Map<String, Any?>> {
        val m = data as? Map<*, *> ?: throw IllegalArgumentException("Bu bir TvKutu yedeği değil")
        if (m["app"] != "TvKutu") throw IllegalArgumentException("Bu bir TvKutu yedeği değil")
        val list = m["accounts"] as? List<*> ?: throw IllegalArgumentException("Yedekte hesap yok")
        val accounts = list.mapNotNull { a ->
            val o = a as? Map<*, *> ?: return@mapNotNull null
            val url = (o["url"] as? String)?.trim().orEmpty()
            if (!isHttpUrl(url)) return@mapNotNull null
            Account(
                name = (o["name"] as? String)?.trim().orEmpty().take(40),
                url = url,
                color = (o["color"] as? String)?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }.orEmpty(),
                epg = (o["epg"] as? String)?.trim().orEmpty(),
                locked = o["locked"] == true,
            )
        }
        if (accounts.isEmpty()) throw IllegalArgumentException("Yedekte geçerli hesap yok")
        @Suppress("UNCHECKED_CAST")
        val settings = (m["settings"] as? Map<String, Any?>).orEmpty()
        return accounts.take(MAX_ACCOUNTS) to settings
    }

    fun isHttpUrl(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    const val MAX_ACCOUNTS = 20
}
