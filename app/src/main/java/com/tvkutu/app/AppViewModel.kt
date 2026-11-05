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
import com.tvkutu.app.data.Channel
import com.tvkutu.app.data.EpgIndex
import com.tvkutu.app.data.Playlist
import com.tvkutu.app.data.RemoteApi
import com.tvkutu.app.data.RemoteOps
import com.tvkutu.app.data.RemoteServer
import com.tvkutu.app.data.Repository
import com.tvkutu.app.data.Source
import com.tvkutu.app.data.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    init {
        startRemote()
        viewModelScope.launch {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
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
    }

    override fun onCleared() {
        server.stop()
    }

    /** Sunucuyu başlatır (çalışıyorsa adresi tazeler). */
    fun startRemote() {
        runCatching {
            val port = server.start(repo.remotePort)
            if (port != repo.remotePort && port > 0) repo.remotePort = port
            val ip = RemoteServer.localIpv4()
            remoteUrl = if (ip == null) null else "http://$ip:$port/s/${server.code}"
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
                .mapNotNull { channelByKey[it.key] }.filterNot { isChannelBlocked(it) }
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
    fun setPosterTitleOption(v: String) { repo.posterTitle = v; posterTitle = v }

    /** Kanal logosu: listedeki tvg-logo, yoksa EPG'deki ikon. */
    fun logoOf(ch: Channel): String? = ch.logo ?: epg.logo(ch)

    fun lastChannelKey(): String? = currentChannel?.key ?: activeId?.let { repo.lastChannel(it) }

    private fun applyPlaylist(pl: Playlist?) {
        playlist = pl
        if (pl == null) {
            groupIndex = emptyMap(); groupLabels = emptyMap(); baseGroups = emptyList(); channelByKey = emptyMap()
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
        val saved = progress[channel.key]
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
        progress = repo.removeProgress(id, channel.key)
        if (resumeRequest?.channel?.key == channel.key) resumeRequest = null
        if (selectedGroup == CONTINUE && progress.isEmpty()) selectedGroup = ALL
    }

    /** Oynatıcı bir kez çağırır: başlangıç konumu (ms). */
    fun takeStartPosition(): Long = pendingStartMs.also { pendingStartMs = 0L }

    fun progressOf(ch: Channel): WatchProgress? = progress[ch.key]

    /** Oynatıcıdan: film/dizi konumunu kaydet (canlı yayınlar için çağrılmaz). */
    fun updateProgress(ch: Channel, posMs: Long, durMs: Long) {
        val id = activeId ?: return
        if (ch.sourceId != id) return
        if (!WatchProgress.shouldKeep(posMs, durMs) && !WatchProgress.isFinished(posMs, durMs)) return
        progress = repo.saveProgress(id, ch.key, posMs, durMs)
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
        pendingStartMs = progress[channel.key]?.posMs ?: 0L
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
            startPlayback(ch, progress[ch.key]?.posMs ?: 0L)
        }

        override fun settingsForBackup(): Map<String, Any?> = mapOf(
            "posterMode" to posterMode, "posterTitle" to posterTitle, "playerMode" to playerMode,
            "streamFormat" to streamFormat, "updateHours" to updateHours,
        )

        override fun restore(accounts: List<Source>, replace: Boolean, settings: Map<String, Any?>) {
            val list = if (replace) accounts else sources + accounts
            saveSources(list)
            (settings["posterMode"] as? String)?.takeIf { it in setOf("auto", "always", "never") }?.let { setPosterModeOption(it) }
            (settings["posterTitle"] as? String)?.takeIf { it in setOf("overlay", "below") }?.let { setPosterTitleOption(it) }
            (settings["playerMode"] as? String)?.takeIf { it in setOf(PLAYER_INTERNAL, PLAYER_EXTERNAL) }?.let { setPlayer(it) }
            (settings["streamFormat"] as? String)?.takeIf { it in setOf("auto", "hls", "ts") }?.let { setFormat(it) }
            (settings["updateHours"] as? Number)?.toInt()?.takeIf { it in setOf(0, 6, 12, 24) }?.let { setUpdateInterval(it) }
            if (replace || activeSource == null) {
                activeId = null
                list.firstOrNull()?.let { openAccount(it.id, pinVerified = true) }
            }
            message = "Telefondan: ${accounts.size} hesap yüklendi"
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
        const val PLAYER_INTERNAL = "internal"
        const val PLAYER_EXTERNAL = "external"
        private val VOD_URL = Regex("""(?i)(/movies?/|/series/|/vod/|\.(mp4|mkv|avi|m4v|mov|webm)(\?|$))""")
        private val TR = Locale("tr")

        /** Arama için: Türkçe büyük/küçük harf ve noktalı/noktasız i farkını yok say. */
        fun fold(s: String): String = s.lowercase(TR).replace('ı', 'i').replace("i̇", "i")
    }
}
