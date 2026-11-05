package com.tvkutu.app.data

/** Kullanıcının eklediği bir playlist linki. */
data class Source(
    val id: String,
    val name: String,
    val url: String,
)

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
