package com.tvkutu.app.data

/** Bir hesap: kullanıcının eklediği bir playlist linki ve ayarları. */
data class Source(
    val id: String,
    val name: String,
    val url: String,
    /** Kutucuk rengi (#RRGGBB). */
    val color: String = "",
    /** Bu hesaba özel EPG linkleri (satır/virgülle ayrılmış). Boşsa listedeki url-tvg. */
    val epg: String = "",
    /** PIN olmadan açılamaz. */
    val locked: Boolean = false,
    /** Son başarılı yüklemedeki kanal sayısı (0 = bilinmiyor). */
    val count: Int = 0,
    /** Son kontrol/yükleme hatası (boş = sorun yok). */
    val error: String = "",
    /** Son kontrol zamanı (ms). */
    val checkedMs: Long = 0L,
) {
    fun epgList(): List<String> =
        epg.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        val COLORS = listOf("#3B2470", "#5A1A1F", "#0F4A4A", "#1C3566", "#5A3A10", "#1D4A2E", "#4A2040", "#2E3440")
    }
}

data class Channel(
    /** Kalıcı anahtar (favoriler için): kaynak kimliği + tvg-id ya da ad + url. */
    val key: String,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
    val sourceId: String = "",
)

data class Playlist(
    val channels: List<Channel>,
    /** Playlist başlığındaki url-tvg / x-tvg-url linkleri. */
    val epgUrls: List<String>,
) {
    /** Geriye dönük uyumluluk: ilk EPG linki. */
    val epgUrl: String? get() = epgUrls.firstOrNull()

    companion object {
        fun merge(parts: List<Playlist>) = Playlist(
            channels = parts.flatMap { it.channels },
            epgUrls = parts.flatMap { it.epgUrls }.distinct(),
        )
    }
}

data class Programme(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
) {
    fun isLiveAt(t: Long) = t in startMs until endMs
    fun progressAt(t: Long): Float =
        if (endMs <= startMs) 0f else ((t - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/** Kanal anahtarına göre sıralı program listesi ve EPG'den gelen kanal logoları. */
class EpgIndex(
    private val byChannel: Map<String, List<Programme>>,
    private val logos: Map<String, String> = emptyMap(),
) {
    val isEmpty get() = byChannel.isEmpty() && logos.isEmpty()

    fun programmes(channel: Channel): List<Programme> = byChannel[channel.key].orEmpty()

    fun current(channel: Channel, now: Long): Programme? =
        programmes(channel).firstOrNull { it.isLiveAt(now) }

    fun next(channel: Channel, now: Long): Programme? =
        programmes(channel).firstOrNull { it.startMs > now }

    fun logo(channel: Channel): String? = logos[channel.key]

    companion object {
        val EMPTY = EpgIndex(emptyMap())

        /** Birden çok EPG dosyasını birleştirir; önce gelen öncelikli. */
        fun merge(parts: List<EpgIndex>): EpgIndex {
            if (parts.size == 1) return parts[0]
            val progs = HashMap<String, List<Programme>>()
            val logos = HashMap<String, String>()
            for (p in parts) {
                p.byChannel.forEach { (k, v) -> if (progs[k].isNullOrEmpty()) progs[k] = v }
                p.logos.forEach { (k, v) -> logos.putIfAbsent(k, v) }
            }
            return EpgIndex(progs, logos)
        }
    }
}

/** Yarıda kalan film/dizi: kaldığı yer, toplam süre (ms) ve son izlenme zamanı. */
data class WatchProgress(val posMs: Long, val durMs: Long, val updatedMs: Long) {
    val fraction: Float get() = if (durMs <= 0) 0f else (posMs.toFloat() / durMs).coerceIn(0f, 1f)
    val remainingMs: Long get() = (durMs - posMs).coerceAtLeast(0)

    companion object {
        /** Bundan kısa izlenenler listeye girmez. */
        const val MIN_WATCHED_MS = 30_000L
        const val MAX_ITEMS = 60

        /** Sonuna bu kadar kala (ya da %95'ten sonra) "bitti" sayılır ve listeden çıkar. */
        fun isFinished(posMs: Long, durMs: Long): Boolean =
            durMs > 0 && (durMs - posMs <= maxOf(120_000L, durMs / 20))

        /** Kaydedilmeye değer mi: biraz izlenmiş ve bitmemiş. */
        fun shouldKeep(posMs: Long, durMs: Long): Boolean =
            durMs > 0 && posMs >= MIN_WATCHED_MS && !isFinished(posMs, durMs)

        /** "18 dk kaldı", "1 sa 4 dk kaldı". */
        fun remainingText(ms: Long): String {
            val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(1)
            val h = totalMin / 60
            val m = totalMin % 60
            return when {
                h == 0L -> "$m dk kaldı"
                m == 0L -> "$h sa kaldı"
                else -> "$h sa $m dk kaldı"
            }
        }

        /** 4350000 → "1:12:30", 95000 → "1:35". */
        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000)
            val h = s / 3600
            val m = (s % 3600) / 60
            val x = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
        }
    }
}

/**
 * Kaldığı yer kaydının anahtarı: hesap + kategori + ad. Linke bağlı değildir; sağlayıcılar
 * film linklerine her yenilemede değişen anahtar eklese de kayıt kaybolmaz.
 */
fun Channel.progressKey(): String = "p:$sourceId/${group.trim()}|${name.trim()}"
