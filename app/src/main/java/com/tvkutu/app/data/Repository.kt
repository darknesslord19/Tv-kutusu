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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class Repository(context: Context) {

    private val prefs = context.getSharedPreferences("tvkutu", Context.MODE_PRIVATE)
    private val dir = context.filesDir

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val quickHttp: OkHttpClient = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    init {
        migrateSingleUrl()
        migrateToAccounts()
    }

    // ---- Hesaplar ----
    var sources: List<Source>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("sources", "[]"))
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(toJson(it)) }
            prefs.edit().putString("sources", arr.toString()).apply()
            // Silinen hesapların önbelleğini temizle.
            val keep = list.map { playlistFile(it.id).name }.toSet()
            dir.listFiles { f -> f.name.startsWith("playlist_") && f.name.endsWith(".m3u") && f.name !in keep }?.forEach { it.delete() }
            if (activeId != null && list.none { it.id == activeId }) activeId = list.firstOrNull()?.id
        }

    var activeId: String?
        get() = prefs.getString("active_id", null)
        set(v) = prefs.edit().putString("active_id", v).apply()

    fun activeSource(): Source? = sources.let { list -> list.firstOrNull { it.id == activeId } ?: list.firstOrNull() }

    fun source(id: String): Source? = sources.firstOrNull { it.id == id }

    @Synchronized
    fun updateSource(id: String, change: (Source) -> Source) {
        sources = sources.map { if (it.id == id) change(it) else it }
    }

    private fun toJson(s: Source) = JSONObject()
        .put("id", s.id).put("name", s.name).put("url", s.url)
        .put("color", s.color).put("epg", s.epg).put("locked", s.locked)
        .put("count", s.count).put("error", s.error).put("checked", s.checkedMs)

    private fun fromJson(o: JSONObject) = Source(
        id = o.getString("id"),
        name = o.optString("name"),
        url = o.getString("url"),
        color = o.optString("color"),
        epg = o.optString("epg"),
        locked = o.optBoolean("locked", false),
        count = o.optInt("count", 0),
        error = o.optString("error"),
        checkedMs = o.optLong("checked", 0L),
    )

    fun nextColor(): String {
        val used = sources.map { it.color }.toSet()
        return Source.COLORS.firstOrNull { it !in used } ?: Source.COLORS[sources.size % Source.COLORS.size]
    }

    val hasPlaylist get() = sources.isNotEmpty()

    // ---- Hesap başına son kanal ve güncelleme zamanı ----
    fun lastChannel(accountId: String): String? = prefs.getString("last_channel_$accountId", null)
    fun setLastChannel(accountId: String, key: String) = prefs.edit().putString("last_channel_$accountId", key).apply()

    private fun lastUpdated(accountId: String) = prefs.getLong("last_updated_$accountId", 0L)

    fun isStale(accountId: String): Boolean {
        val h = updateHours
        return h > 0 && System.currentTimeMillis() - lastUpdated(accountId) > h * HOUR
    }

    // ---- Ayarlar ----
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

    // ---- Favoriler (anahtarlar hesap kimliğiyle başladığı için hesap başınadır) ----
    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())!!.toSet()

    fun toggleFavorite(key: String): Set<String> {
        val set = favorites().toMutableSet()
        if (!set.remove(key)) set.add(key)
        prefs.edit().putStringSet("favorites", set).apply()
        return set
    }

    // ---- Ebeveyn kilidi ----
    val hasPin get() = prefs.contains("pin_hash")

    fun setPin(pin: String?) {
        if (pin.isNullOrEmpty()) {
            prefs.edit().remove("pin_hash").remove("pin_salt").apply()
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String?): Boolean {
        if (!hasPin) return true
        if (pin.isNullOrEmpty()) return false
        val salt = prefs.getString("pin_salt", "") ?: ""
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), (prefs.getString("pin_hash", "") ?: "").toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Kilitli kategori kimlikleri ("grp:<hesap>\u0001<kategori>"). */
    var lockedGroups: Set<String>
        get() = prefs.getStringSet("locked_groups", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("locked_groups", v).apply()

    // ---- Telefon kumandası (kalıcı kod ve port, yer imi çalışmaya devam etsin) ----
    val remoteCode: String
        get() = prefs.getString("remote_code", null) ?: run {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            val c = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            prefs.edit().putString("remote_code", c).apply()
            c
        }

    var remotePort: Int
        get() = prefs.getInt("remote_port", 8686)
        set(v) = prefs.edit().putInt("remote_port", v).apply()

    // ---- Yükleme ----
    private fun playlistFile(id: String) = File(dir, "playlist_$id.m3u")

    suspend fun loadCached(src: Source): Playlist? = withContext(Dispatchers.IO) {
        val f = playlistFile(src.id)
        if (!f.exists()) null
        else runCatching { M3uParser.parse(f.readText(), sourceId = src.id, baseUrl = src.url) }.getOrNull()
            ?.takeIf { it.channels.isNotEmpty() }
    }

    /** Hesabın listesini indirir; başarı/başarısızlık hesabın durumuna yazılır. */
    suspend fun download(src: Source): Playlist = withContext(Dispatchers.IO) {
        try {
            val tmp = File(dir, "playlist_${src.id}.tmp")
            fetch(src.url, tmp)
            val pl = M3uParser.parse(tmp.readText(), sourceId = src.id, baseUrl = src.url)
            if (pl.channels.isEmpty()) {
                tmp.delete()
                throw IOException("Listede kanal bulunamadı (m3u/m3u8 linki olduğundan emin ol)")
            }
            tmp.renameTo(playlistFile(src.id))
            prefs.edit().putLong("last_updated_${src.id}", System.currentTimeMillis()).apply()
            updateSource(src.id) { it.copy(count = pl.channels.size, error = "", checkedMs = System.currentTimeMillis()) }
            pl
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            updateSource(src.id) { it.copy(error = msg, checkedMs = System.currentTimeMillis()) }
            throw e
        }
    }

    /**
     * Hızlı durum kontrolü: listenin ilk birkaç KB'ını ister. Hata metni ya da "" döner
     * ve hesabın durumuna yazar. Kanal sayısı, son tam yüklemeden gelir.
     */
    suspend fun checkReachable(src: Source): String = withContext(Dispatchers.IO) {
        val err = try {
            val req = Request.Builder().url(src.url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-4095")
                .build()
            quickHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "Sunucu hatası: HTTP ${resp.code}"
                else {
                    val head = resp.body?.byteStream()?.use { s ->
                        val buf = ByteArray(4096)
                        val n = s.read(buf)
                        if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
                    }.orEmpty()
                    if (head.contains("#EXTM3U", ignoreCase = true) || head.contains("#EXTINF", ignoreCase = true)) ""
                    else "Yanıt bir m3u listesi değil (abonelik bitmiş olabilir)"
                }
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        updateSource(src.id) { it.copy(error = err, checkedMs = System.currentTimeMillis()) }
        err
    }

    suspend fun checkAll(): Unit = coroutineScope {
        sources.map { s -> async { runCatching { checkReachable(s) } } }.awaitAll()
        Unit
    }

    fun effectiveEpgUrls(src: Source?, playlist: Playlist?): List<String> =
        (src?.epgList().orEmpty()).ifEmpty { playlist?.epgUrls.orEmpty() }.take(MAX_EPG)

    /** Hesabın EPG linklerini yükleyip birleştirir. Biri başarısız olursa diğerleri yine kullanılır. */
    suspend fun loadEpg(src: Source, playlist: Playlist, forceDownload: Boolean): Pair<EpgIndex, List<String>> = withContext(Dispatchers.IO) {
        val urls = effectiveEpgUrls(src, playlist)
        if (urls.isEmpty()) return@withContext EpgIndex.EMPTY to emptyList()
        val now = System.currentTimeMillis()
        val errors = ArrayList<String>()
        val parts = coroutineScope {
            urls.map { url ->
                async {
                    val file = File(dir, epgName(url))
                    try {
                        if (forceDownload || !file.exists()) {
                            val tmp = File(file.path + ".tmp")
                            fetch(url, tmp)
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
        // Hiçbir hesabın kullanmadığı EPG dosyalarını sil.
        val keep = sources.flatMap { it.epgList() }.plus(urls).map { epgName(it) }.toSet()
        dir.listFiles { f -> f.name.startsWith("epg_") && f.name.endsWith(".dat") && f.name !in keep }?.forEach { it.delete() }

        (if (parts.isEmpty()) EpgIndex.EMPTY else EpgIndex.merge(parts)) to errors
    }

    private fun epgName(url: String) = "epg_${url.hashCode().toUInt().toString(16)}.dat"

    private fun fetch(url: String, target: File) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sunucu hatası: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Boş yanıt")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    // ---- Taşıma ----

    /** v0.3 ve öncesi: tek playlist_url ayarını hesap listesine taşır, favorileri korur. */
    private fun migrateSingleUrl() {
        if (prefs.contains("sources")) return
        val old = prefs.getString("playlist_url", "")?.trim().orEmpty()
        val edit = prefs.edit()
        if (old.isNotEmpty()) {
            val id = "s1"
            edit.putString("sources", JSONArray().put(JSONObject().put("id", id).put("name", "Liste 1").put("url", old)).toString())
            edit.putStringSet("favorites", favorites().map { "$id/$it" }.toSet())
            prefs.getString("last_channel", null)?.let { edit.putString("last_channel_$id", "$id/$it") }
            File(dir, "playlist.m3u").takeIf { it.exists() }?.renameTo(playlistFile(id))
        } else {
            edit.putString("sources", "[]")
        }
        edit.remove("playlist_url").apply()
    }

    /** v0.4–0.5: ortak EPG linkini hesaplara dağıtır, renk ve aktif hesap atar. */
    private fun migrateToAccounts() {
        if (prefs.getInt("schema", 0) >= 2) return
        val globalEpg = prefs.getString("epg_url", "")?.trim().orEmpty()
        val lastGlobal = prefs.getString("last_channel", null)
        val list = sources
        val colored = list.mapIndexed { i, s ->
            s.copy(
                color = s.color.ifEmpty { Source.COLORS[i % Source.COLORS.size] },
                epg = s.epg.ifEmpty { globalEpg },
            )
        }
        val edit = prefs.edit()
        val arr = JSONArray().also { a -> colored.forEach { a.put(toJson(it)) } }
        edit.putString("sources", arr.toString())
        if (lastGlobal != null) {
            val owner = colored.firstOrNull { lastGlobal.startsWith("${it.id}/") }
            if (owner != null) edit.putString("last_channel_${owner.id}", lastGlobal)
        }
        if (prefs.getString("active_id", null) == null) {
            val owner = colored.firstOrNull { lastGlobal?.startsWith("${it.id}/") == true } ?: colored.firstOrNull()
            owner?.let { edit.putString("active_id", it.id) }
        }
        edit.remove("epg_url").remove("last_channel").putInt("schema", 2).apply()
    }

    companion object {
        const val HOUR = 3_600_000L
        const val MAX_EPG = 5
        const val USER_AGENT = "TvKutu/0.6 (Android TV)"

        /** Resim (logo) istekleri için tarayıcı benzeri kimlik; bazı sunucular okhttp'yi engeller. */
        const val IMAGE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        fun newSourceId(): String = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
            (100..999).random()
    }
}
