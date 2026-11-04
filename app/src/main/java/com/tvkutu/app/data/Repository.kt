package com.tvkutu.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class Repository(context: Context) {

    private val prefs = context.getSharedPreferences("tvkutu", Context.MODE_PRIVATE)
    private val playlistFile = File(context.filesDir, "playlist.m3u")
    private val epgFile = File(context.filesDir, "epg.dat")

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ---- Ayarlar ----
    var playlistUrl: String
        get() = prefs.getString("playlist_url", "") ?: ""
        set(v) = prefs.edit().putString("playlist_url", v.trim()).apply()

    /** Boşsa playlist içindeki url-tvg kullanılır. */
    var epgUrl: String
        get() = prefs.getString("epg_url", "") ?: ""
        set(v) = prefs.edit().putString("epg_url", v.trim()).apply()

    var lastUpdatedMs: Long
        get() = prefs.getLong("last_updated", 0L)
        private set(v) = prefs.edit().putLong("last_updated", v).apply()

    var lastChannelKey: String?
        get() = prefs.getString("last_channel", null)
        set(v) = prefs.edit().putString("last_channel", v).apply()

    /** Otomatik güncelleme aralığı (saat). 0 = kapalı. */
    var updateHours: Int
        get() = prefs.getInt("update_hours", 12)
        set(v) = prefs.edit().putInt("update_hours", v).apply()

    val hasPlaylist get() = playlistUrl.isNotBlank()

    // ---- Favoriler ----
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()

    fun toggleFavorite(key: String): Set<String> {
        val set = favorites().toMutableSet()
        if (!set.remove(key)) set.add(key)
        prefs.edit().putStringSet("favorites", set).apply()
        return set
    }

    // ---- Yükleme ----
    suspend fun loadCachedPlaylist(): Playlist? = withContext(Dispatchers.IO) {
        if (!playlistFile.exists()) null
        else M3uParser.parse(playlistFile.readText())
    }

    suspend fun downloadPlaylist(): Playlist = withContext(Dispatchers.IO) {
        val url = playlistUrl
        require(url.isNotBlank()) { "Playlist adresi girilmemiş" }
        val tmp = File(playlistFile.path + ".tmp")
        download(url, tmp)
        val text = tmp.readText()
        val pl = M3uParser.parse(text)
        if (pl.channels.isEmpty()) {
            tmp.delete()
            throw IOException("Listede kanal bulunamadı. Adresin bir m3u/m3u8 listesi olduğundan emin olun.")
        }
        tmp.renameTo(playlistFile)
        lastUpdatedMs = System.currentTimeMillis()
        pl
    }

    fun effectiveEpgUrl(playlist: Playlist?): String? =
        epgUrl.ifBlank { null } ?: playlist?.epgUrl

    suspend fun loadEpg(playlist: Playlist, forceDownload: Boolean): EpgIndex = withContext(Dispatchers.IO) {
        val url = effectiveEpgUrl(playlist) ?: return@withContext EpgIndex.EMPTY
        if (forceDownload || !epgFile.exists()) {
            val tmp = File(epgFile.path + ".tmp")
            download(url, tmp)
            tmp.renameTo(epgFile)
        }
        val now = System.currentTimeMillis()
        epgFile.inputStream().use {
            XmltvParser.parse(it, playlist.channels, now - 12 * HOUR, now + 48 * HOUR)
        }
    }

    fun isStale(): Boolean {
        val h = updateHours
        return h > 0 && System.currentTimeMillis() - lastUpdatedMs > h * HOUR
    }

    private fun download(url: String, target: File) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sunucu hatası: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Boş yanıt")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val USER_AGENT = "TvKutu/0.1 (Android TV)"
    }
}
