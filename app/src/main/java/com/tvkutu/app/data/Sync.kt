package com.tvkutu.app.data

import java.security.MessageDigest

/**
 * Wi‑Fi eşitlemenin cihazdan bağımsız modeli.
 *
 * Cihazlar arasında hesap kimlikleri farklıdır; eşitlemede her hesap linkinden türetilen
 * [sid] ile anılır. Her kayıt bir zaman damgası taşır ve "son yazan kazanır":
 * - hesaplar: link başına [Acc] (silinenler `deleted = true` ile tutulur),
 * - favoriler / kilitli kategoriler: anahtar → işaretli zaman (artı: var, eksi: kaldırıldı),
 * - kaldığın yerler: anahtar → [WatchProgress] (posMs < 0: listeden çıkarıldı),
 * - ayarlar: tek parça, [Snap.settingsMs] ile.
 */
object Sync {

    /** Linkten türetilen, cihazlar arası ortak hesap kimliği. */
    fun sid(url: String): String =
        "u" + MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    data class Acc(
        val url: String,
        val name: String,
        val color: String,
        val epg: String,
        val locked: Boolean,
        val ts: Long,
        val deleted: Boolean = false,
    )

    data class Snap(
        val accounts: Map<String, Acc> = emptyMap(),
        val favorites: Map<String, Long> = emptyMap(),
        val locks: Map<String, Long> = emptyMap(),
        val progress: Map<String, WatchProgress> = emptyMap(),
        val settings: Map<String, Any?> = emptyMap(),
        val settingsMs: Long = 0L,
    )

    /** İki görüntüyü birleştirir: her kayıtta zamanı daha yeni olan kazanır. */
    fun merge(a: Snap, b: Snap): Snap {
        val accounts = (a.accounts.keys + b.accounts.keys).associateWith { k ->
            val x = a.accounts[k]
            val y = b.accounts[k]
            when {
                x == null -> y!!
                y == null -> x
                y.ts > x.ts -> y
                else -> x
            }
        }
        return Snap(
            accounts = accounts,
            favorites = lww(a.favorites, b.favorites),
            locks = lww(a.locks, b.locks),
            progress = (a.progress.keys + b.progress.keys).associateWith { k ->
                val x = a.progress[k]
                val y = b.progress[k]
                when {
                    x == null -> y!!
                    y == null -> x
                    y.updatedMs > x.updatedMs -> y
                    else -> x
                }
            },
            settings = if (b.settingsMs > a.settingsMs) b.settings else a.settings,
            settingsMs = maxOf(a.settingsMs, b.settingsMs),
        )
    }

    private fun lww(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
        (a.keys + b.keys).associateWith { k ->
            val x = a[k] ?: 0L
            val y = b[k] ?: 0L
            if (Math.abs(y) > Math.abs(x)) y else x
        }

    /** Silinmemiş hesaplar. */
    fun liveAccounts(s: Snap) = s.accounts.filterValues { !it.deleted }

    // ---- Yerel ↔ ortak anahtar dönüşümü ----

    /** Yerel anahtarı (hesap kimliğiyle) ortak anahtara (sid ile) çevirir. */
    fun toShared(key: String, idToSid: Map<String, String>): String? = Backup.remap(key, idToSid)

    /** Ortak anahtarı yerel hesap kimliğine çevirir. */
    fun toLocal(key: String, sidToId: Map<String, String>): String? = Backup.remap(key, sidToId)

    /**
     * Birleşmiş görüntüyü yerel hesap listesine uygulama planı.
     * [replace]: görüntüde olmayan yerel hesaplar silinir (ilk eşleşmede "diğer cihazdakini kullan").
     * Dönen liste yerel sırayı korur, yeni hesaplar sona eklenir; [deleted] yeni silinenlerin linkleri.
     */
    data class AccountPlan(val sources: List<Source>, val deletedUrls: Map<String, Long>)

    fun planAccounts(local: List<Source>, snap: Snap, replace: Boolean, now: Long, newId: () -> String): AccountPlan {
        val out = ArrayList<Source>()
        val deleted = HashMap<String, Long>()
        val bySid = snap.accounts
        for (s in local) {
            val e = bySid[sid(s.url)]
            when {
                e == null -> if (replace) deleted[s.url] = now else out += s
                e.deleted -> if (e.ts >= s.updatedMs) deleted[s.url] = e.ts else out += s
                e.ts > s.updatedMs -> out += s.copy(name = e.name, color = e.color, epg = e.epg, locked = e.locked, updatedMs = e.ts)
                else -> out += s
            }
        }
        val known = local.map { sid(it.url) }.toSet()
        for ((k, e) in bySid) {
            if (e.deleted || k in known || out.size >= Backup.MAX_ACCOUNTS) continue
            out += Source(id = newId(), name = e.name, url = e.url, color = e.color, epg = e.epg, locked = e.locked, updatedMs = e.ts)
        }
        return AccountPlan(out, deleted)
    }

    // ---- JSON ----

    fun toJson(s: Snap): Map<String, Any?> = linkedMapOf(
        "accounts" to s.accounts.mapValues { (_, a) ->
            linkedMapOf("url" to a.url, "name" to a.name, "color" to a.color, "epg" to a.epg,
                "locked" to a.locked, "ts" to a.ts, "deleted" to a.deleted)
        },
        "favorites" to s.favorites,
        "locks" to s.locks,
        "progress" to s.progress.mapValues { (_, w) -> linkedMapOf("p" to w.posMs, "d" to w.durMs, "t" to w.updatedMs) },
        "settings" to s.settings,
        "settingsMs" to s.settingsMs,
    )

    fun fromJson(any: Any?): Snap {
        val m = any as? Map<*, *> ?: throw IllegalArgumentException("Geçersiz eşitleme verisi")
        fun longMap(x: Any?) = (x as? Map<*, *>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.toLong()?.let { k.toString() to it } }.toMap()
        @Suppress("UNCHECKED_CAST")
        return Snap(
            accounts = (m["accounts"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                val o = v as? Map<*, *> ?: return@mapNotNull null
                val url = (o["url"] as? String)?.trim().orEmpty()
                if (!Backup.isHttpUrl(url)) return@mapNotNull null
                k.toString() to Acc(
                    url = url, name = (o["name"] as? String).orEmpty().take(40), color = (o["color"] as? String).orEmpty(),
                    epg = (o["epg"] as? String).orEmpty(), locked = o["locked"] == true,
                    ts = (o["ts"] as? Number)?.toLong() ?: 0L, deleted = o["deleted"] == true,
                )
            }.toMap(),
            favorites = longMap(m["favorites"]),
            locks = longMap(m["locks"]),
            progress = (m["progress"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                val o = v as? Map<*, *> ?: return@mapNotNull null
                val p = (o["p"] as? Number)?.toLong() ?: return@mapNotNull null
                k.toString() to WatchProgress(p, (o["d"] as? Number)?.toLong() ?: 0L, (o["t"] as? Number)?.toLong() ?: 0L)
            }.toMap(),
            settings = (m["settings"] as? Map<String, Any?>).orEmpty(),
            settingsMs = (m["settingsMs"] as? Number)?.toLong() ?: 0L,
        )
    }
}
