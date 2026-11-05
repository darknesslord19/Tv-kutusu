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
