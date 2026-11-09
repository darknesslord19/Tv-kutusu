package com.tvkutu.app

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvkutu.app.data.Backup
import com.tvkutu.app.data.Channel
import com.tvkutu.app.data.Json
import com.tvkutu.app.data.EpgIndex
import com.tvkutu.app.data.Playlist
import com.tvkutu.app.data.RemoteApi
import com.tvkutu.app.data.RemoteOps
import com.tvkutu.app.data.RemoteServer
import com.tvkutu.app.data.Repository
import com.tvkutu.app.data.Source
import com.tvkutu.app.data.Sync
import com.tvkutu.app.data.SyncDiscovery
import com.tvkutu.app.ui.Palette
import com.tvkutu.app.ui.Themes
import com.tvkutu.app.data.WatchProgress
import com.tvkutu.app.data.progressKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.Locale

enum class Screen { Setup, QrSetup, Accounts, Home, Player }

/** Kenar çubuğundaki bir satır: tüm kanallar, favoriler ya da kategori. */
data class GroupItem(val id: String, val label: String, val count: Int, val isSource: Boolean = false, val locked: Boolean = false)

/** Telefon kumandasından oynatıcıya giden komutlar. */
sealed interface PlayerCommand {
    data class SeekBy(val ms: Long) : PlayerCommand
    data class SeekTo(val ms: Long) : PlayerCommand
    data object Toggle : PlayerCommand
    data object Prev : PlayerCommand
    data object Next : PlayerCommand
}

/** Oynatıcının bildirdiği anlık durum (kumanda için). */
data class PlaybackInfo(val posMs: Long = 0, val durMs: Long = 0, val playing: Boolean = false, val vod: Boolean = false)

/** "Kaldığın yerden devam et?" penceresi isteği. */
data class ResumeRequest(val channel: Channel, val progress: WatchProgress)

/** İlk eşleşmeden sonra "hangi veriler esas alınsın?" sorusu. */
data class SyncChoice(val peer: Repository.SyncPeer)

/** TV'de PIN penceresi isteği. [create]: yeni PIN belirleme (iki kez sorulur). */
data class PinRequest(val title: String, val subtitle: String, val create: Boolean = false, val onPin: (String) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repository(app)

    // ---- Hesaplar ----
    var sources by mutableStateOf(repo.sources)
        private set
    var activeId by mutableStateOf(repo.activeSource()?.id)
        private set
    val activeSource: Source? get() = sources.firstOrNull { it.id == activeId }
    var checking by mutableStateOf(false)
        private set
    private var lastCheckMs = 0L

    // ---- Ekran ----
    var screen by mutableStateOf(Screen.Setup)
        private set
    /** Kurulum ekranı ayar olarak mı açıldı (geri tuşu ana ekrana döner)? */
    var setupFromSettings by mutableStateOf(false)
        private set

    // ---- Aktif hesabın içeriği ----
    var playlist by mutableStateOf<Playlist?>(null)
        private set
    /** Kategori kimliği → kanallar (liste değişince bir kez hesaplanır). */
    private var groupIndex: Map<String, List<Channel>> = emptyMap()
    private var groupLabels: Map<String, String> = emptyMap()
    private var baseGroups: List<GroupItem> = emptyList()
    private var channelByKey: Map<String, Channel> = emptyMap()
    /** Kaldığı yer anahtarı → kanal (eski, linke bağlı anahtarlar da dahil). */
    private var channelByProgressKey: Map<String, Channel> = emptyMap()
    var epg by mutableStateOf(EpgIndex.EMPTY)
        private set
    var favorites by mutableStateOf(repo.favorites())
        private set
    /** Aktif hesabın yarıda kalanları (kanal anahtarı → ilerleme). */
    var progress by mutableStateOf<Map<String, WatchProgress>>(emptyMap())
        private set
    var resumeRequest by mutableStateOf<ResumeRequest?>(null)
        private set
    /** Oynatıcının başlayacağı konum (ms); oynatıcı bir kez okur. */
    private var pendingStartMs = 0L

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

    // ---- Ayarlar ----
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
    var themeId by mutableStateOf(repo.theme)
        private set

    // ---- Ebeveyn kilidi ----
    var hasPin by mutableStateOf(repo.hasPin)
        private set
    var lockedGroups by mutableStateOf(repo.lockedGroups)
        private set
    /** Bu oturumda PIN girildi mi (uygulama arka plana gidince sıfırlanır)? */
    var unlocked by mutableStateOf(false)
        private set
    var pinRequest by mutableStateOf<PinRequest?>(null)
        private set

    /** Yüklenen görsellerin en/boy oranı (logo adresi → genişlik/yükseklik). */
    private val aspects = mutableStateMapOf<String, Float>()

    var selectedGroup by mutableStateOf(ALL)
        private set
    var currentChannel by mutableStateOf<Channel?>(null)
        private set

    private var loadJob: Job? = null

    // ---- Telefon kumandası ----
    private val api = RemoteApi(RemoteBridge())
    private val server = RemoteServer(
        code = repo.remoteCode,
        page = { remotePage },
        handler = { action, body -> runBlocking(Dispatchers.Main) { api.handle(action, body) } },
        syncHandler = { action, headers, body -> runBlocking(Dispatchers.Main) { handleSync(action, headers, body) } },
    )
    private val remotePage: String by lazy {
        app.assets.open("remote.html").use { it.readBytes().toString(Charsets.UTF_8) }
    }
    var remoteUrl by mutableStateOf<String?>(null)
        private set

    private val _remoteKeys = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    /** Telefondan gelen tuşlar (Android tuş kodu); MainActivity pencereye iletir. */
    val remoteKeys: SharedFlow<Int> = _remoteKeys
    private val _playerCommands = MutableSharedFlow<PlayerCommand>(extraBufferCapacity = 32)
    val playerCommands: SharedFlow<PlayerCommand> = _playerCommands
    @Volatile private var playback = PlaybackInfo()

    fun reportPlayback(info: PlaybackInfo) { playback = info }

    /** Kumandadaki "Ana ekran": açık pencereleri kapatıp ana ekrana döner. */
    fun goHome() {
        pinRequest = null
        resumeRequest = null
        val a = activeSource
        screen = when {
            a == null -> Screen.Setup
            a.locked && hasPin && !unlocked -> Screen.Accounts
            else -> Screen.Home
        }
        if (screen == Screen.Home && playlist == null) start()
    }

    private val audio: AudioManager? get() = getApplication<Application>().getSystemService(AudioManager::class.java)

    private fun volumeState(): Map<String, Any?> {
        val a = audio ?: return mapOf("vol" to null)
        return mapOf(
            "vol" to a.getStreamVolume(AudioManager.STREAM_MUSIC),
            "max" to a.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            "muted" to a.isStreamMute(AudioManager.STREAM_MUSIC),
        )
    }

    // ---- Wi‑Fi eşitleme durumu (init içinde kullanıldığı için init'ten önce) ----
    val deviceName: String = runCatching {
        android.provider.Settings.Global.getString(app.contentResolver, "device_name")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "TvKutu"

    /** Aynı ağda bulunan TvKutu'lar. */
    var nearby by mutableStateOf<List<SyncDiscovery.Peer>>(emptyList())
        private set
    var peers by mutableStateOf(repo.pairs)
        private set
    var autoSync by mutableStateOf(repo.autoSync)
        private set
    var syncCode by mutableStateOf(newSyncCode())
        private set
    var syncCodeExpires by mutableLongStateOf(System.currentTimeMillis() + SYNC_CODE_MS)
        private set
    var syncBusy by mutableStateOf<String?>(null)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var syncChoice by mutableStateOf<SyncChoice?>(null)
        private set
    private var discoveryStarted = false
    private val discovery = SyncDiscovery(app, repo.deviceId) { list -> nearby = list }
    private val pairFailures = ArrayDeque<Long>()
    private var lastSyncedHash = 0
    private val syncHttp by lazy {
        repo.http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    init {
        Palette.theme = Themes.byId(repo.theme)
        startRemote()
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
        // Wi‑Fi eşitleme: değişiklik olunca (ve açılışta) eşleşmiş cihazlarla eşitle.
        viewModelScope.launch {
            delay(8_000)
            while (true) {
                runCatching { autoSyncTick() }
                delay(45_000)
            }
        }
        // Uygulama açıkken seçilen aralıkla aktif hesabı yenile.
        viewModelScope.launch {
            while (true) {
                delay(15 * 60_000L)
                val id = activeId
                if (id != null && playlist != null && !loading && repo.isStale(id)) refresh(silent = true)
            }
        }
        val active = activeSource
        screen = when {
            active == null -> Screen.Setup
            active.locked && hasPin -> Screen.Accounts // kilitli hesap: önce PIN
            else -> Screen.Home
        }
        if (screen == Screen.Home) start()
        autoBackup()
    }

    override fun onCleared() {
        server.stop()
        runCatching { discovery.stop() }
    }

    /** Sunucuyu başlatır (çalışıyorsa adresi tazeler). */
    fun startRemote() {
        runCatching {
            val port = server.start(repo.remotePort)
            if (port != repo.remotePort && port > 0) repo.remotePort = port
            val ip = RemoteServer.localIpv4()
            remoteUrl = if (ip == null) null else "http://$ip:$port/s/${server.code}"
            if (!discoveryStarted) {
                discoveryStarted = true
                runCatching { discovery.start(port, deviceName) }
            }
        }.onFailure { remoteUrl = null }
    }

    // ---- Gruplar ----
    val groups: List<GroupItem>
        get() {
            if (playlist == null) return emptyList()
            val list = ArrayList<GroupItem>(baseGroups.size + 2)
            val cont = channelsIn(CONTINUE).size
            if (cont > 0) list += GroupItem(CONTINUE, CONTINUE, cont)
            if (favorites.any { it in channelByKey }) list += GroupItem(FAVORITES, FAVORITES, channelsIn(FAVORITES).size)
            baseGroups.forEach { g -> list += if (g.id in lockedGroups) g.copy(locked = true) else g }
            return list
        }

    fun groupLabel(id: String): String = when (id) {
        ALL, FAVORITES, CONTINUE -> if (id == CONTINUE) "İzlemeye devam et" else id
        else -> groupLabels[id] ?: ALL
    }

    fun channelsIn(group: String): List<Channel> {
        val pl = playlist ?: return emptyList()
        return when (group) {
            ALL -> pl.channels
            FAVORITES -> pl.channels.filter { it.key in favorites }
            CONTINUE -> progress.entries.sortedByDescending { it.value.updatedMs }
                .mapNotNull { channelByProgressKey[it.key] ?: channelByKey[it.key] }
                .distinctBy { it.key }.filterNot { isChannelBlocked(it) }
            else -> groupIndex[group].orEmpty()
        }
    }

    private fun groupIdOf(ch: Channel) = "grp:${ch.sourceId}\u0001${ch.group}"

    /** Kanal kilitli bir kategoride mi ve bu oturumda kilit açılmamış mı? */
    fun isChannelBlocked(ch: Channel) = !unlocked && hasPin && groupIdOf(ch) in lockedGroups

    fun isGroupBlocked(id: String) = !unlocked && hasPin && id in lockedGroups

    /**
     * Kategori seçer. Kilitli kategori için PIN sorar; kumandayla üzerinden geçerken
     * ([fromFocus]) kilitli kategori seçilmez, OK'a basınca PIN sorulur.
     */
    fun selectGroup(id: String, fromFocus: Boolean = false) {
        if (isGroupBlocked(id)) {
            if (!fromFocus) requestPin("Kategori kilitli", groupLabel(id)) { selectedGroup = id }
            return
        }
        selectedGroup = id
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

    /** "Tüm kanallar" seçiliyken satır satır gösterilecek kategoriler (kilitliler gizli). */
    fun rowGroups(selected: String): List<GroupItem> =
        if (selected != ALL) emptyList()
        else groups.filter { (it.id == CONTINUE || it.id == FAVORITES || it.id.startsWith("grp:")) && !isGroupBlocked(it.id) }

    fun showsRows(selected: String) = selected == ALL

    fun setPosterModeOption(v: String) { repo.posterMode = v; posterMode = v }
    fun setTheme(id: String) {
        val t = Themes.byId(id)
        repo.theme = t.id
        themeId = t.id
        Palette.theme = t
    }
    fun setPosterTitleOption(v: String) { repo.posterTitle = v; posterTitle = v }

    /** Kanal logosu: listedeki tvg-logo, yoksa EPG'deki ikon. */
    fun logoOf(ch: Channel): String? = ch.logo ?: epg.logo(ch)

    fun lastChannelKey(): String? = currentChannel?.key ?: activeId?.let { repo.lastChannel(it) }

    private fun applyPlaylist(pl: Playlist?) {
        playlist = pl
        if (pl == null) {
            groupIndex = emptyMap(); groupLabels = emptyMap(); baseGroups = emptyList(); channelByKey = emptyMap()
            channelByProgressKey = emptyMap()
            return
        }
        val index = LinkedHashMap<String, MutableList<Channel>>()
        val labels = HashMap<String, String>()
        val items = ArrayList<GroupItem>()
        items += GroupItem(ALL, ALL, pl.channels.size)
        pl.channels.forEach { index.getOrPut(groupIdOf(it)) { ArrayList() }.add(it) }
        index.forEach { (gKey, list) ->
            val g = list.first().group
            labels[gKey] = g
            items += GroupItem(gKey, g, list.size)
        }
        groupIndex = index
        groupLabels = labels
        baseGroups = items
        channelByKey = pl.channels.associateBy { it.key }
        channelByProgressKey = HashMap<String, Channel>().also { m -> pl.channels.forEach { m.putIfAbsent(it.progressKey(), it) } }
        if (selectedGroup != ALL && selectedGroup != FAVORITES && selectedGroup != CONTINUE && selectedGroup !in index) selectedGroup = ALL
    }

    // ---- Yükleme (yalnızca aktif hesap) ----
    private fun start() {
        val src = activeSource ?: return
        progress = repo.progress(src.id)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            val cached = runCatching { repo.loadCached(src) }.getOrNull()
            if (cached != null && activeId == src.id) {
                applyPlaylist(cached)
                loading = false
                loadEpg(src, cached, force = false)
                if (repo.isStale(src.id)) refresh(silent = true)
            } else {
                refresh(silent = false)
            }
            loading = false
        }
    }

    fun refresh(silent: Boolean = false) {
        val src = activeSource ?: return
        viewModelScope.launch {
            if (!silent) loading = true
            try {
                val pl = repo.download(src)
                sources = repo.sources
                if (activeId != src.id) return@launch
                applyPlaylist(pl)
                loadError = null
                loading = false
                if (!silent) message = "${src.name} · ${pl.channels.size} kanal"
                loadEpg(src, pl, force = true)
            } catch (e: Exception) {
                sources = repo.sources
                if (activeId != src.id) return@launch
                val msg = "${src.name} yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
                loadError = msg
                if (playlist == null) message = msg
            } finally {
                loading = false
            }
        }
    }

    private suspend fun loadEpg(src: Source, pl: Playlist, force: Boolean) {
        if (repo.effectiveEpgUrls(src, pl).isEmpty()) return
        epgLoading = true
        try {
            val (index, errors) = repo.loadEpg(src, pl, force)
            if (activeId != src.id) return
            epg = index
            if (errors.isNotEmpty() && index.isEmpty) message = "Yayın akışı yüklenemedi: ${errors.first()}"
        } catch (e: Exception) {
            message = "Yayın akışı yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            epgLoading = false
        }
    }

    // ---- Hesap işlemleri ----

    /** Hesabı açar. Kilitliyse ve PIN doğrulanmadıysa önce PIN sorar. */
    fun openAccount(id: String, pinVerified: Boolean = false) {
        val src = sources.firstOrNull { it.id == id } ?: return
        if (src.locked && hasPin && !unlocked && !pinVerified) {
            requestPin("${src.name} kilitli", "Açmak için PIN'i gir") { openAccount(id, pinVerified = true) }
            return
        }
        if (src.locked && pinVerified) unlocked = true
        val switching = id != activeId || playlist == null
        activeId = id
        repo.activeId = id
        screen = Screen.Home
        if (switching) {
            loadJob?.cancel()
            currentChannel = null
            applyPlaylist(null)
            epg = EpgIndex.EMPTY
            loadError = null
            selectedGroup = ALL
            message = null
            resumeRequest = null
            start()
        }
    }

    fun openAccounts() {
        screen = Screen.Accounts
        checkAll(force = false)
    }

    fun closeAccounts() {
        val a = activeSource
        if (a != null && !(a.locked && hasPin && !unlocked)) {
            screen = Screen.Home
            if (playlist == null) start()
        }
    }

    /** Tüm hesapların bağlantısını kontrol eder (en fazla dakikada bir). */
    fun checkAll(force: Boolean) {
        if (checking || sources.isEmpty()) return
        if (!force && System.currentTimeMillis() - lastCheckMs < 60_000) return
        lastCheckMs = System.currentTimeMillis()
        viewModelScope.launch {
            checking = true
            try { repo.checkAll() } finally {
                sources = repo.sources
                checking = false
            }
        }
    }

    private fun saveSources(list: List<Source>) {
        repo.sources = list
        sources = repo.sources
        autoBackup()
    }

    /** Ekler ya da günceller; aktif hesabın linki değiştiyse yeniden yükler. */
    fun upsertAccount(src: Source, openNow: Boolean) {
        val old = sources.firstOrNull { it.id == src.id }
        saveSources(if (old == null) sources + src else sources.map { if (it.id == src.id) src else it })
        when {
            openNow -> {
                if (old != null && src.id == activeId && (old.url != src.url || old.epg != src.epg)) activeId = null
                openAccount(src.id, pinVerified = true)
            }
            src.id == activeId && old != null && (old.url != src.url || old.epg != src.epg) -> {
                applyPlaylist(null); epg = EpgIndex.EMPTY; start()
            }
            activeId == null -> openAccount(src.id, pinVerified = true)
        }
    }

    fun addAccountManual(name: String, url: String, epgUrl: String): String? {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return "Link http:// veya https:// ile başlamalı."
        if (sources.any { it.url == u }) return "Bu link zaten ekli."
        if (sources.size >= 20) return "En fazla 20 hesap eklenebilir."
        val src = Source(
            id = Repository.newSourceId(),
            name = name.trim().take(40).ifEmpty { defaultName(u, sources.size) },
            url = u, color = repo.nextColor(), epg = epgUrl.trim(),
        )
        upsertAccount(src, openNow = true)
        return null
    }

    fun deleteAccount(id: String, pinVerified: Boolean = false) {
        val src = sources.firstOrNull { it.id == id } ?: return
        if (src.locked && hasPin && !unlocked && !pinVerified) {
            requestPin("${src.name} kilitli", "Silmek için PIN'i gir") { deleteAccount(id, pinVerified = true) }
            return
        }
        saveSources(sources.filterNot { it.id == id })
        if (activeId == id) {
            val next = sources.firstOrNull()
            activeId = null
            applyPlaylist(null)
            epg = EpgIndex.EMPTY
            if (next == null) {
                repo.activeId = null
                setupFromSettings = false
                screen = Screen.Setup
            } else if (screen == Screen.Home || screen == Screen.Player) {
                openAccount(next.id)
            } else {
                repo.activeId = next.id
                activeId = next.id
            }
        }
    }

    fun toggleAccountLock(id: String) {
        val src = sources.firstOrNull { it.id == id } ?: return
        if (!hasPin) {
            createPin { toggleAccountLock(id) }
            return
        }
        withPin("Kilit ayarı", src.name) {
            saveSources(sources.map { if (it.id == id) it.copy(locked = !it.locked) else it })
        }
    }

    private fun defaultName(url: String, i: Int): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")
        return if (host.isNullOrBlank()) "Hesap ${i + 1}" else host
    }

    // ---- PIN ----
    private fun requestPin(title: String, subtitle: String, onOk: () -> Unit) {
        pinRequest = PinRequest(title, subtitle) { pin ->
            if (repo.checkPin(pin)) {
                pinRequest = null
                unlocked = true
                onOk()
            } else {
                message = "PIN yanlış"
                pinRequest = pinRequest?.copy(subtitle = "PIN yanlış, tekrar dene")
            }
        }
    }

    /** PIN varsa ve oturum kilitliyse sorar, yoksa doğrudan çalıştırır. */
    fun withPin(title: String, subtitle: String, action: () -> Unit) {
        if (!hasPin || unlocked) action() else requestPin(title, subtitle, action)
    }

    fun createPin(then: (() -> Unit)? = null) {
        pinRequest = PinRequest("PIN belirle", "Kilitler için 4 haneli bir PIN seç", create = true) { pin ->
            repo.setPin(pin)
            hasPin = true
            unlocked = true
            pinRequest = null
            message = "PIN belirlendi"
            then?.invoke()
        }
    }

    fun changePin() = withPin("PIN'i değiştir", "Önce mevcut PIN'i gir") { createPin() }

    fun removePin() = withPin("PIN'i kaldır", "Mevcut PIN'i gir") {
        repo.setPin(null)
        hasPin = false
        // PIN olmadan kilitlerin anlamı yok; kaldır.
        saveSources(sources.map { it.copy(locked = false) })
        repo.lockedGroups = emptySet()
        lockedGroups = emptySet()
        message = "PIN ve tüm kilitler kaldırıldı"
    }

    fun toggleGroupLock(id: String) {
        if (!hasPin) {
            createPin { toggleGroupLock(id) }
            return
        }
        withPin("Kategori kilidi", groupLabel(id)) {
            val set = lockedGroups.toMutableSet()
            if (!set.remove(id)) set += id
            repo.lockedGroups = set
            lockedGroups = set
        }
    }

    fun cancelPin() { pinRequest = null }

    /** Uygulama arka plana gidince kilitleri yeniden etkinleştir. */
    fun lockSession() {
        if (!unlocked) return
        unlocked = false
        pinRequest = null
        val a = activeSource
        if (a != null && a.locked && hasPin) {
            screen = Screen.Accounts
        } else if (isGroupBlocked(selectedGroup)) {
            selectedGroup = ALL
            if (screen == Screen.Player) screen = Screen.Home
        }
    }

    // ---- Ekranlar ----
    fun openSettings() {
        setupFromSettings = true
        screen = Screen.Setup
    }

    fun closeSettings() {
        if (activeSource != null) {
            screen = Screen.Home
            if (playlist == null) start()
        }
    }

    fun openQrSetup() {
        startRemote()
        screen = Screen.QrSetup
    }

    fun closeQrSetup() {
        screen = if (setupFromSettings || activeSource == null) Screen.Setup else Screen.Accounts
    }

    fun setUpdateInterval(hours: Int) {
        repo.updateHours = hours
        updateHours = hours
    }

    fun play(channel: Channel) {
        if (isChannelBlocked(channel)) {
            requestPin("Kategori kilitli", channel.group) { play(channel) }
            return
        }
        val saved = progressOf(channel)
        if (saved != null && playerMode != PLAYER_EXTERNAL) {
            resumeRequest = ResumeRequest(channel, saved)
            return
        }
        startPlayback(channel, 0L)
    }

    /** Oynatmayı başlatır; [startMs] > 0 ise o konumdan. */
    fun startPlayback(channel: Channel, startMs: Long) {
        resumeRequest = null
        pendingStartMs = startMs
        currentChannel = channel
        activeId?.let { repo.setLastChannel(it, channel.key) }
        if (playerMode == PLAYER_EXTERNAL) {
            openExternal(channel)
        } else {
            screen = Screen.Player
        }
    }

    fun resumeFromSaved() { resumeRequest?.let { startPlayback(it.channel, it.progress.posMs) } }
    fun restartFromBeginning() { resumeRequest?.let { startPlayback(it.channel, 0L) } }
    fun dismissResume() { resumeRequest = null }

    fun removeFromContinue(channel: Channel) {
        val id = activeId ?: return
        progress = repo.removeProgress(id, channel.progressKey()).let { m ->
            if (channel.key in m) repo.removeProgress(id, channel.key) else m
        }
        if (resumeRequest?.channel?.key == channel.key) resumeRequest = null
        if (selectedGroup == CONTINUE && progress.isEmpty()) selectedGroup = ALL
    }

    /** Oynatıcı bir kez çağırır: başlangıç konumu (ms). */
    fun takeStartPosition(): Long = pendingStartMs.also { pendingStartMs = 0L }

    fun progressOf(ch: Channel): WatchProgress? = progress[ch.progressKey()] ?: progress[ch.key]

    /** Oynatıcıdan: film/dizi konumunu kaydet (canlı yayınlar için çağrılmaz). */
    fun updateProgress(ch: Channel, posMs: Long, durMs: Long, announce: Boolean = false) {
        val id = activeId ?: return
        if (ch.sourceId != id) return
        if (!WatchProgress.shouldKeep(posMs, durMs) && !WatchProgress.isFinished(posMs, durMs)) return
        // Eski (linke bağlı) kaydı yeni anahtara taşı.
        if (ch.key in progress) repo.removeProgress(id, ch.key)
        progress = repo.saveProgress(id, ch.progressKey(), posMs, durMs)
        if (announce) {
            message = if (WatchProgress.shouldKeep(posMs, durMs)) "Kaldığın yer kaydedildi: ${ch.name} · ${WatchProgress.clock(posMs)}"
            else "${ch.name} bitti, Devam et listesinden çıkarıldı"
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
        if (isChannelBlocked(channel)) return
        pendingStartMs = progressOf(channel)?.posMs ?: 0L
        currentChannel = channel
        activeId?.let { repo.setLastChannel(it, channel.key) }
    }

    fun exitPlayer() {
        screen = Screen.Home
    }

    fun toggleFavorite(channel: Channel) {
        favorites = repo.toggleFavorite(channel.key)
        if (selectedGroup == FAVORITES && channelsIn(FAVORITES).isEmpty()) selectedGroup = ALL
    }

    fun consumeMessage() {
        message = null
    }

    // ---- Wi‑Fi eşitleme ----
    private fun newSyncCode(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

    /** Süresi dolduysa yeni eşleme kodu üretir (ekran her saniye çağırabilir). */
    fun refreshSyncCode(force: Boolean = false) {
        if (force || System.currentTimeMillis() >= syncCodeExpires) {
            syncCode = newSyncCode()
            syncCodeExpires = System.currentTimeMillis() + SYNC_CODE_MS
        }
    }

    fun setAutoSyncOption(v: Boolean) { repo.autoSync = v; autoSync = v }

    /** Bu cihazın eşitleme görüntüsü (hesaplar link kimliğiyle). */
    fun buildSnap(): Sync.Snap {
        val list = sources
        val idToSid = list.associate { it.id to Sync.sid(it.url) }
        val accounts = HashMap<String, Sync.Acc>()
        repo.deletedAccounts().forEach { (url, ts) ->
            if (list.none { it.url == url }) accounts[Sync.sid(url)] = Sync.Acc(url, "", "", "", false, ts, deleted = true)
        }
        list.forEach { accounts[Sync.sid(it.url)] = Sync.Acc(it.url, it.name, it.color, it.epg, it.locked, it.updatedMs.coerceAtLeast(1)) }
        val progressAll = HashMap<String, WatchProgress>()
        list.forEach { s ->
            repo.progress(s.id).forEach { (k, w) -> Sync.toShared(k, idToSid)?.let { progressAll[it] = w } }
            repo.progressTombs(s.id).forEach { (k, ts) ->
                Sync.toShared(k, idToSid)?.let { key -> if ((progressAll[key]?.updatedMs ?: 0) < ts) progressAll[key] = WatchProgress(-1, 0, ts) }
            }
        }
        return Sync.Snap(
            accounts = accounts,
            favorites = repo.favLww().mapNotNull { (k, v) -> Sync.toShared(k, idToSid)?.let { it to v } }.toMap(),
            locks = repo.lockLww().mapNotNull { (k, v) -> Sync.toShared(k, idToSid)?.let { it to v } }.toMap(),
            progress = progressAll,
            settings = backupSettings() + ("theme" to themeId),
            settingsMs = repo.settingsMs,
        )
    }

    /** Görüntüyü bu cihaza uygular. [replace]: görüntüde olmayanlar silinir. */
    fun applySnap(snap: Sync.Snap, replace: Boolean) {
        val now = System.currentTimeMillis()
        val plan = Sync.planAccounts(sources, snap, replace, now) { Repository.newSourceId() }
        repo.persistSources(plan.sources)
        repo.addDeletedAccounts(plan.deletedUrls + snap.accounts.values.filter { it.deleted }.associate { it.url to it.ts })
        sources = repo.sources
        val idToSid = sources.associate { it.id to Sync.sid(it.url) }
        val sidToId = idToSid.entries.associate { (k, v) -> v to k }

        fun mergeLww(local: Map<String, Long>, shared: Map<String, Long>): Map<String, Long> {
            val incoming = shared.mapNotNull { (k, v) -> Sync.toLocal(k, sidToId)?.let { it to v } }.toMap()
            if (replace) return incoming
            val out = HashMap(local)
            incoming.forEach { (k, v) -> if (Math.abs(v) >= Math.abs(out[k] ?: 0L)) out[k] = v }
            return out
        }
        favorites = repo.applyFavLww(mergeLww(repo.favLww(), snap.favorites))
        lockedGroups = repo.applyLockLww(mergeLww(repo.lockLww(), snap.locks))

        sources.forEach { s ->
            val sid = idToSid[s.id] ?: return@forEach
            val mine = snap.progress.filterKeys { it.startsWith("p:$sid/") || it.startsWith("$sid/") }
            val live = if (replace) HashMap() else HashMap(repo.progress(s.id))
            val tombs = if (replace) HashMap() else HashMap(repo.progressTombs(s.id))
            mine.forEach { (k, w) ->
                val lk = Sync.toLocal(k, sidToId) ?: return@forEach
                val cur = maxOf(live[lk]?.updatedMs ?: 0, tombs[lk] ?: 0)
                if (w.updatedMs < cur) return@forEach
                if (w.posMs < 0) { live.remove(lk); tombs[lk] = w.updatedMs } else { live[lk] = w; tombs.remove(lk) }
            }
            repo.applyProgress(s.id, live, tombs)
        }
        activeId?.let { progress = repo.progress(it) }

        if (replace || snap.settingsMs > repo.settingsMs) {
            val st = snap.settings
            (st["posterMode"] as? String)?.takeIf { it in setOf("auto", "always", "never") }?.let { setPosterModeOption(it) }
            (st["posterTitle"] as? String)?.takeIf { it in setOf("overlay", "below") }?.let { setPosterTitleOption(it) }
            (st["playerMode"] as? String)?.takeIf { it in setOf(PLAYER_INTERNAL, PLAYER_EXTERNAL) }?.let { setPlayer(it) }
            (st["streamFormat"] as? String)?.takeIf { it in setOf("auto", "hls", "ts") }?.let { setFormat(it) }
            (st["updateHours"] as? Number)?.toInt()?.takeIf { it in setOf(0, 6, 12, 24) }?.let { setUpdateInterval(it) }
            (st["theme"] as? String)?.let { setTheme(it) }
            repo.settingsMs = snap.settingsMs
        }

        val a = activeSource
        if (a == null) {
            activeId = null
            sources.firstOrNull()?.let { openAccount(it.id, pinVerified = true) } ?: run {
                if (screen != Screen.Setup) { setupFromSettings = false; screen = Screen.Setup }
            }
        } else if (playlist == null && screen == Screen.Home) start()
        lastSyncedHash = Json.write(Sync.toJson(buildSnap())).hashCode()
        autoBackup()
    }

    /** Diğer cihazdan gelen eşitleme istekleri (ana iş parçacığında). */
    private fun handleSync(action: String, headers: Map<String, String>, body: Map<String, Any?>): Map<String, Any?> {
        when (action) {
            "hello" -> return mapOf("app" to "TvKutu", "deviceId" to repo.deviceId, "name" to deviceName, "v" to 1)
            "pair" -> {
                val now = System.currentTimeMillis()
                while (pairFailures.isNotEmpty() && now - pairFailures.first() > 60_000) pairFailures.removeFirst()
                if (pairFailures.size >= 8) return mapOf("error" to "Çok fazla hatalı deneme, biraz bekle")
                refreshSyncCode()
                val code = (body["code"] as? String)?.filter { it.isDigit() }.orEmpty()
                if (code != syncCode) {
                    pairFailures.addLast(now)
                    return mapOf("error" to "Kod eşleşmedi")
                }
                val fromId = body["deviceId"] as? String ?: return mapOf("error" to "Eksik bilgi")
                val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val peer = Repository.SyncPeer(
                    fromId, (body["name"] as? String)?.take(40) ?: "TvKutu", token,
                    headers["x-remote-addr"].orEmpty(), (body["port"] as? Number)?.toInt() ?: 0, 0,
                )
                repo.upsertPair(peer)
                peers = repo.pairs
                refreshSyncCode(force = true)
                message = "${peer.name} ile eşleşildi"
                return mapOf("ok" to true, "deviceId" to repo.deviceId, "name" to deviceName, "token" to token)
            }
        }
        // Eşleşmiş cihaz doğrulaması
        val fromId = body["deviceId"] as? String
        val token = headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val peer = peers.firstOrNull { it.deviceId == fromId && it.token == token }
            ?: return mapOf("error" to "Eşleşme bulunamadı", "unpaired" to true)
        val updated = peer.copy(
            host = headers["x-remote-addr"]?.takeIf { it.isNotBlank() } ?: peer.host,
            port = (body["port"] as? Number)?.toInt() ?: peer.port,
            lastSyncMs = System.currentTimeMillis(),
        )
        return when (action) {
            "exchange" -> {
                val result: Map<String, Any?> = when (body["mode"]) {
                    "pull" -> mapOf("ok" to true, "snapshot" to Sync.toJson(buildSnap()))
                    "replace" -> {
                        applySnap(Sync.fromJson(body["snapshot"]), replace = true)
                        message = "${peer.name} cihazındaki veriler alındı"
                        mapOf("ok" to true)
                    }
                    else -> {
                        val merged = Sync.merge(buildSnap(), Sync.fromJson(body["snapshot"]))
                        applySnap(merged, replace = false)
                        mapOf("ok" to true, "snapshot" to Sync.toJson(merged))
                    }
                }
                repo.upsertPair(updated); peers = repo.pairs
                result
            }
            "unpair" -> {
                repo.pairs = repo.pairs.filterNot { it.deviceId == peer.deviceId }
                peers = repo.pairs
                mapOf("ok" to true)
            }
            else -> mapOf("error" to "Bilinmeyen işlem")
        }
    }

    private suspend fun postJson(host: String, port: Int, path: String, body: Map<String, Any?>, token: String? = null): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("http://$host:$port/sync/$path")
                .post(Json.write(body).toRequestBody("application/json".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            syncHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Json.parseObject(text)
            }
        }

    /** Diğer cihazın kodunu yazıp eşleşir; ağda bulunan cihazlara sırayla dener. */
    fun pairWithCode(input: String) {
        val code = input.filter { it.isDigit() }
        if (code.length != 6) { syncError = "Kod 6 haneli olmalı"; return }
        val candidates = nearby
        if (candidates.isEmpty()) {
            syncError = "Ağda başka TvKutu bulunamadı. Diğer cihazda TvKutu açık ve aynı Wi‑Fi'de mi?"
            return
        }
        syncError = null
        syncBusy = "Bağlanılıyor…"
        viewModelScope.launch {
            var paired: Repository.SyncPeer? = null
            var lastErr: String? = null
            for (c in candidates) {
                val res = runCatching {
                    postJson(c.host, c.port, "pair", mapOf("code" to code, "deviceId" to repo.deviceId, "name" to deviceName, "port" to server.port))
                }.getOrElse { lastErr = it.message; null } ?: continue
                if (res["ok"] == true) {
                    paired = Repository.SyncPeer(
                        res["deviceId"] as? String ?: c.deviceId, res["name"] as? String ?: c.name,
                        res["token"] as? String ?: "", c.host, c.port, 0,
                    )
                    break
                } else lastErr = res["error"] as? String
            }
            syncBusy = null
            val p = paired
            if (p == null) {
                syncError = if (lastErr == "Kod eşleşmedi" || lastErr == null) "Kod hiçbir cihazla eşleşmedi. Diğer cihazdaki kodu kontrol et." else lastErr
                return@launch
            }
            repo.upsertPair(p)
            peers = repo.pairs
            syncChoice = SyncChoice(p) // ilk eşitleme: hangi veriler esas alınsın?
        }
    }

    /** İlk eşitleme seçimi: "merge", "mine" (bu cihazdaki) ya da "theirs" (diğer cihazdaki). */
    fun firstSync(choice: String) {
        val p = syncChoice?.peer ?: return
        syncChoice = null
        exchange(p, when (choice) { "mine" -> "replace"; "theirs" -> "pull"; else -> "merge" }, announce = true)
    }

    fun cancelFirstSync() { syncChoice = null }

    fun syncNow(deviceId: String) = peers.firstOrNull { it.deviceId == deviceId }?.let { exchange(it, "merge", announce = true) }

    fun removePeer(deviceId: String) {
        val p = peers.firstOrNull { it.deviceId == deviceId } ?: return
        repo.pairs = repo.pairs.filterNot { it.deviceId == deviceId }
        peers = repo.pairs
        viewModelScope.launch {
            val (h, port) = addressOf(p)
            runCatching { postJson(h, port, "unpair", mapOf("deviceId" to repo.deviceId), p.token) }
        }
    }

    private fun addressOf(p: Repository.SyncPeer): kotlin.Pair<String, Int> =
        nearby.firstOrNull { it.deviceId == p.deviceId }?.let { it.host to it.port } ?: (p.host to p.port)

    fun isPeerOnline(p: Repository.SyncPeer) = nearby.any { it.deviceId == p.deviceId }

    private fun exchange(p: Repository.SyncPeer, mode: String, announce: Boolean) {
        if (syncBusy != null) return
        syncBusy = "${p.name} ile eşitleniyor…"
        viewModelScope.launch {
            val (host, port) = addressOf(p)
            try {
                val body = linkedMapOf<String, Any?>("deviceId" to repo.deviceId, "mode" to mode, "port" to server.port)
                if (mode != "pull") body["snapshot"] = Sync.toJson(buildSnap())
                val res = postJson(host, port, "exchange", body, p.token)
                when {
                    res["unpaired"] == true -> {
                        repo.pairs = repo.pairs.filterNot { it.deviceId == p.deviceId }; peers = repo.pairs
                        throw IllegalStateException("${p.name} eşleşmeyi kaldırmış; yeniden eşleş")
                    }
                    res["error"] != null -> throw IllegalStateException(res["error"].toString())
                }
                val before = buildSnap()
                res["snapshot"]?.let { applySnap(Sync.fromJson(it), replace = mode == "pull") }
                repo.upsertPair(p.copy(host = host, port = port, lastSyncMs = System.currentTimeMillis()))
                peers = repo.pairs
                if (announce) message = "Eşitlendi: ${p.name} · " + summarizeChange(before, buildSnap())
                syncError = null
            } catch (e: Exception) {
                if (announce) syncError = "${p.name} ile eşitlenemedi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                syncBusy = null
            }
        }
    }

    private fun summarizeChange(a: Sync.Snap, b: Sync.Snap): String {
        val acc = Sync.liveAccounts(b).size
        val fav = b.favorites.count { it.value > 0 }
        val prog = b.progress.count { it.value.posMs >= 0 }
        return if (a == b) "değişiklik yok" else "$acc hesap, $fav favori, $prog kaldığın yer"
    }

    /** Değişiklik varsa ağdaki eşleşmiş cihazlarla sessizce eşitler. */
    private fun autoSyncTick() {
        if (!autoSync || peers.isEmpty() || syncBusy != null) return
        val h = Json.write(Sync.toJson(buildSnap())).hashCode()
        val online = peers.filter { isPeerOnline(it) }
        if (online.isEmpty()) return
        if (h == lastSyncedHash && online.all { System.currentTimeMillis() - it.lastSyncMs < 10 * 60_000 }) return
        lastSyncedHash = h
        online.firstOrNull()?.let { exchange(it, "merge", announce = false) }
    }

    // ---- Yedekleme ----
    private fun backupSettings(): Map<String, Any?> = mapOf(
        "posterMode" to posterMode, "posterTitle" to posterTitle, "playerMode" to playerMode,
        "streamFormat" to streamFormat, "updateHours" to updateHours, "theme" to themeId,
    )

    fun backupExtras(): Backup.Extras = Backup.Extras(
        favorites = repo.favorites(),
        lockedGroups = repo.lockedGroups,
        progress = sources.associate { it.id to repo.progress(it.id) }.filterValues { it.isNotEmpty() },
        lastChannels = sources.mapNotNull { s -> repo.lastChannel(s.id)?.let { s.id to it } }.toMap(),
    )

    /** Tam yedek (JSON metni). */
    fun backupJson(): String = Json.write(Backup.build(sources, backupSettings(), backupExtras()))

    /** Yedek metnini geri yükler; sonucu anlatan bir cümle döndürür. Geçersizse IllegalArgumentException. */
    fun restoreBackupText(text: String, replace: Boolean): String {
        val data = Backup.parse(runCatching { Json.parse(text) }.getOrElse { throw IllegalArgumentException("Dosya okunamadı: geçerli bir yedek değil") })
        val plan = Backup.plan(data, sources, replace, hasPin) { Repository.newSourceId() }
        if (plan.added == 0 && !replace && plan.extras.isEmpty) return "Yedekteki hesapların hepsi zaten ekli."
        applyRestore(plan, replace)
        val x = plan.extras
        return buildString {
            append(if (replace) "Yedek yüklendi: ${plan.added} hesap" else "Yedek eklendi: ${plan.added} yeni hesap")
            val parts = listOfNotNull(
                x.favorites.size.takeIf { it > 0 }?.let { "$it favori" },
                x.progress.values.sumOf { it.size }.takeIf { it > 0 }?.let { "$it kaldığın yer" },
                x.lockedGroups.size.takeIf { it > 0 }?.let { "$it kilitli kategori" },
            )
            if (parts.isNotEmpty()) append(" · " + parts.joinToString(", "))
        }
    }

    /** Planı uygular: hesaplar, ayarlar, favoriler, kilitler, kaldığın yerler, son kanallar. */
    fun applyRestore(plan: Backup.Plan, replace: Boolean) {
        val list = if (replace) plan.newSources else sources + plan.newSources
        saveSources(list)
        val st = plan.settings
        (st["posterMode"] as? String)?.takeIf { it in setOf("auto", "always", "never") }?.let { setPosterModeOption(it) }
        (st["posterTitle"] as? String)?.takeIf { it in setOf("overlay", "below") }?.let { setPosterTitleOption(it) }
        (st["playerMode"] as? String)?.takeIf { it in setOf(PLAYER_INTERNAL, PLAYER_EXTERNAL) }?.let { setPlayer(it) }
        (st["streamFormat"] as? String)?.takeIf { it in setOf("auto", "hls", "ts") }?.let { setFormat(it) }
        (st["updateHours"] as? Number)?.toInt()?.takeIf { it in setOf(0, 6, 12, 24) }?.let { setUpdateInterval(it) }
        (st["theme"] as? String)?.let { setTheme(it) }

        val x = plan.extras
        val favs = if (replace) x.favorites else repo.favorites() + x.favorites
        repo.setFavorites(favs); favorites = favs
        val locks = if (replace) x.lockedGroups else repo.lockedGroups + x.lockedGroups
        repo.lockedGroups = locks; lockedGroups = locks
        x.progress.forEach { (id, m) -> repo.mergeProgress(id, m) }
        x.lastChannels.forEach { (id, key) -> if (replace || repo.lastChannel(id) == null) repo.setLastChannel(id, key) }
        activeId?.let { progress = repo.progress(it) }

        if (replace || activeSource == null) {
            activeId = null
            list.firstOrNull()?.let { openAccount(it.id, pinVerified = true) }
        }
        autoBackup()
    }

    /** Hesaplar değişince ya da günde bir kez cihazın içine otomatik yedek. */
    fun autoBackup() {
        if (sources.isEmpty()) return
        val key = sources.joinToString("|") { "${it.id}:${it.url}:${it.name}:${it.locked}:${it.color}:${it.epg}" } +
            "#" + favorites.size + "#" + lockedGroups.size
        runCatching { repo.saveAutoBackup(backupJson(), key) }
    }

    // ---- Telefon kumandasının TV tarafı (ana iş parçacığında çağrılır) ----
    private inner class RemoteBridge : RemoteOps {
        override fun accounts() = sources
        override fun activeId() = this@AppViewModel.activeId
        override fun activeName() = activeSource?.name
        override fun hasPin() = this@AppViewModel.hasPin
        override fun checkPin(pin: String?) = repo.checkPin(pin)
        override fun isChecking() = checking

        override fun select(id: String) {
            val name = sources.firstOrNull { it.id == id }?.name
            openAccount(id, pinVerified = true)
            message = "Telefondan: $name açıldı"
        }

        override fun upsert(src: Source, openNow: Boolean) {
            upsertAccount(src, openNow)
            message = "Telefondan: ${src.name} kaydedildi"
        }

        override fun delete(id: String) {
            deleteAccount(id, pinVerified = true)
        }

        override fun startCheckAll() = checkAll(force = false)

        override fun search(q: String, limit: Int): Pair<List<Map<String, Any?>>, Boolean> {
            val needle = fold(q)
            val hits = ArrayList<Channel>()
            var more = false
            for (ch in playlist?.channels.orEmpty()) {
                if (isChannelBlocked(ch)) continue
                if (fold(ch.name).contains(needle)) {
                    if (hits.size >= limit) { more = true; break }
                    hits += ch
                }
            }
            val t = System.currentTimeMillis()
            return hits.map { ch ->
                mapOf(
                    "key" to ch.key, "name" to ch.name, "group" to ch.group,
                    "logo" to logoOf(ch), "now" to epg.current(ch, t)?.title,
                )
            } to more
        }

        override fun channelLocked(key: String): Boolean? = channelByKey[key]?.let { isChannelBlocked(it) }

        override fun play(key: String) {
            val ch = channelByKey[key] ?: return
            pinRequest = null
            if (selectedGroup != FAVORITES || ch.key !in favorites) selectedGroup = groupIdOf(ch)
            startPlayback(ch, progressOf(ch)?.posMs ?: 0L)
        }

        override fun settingsForBackup(): Map<String, Any?> = backupSettings()

        override fun backupExtras(): Backup.Extras = this@AppViewModel.backupExtras()

        override fun restore(plan: Backup.Plan, replace: Boolean) {
            applyRestore(plan, replace)
            message = "Telefondan: yedek yüklendi (${plan.added} yeni hesap)"
        }

        override fun newId() = Repository.newSourceId()
        override fun nextColor() = repo.nextColor()

        override fun nowPlaying(): Map<String, Any?> {
            val ch = currentChannel
            if (screen == Screen.Player && ch != null) {
                val pb = playback
                val t = System.currentTimeMillis()
                val cur = epg.current(ch, t)
                return linkedMapOf(
                    "screen" to "player", "title" to ch.name,
                    "subtitle" to if (pb.vod) ch.group else (cur?.title ?: ch.group),
                    "logo" to logoOf(ch), "live" to !pb.vod,
                    "pos" to pb.posMs, "dur" to pb.durMs, "playing" to pb.playing,
                    "progStart" to cur?.startMs, "progEnd" to cur?.endMs,
                    "volume" to volumeState(),
                )
            }
            val (title, sub) = when (screen) {
                Screen.Home -> (activeSource?.name ?: "TvKutu") to "Ana ekran · ${groupLabel(selectedGroup)}"
                Screen.Accounts -> "Hesap Seç" to "TV'de hesap seçme ekranı"
                Screen.Setup -> "Ayarlar" to "TV'de ayarlar açık"
                Screen.QrSetup -> "Telefondan yönet" to "TV'de karekod ekranı"
                Screen.Player -> "TvKutu" to ""
            }
            return linkedMapOf("screen" to screen.name.lowercase(), "title" to title, "subtitle" to sub, "volume" to volumeState())
        }

        override fun remoteKey(name: String) {
            val inPlayer = screen == Screen.Player
            when (name) {
                "home" -> goHome()
                "back" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_BACK)
                "play" -> if (inPlayer) _playerCommands.tryEmit(PlayerCommand.Toggle) else _remoteKeys.tryEmit(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "chup" -> if (inPlayer) _playerCommands.tryEmit(PlayerCommand.Prev) else _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_UP)
                "chdown" -> if (inPlayer) _playerCommands.tryEmit(PlayerCommand.Next) else _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_DOWN)
                "up" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_UP)
                "down" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_DOWN)
                "left" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_LEFT)
                "right" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_RIGHT)
                "ok" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_DPAD_CENTER)
                "menu" -> _remoteKeys.tryEmit(KeyEvent.KEYCODE_MENU)
            }
        }

        override fun seek(byMs: Long?, toMs: Long?) {
            if (screen != Screen.Player) return
            if (toMs != null) _playerCommands.tryEmit(PlayerCommand.SeekTo(toMs))
            else if (byMs != null) _playerCommands.tryEmit(PlayerCommand.SeekBy(byMs))
        }

        override fun volume(step: Int): Map<String, Any?> {
            audio?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                when (step) {
                    1 -> AudioManager.ADJUST_RAISE
                    -1 -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_TOGGLE_MUTE
                },
                AudioManager.FLAG_SHOW_UI,
            )
            return volumeState()
        }
    }

    companion object {
        const val ALL = "Tüm kanallar"
        const val FAVORITES = "★ Favoriler"
        const val CONTINUE = "▶ Devam et"
        const val SYNC_CODE_MS = 3 * 60_000L
        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
        private val VOD_URL = Regex("""(?i)(/movies?/|/series/|/vod/|\.(mp4|mkv|avi|m4v|mov|webm)(\?|$))""")
        private val TR = Locale("tr")

        /** Arama için: Türkçe büyük/küçük harf ve noktalı/noktasız i farkını yok say. */
        fun fold(s: String): String = s.lowercase(TR).replace('ı', 'i').replace("i̇", "i")
    }
}
