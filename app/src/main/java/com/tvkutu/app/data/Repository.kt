package com.tvkutu.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Birden çok kaynağın yüklenme sonucu. */
class LoadResult(
    val playlist: Playlist,
    /** Kaynak adı → hata mesajı (yüklenemeyenler). */
    val errors: List<Pair<String, String>>,
)

class Repository(context: Context) {

    private val prefs = context.getSharedPreferences("tvkutu", Context.MODE_PRIVATE)
    private val dir = context.filesDir

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        migrateSingleUrl()
    }

    // ---- Kaynaklar ----
    var sources: List<Source>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("sources", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Source(o.getString("id"), o.optString("name"), o.getString("url"))
            }
        }.getOrDefault(emptyList())
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)) }
            prefs.edit().putString("sources", arr.toString()).apply()
            // Silinen kaynakların önbelleğini temizle.
            val keep = list.map { playlistFile(it.id).name }.toSet()
            dir.listFiles { f -> f.name.startsWith("playlist_") && f.name !in keep }?.forEach { it.delete() }
        }

    /** Kullanıcının elle girdiği EPG linkleri (virgül/satır ile ayrılmış). Boşsa playlist'lerdeki url-tvg kullanılır. */
    var epgUrl: String
        get() = prefs.getString("epg_url", "") ?: ""
        set(v) = prefs.edit().putString("epg_url", v.trim()).apply()

    fun epgList(): List<String> =
        epgUrl.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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

    /** "internal" = ExoPlayer, "external" = cihazdaki başka oynatıcı (VLC, MX…). */
    var playerMode: String
        get() = prefs.getString("player_mode", "internal") ?: "internal"
        set(v) = prefs.edit().putString("player_mode", v).apply()

    /** "auto", "hls" veya "ts". */
    var streamFormat: String
        get() = prefs.getString("stream_format", "auto") ?: "auto"
        set(v) = prefs.edit().putString("stream_format", v).apply()

    /** Afiş kartları: "auto" (görsel/linke göre), "always" (hep afiş), "never" (hep logo). */
    var posterMode: String
        get() = prefs.getString("poster_mode", "auto") ?: "auto"
        set(v) = prefs.edit().putString("poster_mode", v).apply()

    /** Afiş kartında ad: "overlay" (afişin üstünde) veya "below" (afişin altında). */
    var posterTitle: String
        get() = prefs.getString("poster_title", "overlay") ?: "overlay"
        set(v) = prefs.edit().putString("poster_title", v).apply()

    val hasPlaylist get() = sources.isNotEmpty()

    // ---- Favoriler ----
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()

    fun toggleFavorite(key: String): Set<String> {
        val set = favorites().toMutableSet()
        if (!set.remove(key)) set.add(key)
        prefs.edit().putStringSet("favorites", set).apply()
        return set
    }

    // ---- Yükleme ----
    private fun playlistFile(id: String) = File(dir, "playlist_$id.m3u")

    private fun parseFile(src: Source): Playlist? {
        val f = playlistFile(src.id)
        if (!f.exists()) return null
        return M3uParser.parse(f.readText(), sourceId = src.id, baseUrl = src.url)
    }

    suspend fun loadCachedPlaylist(): Playlist? = withContext(Dispatchers.IO) {
        val parts = sources.mapNotNull { runCatching { parseFile(it) }.getOrNull() }
        if (parts.isEmpty()) null else Playlist.merge(parts)
    }

    /** Tüm kaynakları paralel indirir. Biri başarısız olursa önbellekteki kopyası kullanılır. */
    suspend fun downloadAll(): LoadResult = withContext(Dispatchers.IO) {
        val list = sources
        if (list.isEmpty()) throw IOException("Hiç playlist linki eklenmemiş")
        val results = coroutineScope {
            list.map { src ->
                async {
                    try {
                        Result.success(downloadOne(src))
                    } catch (e: Exception) {
                        Result.failure<Playlist>(e)
                    }
                }
            }.awaitAll()
        }
        val parts = ArrayList<Playlist>()
        val errors = ArrayList<Pair<String, String>>()
        results.forEachIndexed { i, r ->
            val src = list[i]
            r.onSuccess { parts += it }
            r.onFailure { e ->
                errors += src.name to (e.message ?: e.javaClass.simpleName)
                runCatching { parseFile(src) }.getOrNull()?.let { parts += it }
            }
        }
        val merged = Playlist.merge(parts)
        if (merged.channels.isEmpty()) {
            throw IOException(errors.joinToString("\n") { "${it.first}: ${it.second}" }.ifEmpty { "Listelerde kanal bulunamadı" })
        }
        if (errors.size < list.size) lastUpdatedMs = System.currentTimeMillis()
        LoadResult(merged, errors)
    }

    private fun downloadOne(src: Source): Playlist {
        val tmp = File(dir, "playlist_${src.id}.tmp")
        download(src.url, tmp)
        val pl = M3uParser.parse(tmp.readText(), sourceId = src.id, baseUrl = src.url)
        if (pl.channels.isEmpty()) {
            tmp.delete()
            throw IOException("Listede kanal bulunamadı (m3u/m3u8 linki olduğundan emin ol)")
        }
        tmp.renameTo(playlistFile(src.id))
        return pl
    }

    fun effectiveEpgUrls(playlist: Playlist?): List<String> =
        epgList().ifEmpty { playlist?.epgUrls.orEmpty() }.take(MAX_EPG)

    /** Tüm EPG linklerini yükleyip birleştirir. Biri başarısız olursa diğerleri yine kullanılır. */
    suspend fun loadEpg(playlist: Playlist, forceDownload: Boolean): Pair<EpgIndex, List<String>> = withContext(Dispatchers.IO) {
        val urls = effectiveEpgUrls(playlist)
        if (urls.isEmpty()) return@withContext EpgIndex.EMPTY to emptyList()
        val now = System.currentTimeMillis()
        val errors = ArrayList<String>()
        val parts = coroutineScope {
            urls.map { url ->
                async {
                    val file = File(dir, "epg_${url.hashCode().toUInt().toString(16)}.dat")
                    try {
                        if (forceDownload || !file.exists()) {
                            val tmp = File(file.path + ".tmp")
                            download(url, tmp)
                            tmp.renameTo(file)
                        }
                        file.inputStream().use {
                            XmltvParser.parse(it, playlist.channels, now - 12 * HOUR, now + 48 * HOUR)
                        }
                    } catch (e: Exception) {
                        synchronized(errors) { errors += e.message ?: e.javaClass.simpleName }
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        // Artık kullanılmayan EPG dosyalarını sil.
        val keep = urls.map { "epg_${it.hashCode().toUInt().toString(16)}.dat" }.toSet()
        dir.listFiles { f -> f.name.startsWith("epg_") && f.name !in keep }?.forEach { it.delete() }

        (if (parts.isEmpty()) EpgIndex.EMPTY else EpgIndex.merge(parts)) to errors
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

    /** v0.3 ve öncesi: tek playlist_url ayarını kaynak listesine taşır, favorileri korur. */
    private fun migrateSingleUrl() {
        if (prefs.contains("sources")) return
        val old = prefs.getString("playlist_url", "")?.trim().orEmpty()
        val edit = prefs.edit()
        if (old.isNotEmpty()) {
            val id = "s1"
            edit.putString("sources", JSONArray().put(JSONObject().put("id", id).put("name", "Liste 1").put("url", old)).toString())
            val favs = favorites().map { "$id/$it" }.toSet()
            edit.putStringSet("favorites", favs)
            prefs.getString("last_channel", null)?.let { edit.putString("last_channel", "$id/$it") }
            File(dir, "playlist.m3u").takeIf { it.exists() }?.renameTo(playlistFile(id))
        } else {
            edit.putString("sources", "[]")
        }
        edit.remove("playlist_url").apply()
    }

    companion object {
        const val HOUR = 3_600_000L
        const val MAX_EPG = 5
        const val USER_AGENT = "TvKutu/0.4 (Android TV)"

        /** Resim (logo) istekleri için tarayıcı benzeri kimlik; bazı sunucular okhttp'yi engeller. */
        const val IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        fun newSourceId(): String = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
            (100..999).random()
    }
}
