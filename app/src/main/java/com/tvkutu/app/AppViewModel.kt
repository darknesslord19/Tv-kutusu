package com.tvkutu.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvkutu.app.data.Channel
import com.tvkutu.app.data.EpgIndex
import com.tvkutu.app.data.Playlist
import com.tvkutu.app.data.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { Setup, QrSetup, Home, Player }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repository(app)

    var screen by mutableStateOf(if (repo.hasPlaylist) Screen.Home else Screen.Setup)
        private set
    /** Kurulum ekranı ayar olarak mı açıldı (geri tuşu ana ekrana döner)? */
    var setupFromSettings by mutableStateOf(false)
        private set

    var playlist by mutableStateOf<Playlist?>(null)
        private set
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
    val groups: List<String>
        get() {
            val pl = playlist ?: return emptyList()
            val list = ArrayList<String>()
            if (favorites.isNotEmpty()) list += FAVORITES
            list += ALL
            pl.channels.mapTo(LinkedHashSet()) { it.group }.forEach { list += it }
            return list
        }

    fun channelsIn(group: String): List<Channel> {
        val pl = playlist ?: return emptyList()
        return when (group) {
            ALL -> pl.channels
            FAVORITES -> pl.channels.filter { it.key in favorites }
            else -> pl.channels.filter { it.group == group }
        }
    }

    // ---- Eylemler ----
    private fun start() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            val cached = runCatching { repo.loadCachedPlaylist() }.getOrNull()
            if (cached != null && cached.channels.isNotEmpty()) {
                playlist = cached
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
                val pl = repo.downloadPlaylist()
                loadError = null
                playlist = pl
                if (selectedGroup !in groups) selectedGroup = ALL
                loading = false
                loadEpg(pl, force = true)
                if (!silent) message = "Liste güncellendi: ${pl.channels.size} kanal"
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
        if (repo.effectiveEpgUrl(pl) == null) return
        epgLoading = true
        try {
            epg = repo.loadEpg(pl, force)
        } catch (e: Exception) {
            message = "Yayın akışı yüklenemedi: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            epgLoading = false
        }
    }

    fun saveSetup(playlistUrl: String, epgUrl: String) {
        val changed = playlistUrl.trim() != repo.playlistUrl || epgUrl.trim() != repo.epgUrl
        repo.playlistUrl = playlistUrl
        repo.epgUrl = epgUrl
        screen = Screen.Home
        if (changed || playlist == null) {
            playlist = null
            epg = EpgIndex.EMPTY
            selectedGroup = ALL
            refresh(silent = false)
        }
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
    fun applyFromPhone(playlistUrl: String, epgUrl: String) {
        message = null
        saveSetup(playlistUrl, epgUrl)
        message = "Linkler telefondan alındı, liste yükleniyor…"
    }

    fun setUpdateInterval(hours: Int) {
        repo.updateHours = hours
        updateHours = hours
    }

    fun play(channel: Channel) {
        currentChannel = channel
        repo.lastChannelKey = channel.key
        screen = Screen.Player
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
    }
}
