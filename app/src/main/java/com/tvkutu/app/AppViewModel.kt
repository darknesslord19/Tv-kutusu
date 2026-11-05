package com.tvkutu.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvkutu.app.data.Channel
import com.tvkutu.app.data.EpgIndex
import com.tvkutu.app.data.Playlist
import com.tvkutu.app.data.Repository
import com.tvkutu.app.data.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Setup, QrSetup, Home, Player }

/** Kenar çubuğundaki bir satır: tüm kanallar, favoriler, kaynak başlığı ya da kategori. */
data class GroupItem(val id: String, val label: String, val count: Int, val isSource: Boolean = false)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repository(app)

    var screen by mutableStateOf(if (repo.hasPlaylist) Screen.Home else Screen.Setup)
        private set
    /** Kurulum ekranı ayar olarak mı açıldı (geri tuşu ana ekrana döner)? */
    var setupFromSettings by mutableStateOf(false)
        private set

    var playlist by mutableStateOf<Playlist?>(null)
        private set
    var sources by mutableStateOf(repo.sources)
        private set

    /** Kategori kimliği → kanallar (liste değişince bir kez hesaplanır). */
    private var groupIndex: Map<String, List<Channel>> = emptyMap()
    private var groupLabels: Map<String, String> = emptyMap()
    private var baseGroups: List<GroupItem> = emptyList()
    var epg by mutableStateOf(EpgIndex.EMPTY)
        private set
    var favorites by mutableStateOf(repo.favorites())
        private set

    var loading by mutableStateOf(false)
        private set
    var epgLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** Son liste yükleme hatası (ayarlar ekranında gösterilir). */
    var loadError by mutableStateOf<String?>(null)
        private set

    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    var updateHours by mutableStateOf(repo.updateHours)
        private set
    var playerMode by mutableStateOf(repo.playerMode)
        private set
    var streamFormat by mutableStateOf(repo.streamFormat)
        private set
    var posterMode by mutableStateOf(repo.posterMode)
        private set
    var posterTitle by mutableStateOf(repo.posterTitle)
        private set

    /** Yüklenen görsellerin en/boy oranı (logo adresi → genişlik/yükseklik). */
    private val aspects = mutableStateMapOf<String, Float>()

    var selectedGroup by mutableStateOf(ALL)
    var currentChannel by mutableStateOf<Channel?>(null)
        private set

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
        // Uygulama açıkken seçilen aralıkla liste ve yayın akışını yenile.
        viewModelScope.launch {
            while (true) {
                delay(15 * 60_000L)
                if (playlist != null && !loading && repo.isStale()) refresh(silent = true)
            }
        }
        if (repo.hasPlaylist) start()
    }

    // ---- Gruplar ----
    val groups: List<GroupItem>
        get() {
            if (playlist == null) return emptyList()
            val fav = favorites
            val list = ArrayList<GroupItem>(baseGroups.size + 1)
            if (fav.isNotEmpty()) list += GroupItem(FAVORITES, FAVORITES, channelsIn(FAVORITES).size)
            list += baseGroups
            return list
        }

    fun groupLabel(id: String): String = when (id) {
        ALL, FAVORITES -> id
        else -> groupLabels[id] ?: ALL
    }

    fun channelsIn(group: String): List<Channel> {
        val pl = playlist ?: return emptyList()
        return when (group) {
            ALL -> pl.channels
            FAVORITES -> pl.channels.filter { it.key in favorites }
            else -> groupIndex[group].orEmpty()
        }
    }

    fun reportAspect(url: String, ratio: Float) {
        if (ratio > 0f && url !in aspects) aspects[url] = ratio
    }

    /**
     * Bu kategori afiş kartlarıyla mı gösterilsin?
     * Otomatik modda: görseller yüklendikçe ölçülen oranlara (dikey = afiş) bakılır;
     * henüz ölçüm yoksa linklerden tahmin edilir (film/dizi uzantıları, TMDB afişleri).
     */
    fun isPosterGroup(groupId: String): Boolean = when (posterMode) {
        "always" -> true
        "never" -> false
        else -> {
            val sample = channelsIn(groupId).take(24)
            val measured = sample.mapNotNull { ch -> logoOf(ch)?.let { aspects[it] } }
            if (measured.size >= 3) measured.count { it < 0.85f } * 2 > measured.size
            else sample.isNotEmpty() && sample.count { looksLikeVod(it) } * 2 > sample.size
        }
    }

    private fun looksLikeVod(ch: Channel): Boolean {
        val logo = logoOf(ch).orEmpty()
        return VOD_URL.containsMatchIn(ch.url) || logo.contains("tmdb.org", true) ||
            logo.contains("/w600_and_h900", true) || logo.contains("poster", true)
    }

    /** "Tüm kanallar" ya da bir liste başlığı seçiliyken satır satır gösterilecek kategoriler. */
    fun rowGroups(selected: String): List<GroupItem> {
        val items = groups
        return when {
            selected == ALL -> items.filter { it.id == FAVORITES || it.id.startsWith("grp:") }
            selected.startsWith("src:") -> {
                val sid = selected.removePrefix("src:")
                items.filter { it.id.startsWith("grp:$sid\u0001") }
            }
            else -> emptyList()
        }
    }

    fun showsRows(selected: String) = selected == ALL || selected.startsWith("src:")

    fun setPosterModeOption(v: String) { repo.posterMode = v; posterMode = v }
    fun setPosterTitleOption(v: String) { repo.posterTitle = v; posterTitle = v }

    /** Kanal logosu: listedeki tvg-logo, yoksa EPG'deki ikon. */
    fun logoOf(ch: Channel): String? = ch.logo ?: epg.logo(ch)

    private fun applyPlaylist(pl: Playlist?) {
        playlist = pl
        if (pl == null) {
            groupIndex = emptyMap(); groupLabels = emptyMap(); baseGroups = emptyList()
            return
        }
        val multi = sources.size > 1
        val index = LinkedHashMap<String, MutableList<Channel>>()
        val labels = HashMap<String, String>()
        val items = ArrayList<GroupItem>()
        items += GroupItem(ALL, ALL, pl.channels.size)
        val bySource = pl.channels.groupBy { it.sourceId }
        val ordered = sources.map { it.id }.filter { it in bySource } + bySource.keys.filter { id -> sources.none { it.id == id } }
        for (sid in ordered) {
            val chans = bySource[sid].orEmpty()
            val srcName = sources.firstOrNull { it.id == sid }?.name?.ifBlank { null } ?: "Liste"
            if (multi) {
                val sKey = "src:$sid"
                index[sKey] = chans.toMutableList()
                labels[sKey] = srcName
                items += GroupItem(sKey, srcName, chans.size, isSource = true)
            }
            val groupOrder = LinkedHashMap<String, MutableList<Channel>>()
            chans.forEach { groupOrder.getOrPut(it.group) { ArrayList() }.add(it) }
            groupOrder.forEach { (g, list) ->
                val gKey = "grp:$sid\u0001$g"
                index[gKey] = list
                labels[gKey] = if (multi) "$srcName · $g" else g
                items += GroupItem(gKey, g, list.size)
            }
        }
        groupIndex = index
        groupLabels = labels
        baseGroups = items
        if (selectedGroup != ALL && selectedGroup != FAVORITES && selectedGroup !in index) selectedGroup = ALL
    }

    // ---- Eylemler ----
    private fun start() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            val cached = runCatching { repo.loadCachedPlaylist() }.getOrNull()
            if (cached != null && cached.channels.isNotEmpty()) {
                applyPlaylist(cached)
                loading = false
                loadEpg(cached, force = false)
                if (repo.isStale()) refresh(silent = true)
            } else {
                refresh(silent = false)
            }
            loading = false
        }
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) loading = true
            try {
                val res = repo.downloadAll()
                val pl = res.playlist
                applyPlaylist(pl)
                loadError = if (res.errors.isEmpty()) null
                    else res.errors.joinToString("\n") { "${it.first}: yüklenemedi (${it.second})" }
                loading = false
                if (!silent) {
                    message = buildString {
                        append("${sources.size - res.errors.size}/${sources.size} liste yüklendi · ${pl.channels.size} kanal")
                        if (res.errors.isNotEmpty()) append(" · ${res.errors.joinToString { it.first }} yüklenemedi")
                    }
                }
                loadEpg(pl, force = true)
            } catch (e: Exception) {
                message = "Liste yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
                loadError = message
                if (playlist == null && !silent) {
                    setupFromSettings = false
                    screen = Screen.Setup
                }
            } finally {
                loading = false
            }
        }
    }

    private suspend fun loadEpg(pl: Playlist, force: Boolean) {
        if (repo.effectiveEpgUrls(pl).isEmpty()) return
        epgLoading = true
        try {
            val (index, errors) = repo.loadEpg(pl, force)
            epg = index
            if (errors.isNotEmpty() && index.isEmpty) message = "Yayın akışı yüklenemedi: ${errors.first()}"
        } catch (e: Exception) {
            message = "Yayın akışı yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            epgLoading = false
        }
    }

    /**
     * Yeni kaynak listesini kaydeder. Aynı linke sahip kaynakların kimliği korunur
     * (favoriler kaybolmasın).
     */
    fun saveSetup(entries: List<Pair<String, String>>, epgUrl: String) {
        val old = repo.sources
        val usedIds = HashSet<String>()
        val newList = entries.mapIndexed { i, (name, url) ->
            val u = url.trim()
            val existing = old.firstOrNull { it.url == u && it.id !in usedIds }
            val id = existing?.id ?: Repository.newSourceId() + i
            usedIds += id
            Source(id, name.trim().ifEmpty { defaultName(u, i) }, u)
        }
        val changed = newList != old || epgUrl.trim() != repo.epgUrl
        repo.sources = newList
        repo.epgUrl = epgUrl
        sources = newList
        screen = Screen.Home
        if (changed || playlist == null) {
            applyPlaylist(null)
            epg = EpgIndex.EMPTY
            selectedGroup = ALL
            refresh(silent = false)
        }
    }

    private fun defaultName(url: String, i: Int): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")
        return if (host.isNullOrBlank()) "Liste ${i + 1}" else host
    }

    fun openSettings() {
        setupFromSettings = true
        screen = Screen.Setup
    }

    fun closeSettings() {
        if (repo.hasPlaylist) screen = Screen.Home
    }

    fun openQrSetup() {
        screen = Screen.QrSetup
    }

    fun closeQrSetup() {
        screen = Screen.Setup
    }

    /** Telefondan gelen linkler. */
    fun applyFromPhone(entries: List<Pair<String, String>>, epgUrl: String) {
        message = null
        saveSetup(entries, epgUrl)
        message = "${entries.size} link telefondan alındı, yükleniyor…"
    }

    fun setUpdateInterval(hours: Int) {
        repo.updateHours = hours
        updateHours = hours
    }

    fun play(channel: Channel) {
        currentChannel = channel
        repo.lastChannelKey = channel.key
        if (playerMode == PLAYER_EXTERNAL) {
            openExternal(channel)
        } else {
            screen = Screen.Player
        }
    }

    /** Kanalı cihazdaki başka bir oynatıcıda (VLC, MX Player…) açar. */
    fun openExternal(channel: Channel) {
        val app = getApplication<Application>()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(channel.url), "video/*")
            putExtra("title", channel.name)
            channel.userAgent?.let { ua ->
                putExtra("headers", arrayOf("User-Agent", ua)) // MX Player
                putExtra("http-user-agent", ua)                // VLC
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(app.packageManager) == null) {
            message = "Harici oynatıcı bulunamadı. VLC veya MX Player kur ya da Ayarlar'dan dahili oynatıcıyı seç."
            return
        }
        try {
            app.startActivity(Intent.createChooser(intent, channel.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            message = "Harici oynatıcı bulunamadı. VLC veya MX Player kur ya da Ayarlar'dan dahili oynatıcıyı seç."
        }
    }

    fun setPlayer(mode: String) {
        repo.playerMode = mode
        playerMode = mode
    }

    fun setFormat(format: String) {
        repo.streamFormat = format
        streamFormat = format
    }

    fun switchTo(channel: Channel) {
        currentChannel = channel
        repo.lastChannelKey = channel.key
    }

    fun exitPlayer() {
        screen = Screen.Home
    }

    fun toggleFavorite(channel: Channel) {
        favorites = repo.toggleFavorite(channel.key)
        if (selectedGroup == FAVORITES && favorites.isEmpty()) selectedGroup = ALL
    }

    fun consumeMessage() {
        message = null
    }

    companion object {
        const val ALL = "Tüm kanallar"
        const val FAVORITES = "★ Favoriler"
        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
        private val VOD_URL = Regex("""(?i)(/movies?/|/series/|/vod/|\.(mp4|mkv|avi|m4v|mov|webm)(\?|$))""")
    }
}
