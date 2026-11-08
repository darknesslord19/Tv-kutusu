package com.tvkutu.app.data

/**
 * Yedek dosyası (v2): hesaplar, görünüm ayarları, favoriler, kaldığın yerler, kilitli kategoriler
 * ve hesap başına son kanal. PIN yedeğe girmez.
 *
 * Geri yüklemede hesaplar yeni kimlik alır; favori/ilerleme/kilit anahtarları ("<hesap>/…",
 * "p:<hesap>/…", "grp:<hesap>\u0001…") yeni kimliklere eşlenir. Aynı linke sahip hesap zaten
 * varsa yenisi eklenmez, veriler mevcut hesaba birleştirilir.
 */
object Backup {
    data class Account(val ref: String, val name: String, val url: String, val color: String, val epg: String, val locked: Boolean)

    data class Extras(
        val favorites: Set<String> = emptySet(),
        val lockedGroups: Set<String> = emptySet(),
        /** Hesap kimliği → (kayıt anahtarı → ilerleme). */
        val progress: Map<String, Map<String, WatchProgress>> = emptyMap(),
        /** Hesap kimliği → son izlenen kanal anahtarı. */
        val lastChannels: Map<String, String> = emptyMap(),
    ) {
        val isEmpty get() = favorites.isEmpty() && lockedGroups.isEmpty() && progress.isEmpty() && lastChannels.isEmpty()
    }

    data class Data(val accounts: List<Account>, val settings: Map<String, Any?>, val extras: Extras, val createdMs: Long)

    data class Plan(
        /** Eklenecek yeni hesaplar. */
        val newSources: List<Source>,
        /** Yeni/mevcut hesap kimliklerine eşlenmiş veriler. */
        val extras: Extras,
        val settings: Map<String, Any?>,
    ) {
        val added get() = newSources.size
    }

    fun build(accounts: List<Source>, settings: Map<String, Any?>, extras: Extras = Extras()): Map<String, Any?> = linkedMapOf(
        "app" to "TvKutu",
        "v" to 2,
        "createdMs" to System.currentTimeMillis(),
        "accounts" to accounts.map {
            linkedMapOf("ref" to it.id, "name" to it.name, "url" to it.url, "color" to it.color, "epg" to it.epg, "locked" to it.locked)
        },
        "settings" to settings,
        "favorites" to extras.favorites.sorted(),
        "lockedGroups" to extras.lockedGroups.sorted(),
        "progress" to extras.progress.mapValues { (_, m) ->
            m.mapValues { (_, w) -> linkedMapOf("p" to w.posMs, "d" to w.durMs, "t" to w.updatedMs) }
        },
        "lastChannels" to extras.lastChannels,
    )

    /** Yedeği okur; geçersizse açıklayıcı bir hata fırlatır. v1 yedekleri de okunur. */
    fun parse(data: Any?): Data {
        val m = data as? Map<*, *> ?: throw IllegalArgumentException("Bu bir TvKutu yedeği değil")
        if (m["app"] != "TvKutu") throw IllegalArgumentException("Bu bir TvKutu yedeği değil")
        val list = m["accounts"] as? List<*> ?: throw IllegalArgumentException("Yedekte hesap yok")
        val accounts = list.mapIndexedNotNull { i, a ->
            val o = a as? Map<*, *> ?: return@mapIndexedNotNull null
            val url = (o["url"] as? String)?.trim().orEmpty()
            if (!isHttpUrl(url)) return@mapIndexedNotNull null
            Account(
                ref = (o["ref"] as? String)?.takeIf { it.isNotBlank() } ?: "r$i",
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
        val progress = (m["progress"] as? Map<*, *>).orEmpty().mapNotNull { (acc, v) ->
            val inner = (v as? Map<*, *>)?.mapNotNull { (k, w) ->
                val o = w as? Map<*, *> ?: return@mapNotNull null
                val p = (o["p"] as? Number)?.toLong() ?: return@mapNotNull null
                val d = (o["d"] as? Number)?.toLong() ?: return@mapNotNull null
                k.toString() to WatchProgress(p, d, (o["t"] as? Number)?.toLong() ?: 0L)
            }?.toMap().orEmpty()
            if (inner.isEmpty()) null else acc.toString() to inner
        }.toMap()
        val extras = Extras(
            favorites = (m["favorites"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet(),
            lockedGroups = (m["lockedGroups"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet(),
            progress = progress,
            lastChannels = (m["lastChannels"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> (v as? String)?.let { k.toString() to it } }.toMap(),
        )
        return Data(accounts.take(MAX_ACCOUNTS), settings, extras, (m["createdMs"] as? Number)?.toLong() ?: 0L)
    }

    /**
     * Geri yükleme planı. [replace] ise mevcut hesaplar yok sayılır (hepsi yedektekilerle değişir),
     * değilse aynı linke sahip hesaplar birleştirilir, yeniler eklenir (en fazla [MAX_ACCOUNTS]).
     */
    fun plan(data: Data, existing: List<Source>, replace: Boolean, hasPin: Boolean, newId: () -> String): Plan {
        val base = if (replace) emptyList() else existing
        val fresh = ArrayList<Source>()
        val idMap = HashMap<String, String>()
        val usedColors = base.map { it.color }.toMutableSet()
        for (a in data.accounts) {
            val dup = base.firstOrNull { it.url == a.url } ?: fresh.firstOrNull { it.url == a.url }
            if (dup != null) {
                idMap[a.ref] = dup.id
                continue
            }
            if (base.size + fresh.size >= MAX_ACCOUNTS) continue
            val color = a.color.ifEmpty { Source.COLORS.firstOrNull { it !in usedColors } ?: Source.COLORS[fresh.size % Source.COLORS.size] }
            usedColors += color
            val src = Source(
                id = newId(), name = a.name.ifEmpty { "Hesap ${base.size + fresh.size + 1}" }, url = a.url,
                color = color, epg = a.epg, locked = a.locked && hasPin,
            )
            fresh += src
            idMap[a.ref] = src.id
        }
        val x = data.extras
        val extras = Extras(
            favorites = x.favorites.mapNotNull { remap(it, idMap) }.toSet(),
            lockedGroups = x.lockedGroups.mapNotNull { remap(it, idMap) }.toSet(),
            progress = x.progress.mapNotNull { (ref, m) ->
                val id = idMap[ref] ?: return@mapNotNull null
                val mm = m.mapNotNull { (k, w) -> remap(k, idMap)?.let { it to w } }.toMap()
                if (mm.isEmpty()) null else id to mm
            }.toMap(),
            lastChannels = x.lastChannels.mapNotNull { (ref, key) ->
                val id = idMap[ref] ?: return@mapNotNull null
                remap(key, idMap)?.let { id to it }
            }.toMap(),
        )
        return Plan(fresh, extras, data.settings)
    }

    /** Anahtarın içindeki hesap kimliğini yenisiyle değiştirir; hesap eşlenmemişse null. */
    fun remap(key: String, idMap: Map<String, String>): String? = when {
        key.startsWith("p:") -> {
            val rest = key.removePrefix("p:")
            idMap[rest.substringBefore('/')]?.let { "p:$it/" + rest.substringAfter('/', "") }
        }
        key.startsWith("grp:") -> {
            val rest = key.removePrefix("grp:")
            idMap[rest.substringBefore('\u0001')]?.let { "grp:$it\u0001" + rest.substringAfter('\u0001', "") }
        }
        key.contains('/') -> idMap[key.substringBefore('/')]?.let { "$it/" + key.substringAfter('/') }
        else -> null
    }

    fun isHttpUrl(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    const val MAX_ACCOUNTS = 20
}
