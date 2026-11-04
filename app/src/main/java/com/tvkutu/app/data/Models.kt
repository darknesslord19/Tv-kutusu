package com.tvkutu.app.data

data class Channel(
    /** Kalıcı anahtar (favoriler için): tvg-id varsa o, yoksa ad + url. */
    val key: String,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
)

data class Playlist(
    val channels: List<Channel>,
    /** Playlist başlığındaki url-tvg / x-tvg-url (ilk link). */
    val epgUrl: String?,
)

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

/** Kanal anahtarına göre sıralı program listesi. */
class EpgIndex(private val byChannel: Map<String, List<Programme>>) {
    val isEmpty get() = byChannel.isEmpty()

    fun programmes(channel: Channel): List<Programme> = byChannel[channel.key].orEmpty()

    fun current(channel: Channel, now: Long): Programme? =
        programmes(channel).firstOrNull { it.isLiveAt(now) }

    fun next(channel: Channel, now: Long): Programme? =
        programmes(channel).firstOrNull { it.startMs > now }

    companion object {
        val EMPTY = EpgIndex(emptyMap())
    }
}
