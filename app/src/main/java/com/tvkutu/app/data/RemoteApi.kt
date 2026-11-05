package com.tvkutu.app.data

/** TV tarafının telefon kumandasına sunduğu temel işlemler (uygulama durumunu değiştirenler ana iş parçacığında çalışmalı). */
interface RemoteOps {
    fun accounts(): List<Source>
    fun activeId(): String?
    fun activeName(): String?
    fun hasPin(): Boolean
    fun checkPin(pin: String?): Boolean
    fun isChecking(): Boolean
    fun select(id: String)
    /** Hesabı ekler ya da (aynı kimlikle) günceller. */
    fun upsert(src: Source, openNow: Boolean)
    fun delete(id: String)
    fun startCheckAll()
    /** Arama sonuçları (kilitli kategoriler hariç) ve daha fazla sonuç olup olmadığı. */
    fun search(q: String, limit: Int): Pair<List<Map<String, Any?>>, Boolean>
    /** Kanal bulunamazsa null, kilitli bir kategorideyse true. */
    fun channelLocked(key: String): Boolean?
    fun play(key: String)
    fun settingsForBackup(): Map<String, Any?>
    fun restore(accounts: List<Source>, replace: Boolean, settings: Map<String, Any?>)
    fun newId(): String
    fun nextColor(): String

    // ---- Kumanda ----
    /** Şu an oynayan (ya da hangi ekranda olunduğu). */
    fun nowPlaying(): Map<String, Any?>
    /** Kumanda tuşu: [RemoteApi.KEYS] içinden. */
    fun remoteKey(name: String)
    /** Film/dizide sarma: göreli ([byMs]) ya da mutlak ([toMs]). */
    fun seek(byMs: Long?, toMs: Long?)
    /** Ses: +1 aç, -1 kıs, 0 sessiz aç/kapat. Güncel durumu döndürür. */
    fun volume(step: Int): Map<String, Any?>
}

/**
 * Telefon kumandasının JSON API'si: doğrulama ve PIN kuralları burada.
 *
 * PIN kuralları (PIN belirlenmişse): kilitli hesabı açmak, kilitli hesabı düzenlemek/silmek,
 * kilit ayarını değiştirmek, kilitli kategorideki kanalı açmak, yedek almak ve yüklemek PIN ister.
 * PIN yalnızca TV'den belirlenir.
 */
class RemoteApi(private val ops: RemoteOps) {

    fun handle(action: String, body: Map<String, Any?>): Map<String, Any?> = when (action) {
        "state" -> state()
        "check" -> { ops.startCheckAll(); state() }
        "select" -> select(body)
        "save" -> save(body)
        "delete" -> delete(body)
        "search" -> search(body)
        "play" -> play(body)
        "backup" -> backup(body)
        "restore" -> restore(body)
        "now" -> ops.nowPlaying()
        "key" -> key(body)
        "seek" -> seek(body)
        "vol" -> ops.volume(((body["d"] as? Number)?.toInt() ?: 0).coerceIn(-1, 1))
        else -> mapOf("error" to "Bilinmeyen işlem")
    }

    private fun needPin(msg: String = "Bu işlem için PIN gerekli") = mapOf("needPin" to true, "error" to msg)
    private fun wrongPin() = mapOf("needPin" to true, "error" to "PIN yanlış, tekrar dene")
    private fun ok(vararg extra: Pair<String, Any?>) = linkedMapOf<String, Any?>("ok" to true).apply { putAll(extra) }

    /** PIN gerekiyorsa ve verilmemiş/yanlışsa uygun yanıtı, aksi halde null döndürür. */
    private fun pinGate(required: Boolean, body: Map<String, Any?>): Map<String, Any?>? {
        if (!required || !ops.hasPin()) return null
        val pin = body["pin"] as? String
        if (pin.isNullOrEmpty()) return needPin()
        return if (ops.checkPin(pin)) null else wrongPin()
    }

    private fun state(): Map<String, Any?> {
        val active = ops.activeId()
        val checking = ops.isChecking()
        return linkedMapOf(
            "active" to active,
            "hasPin" to ops.hasPin(),
            "checking" to checking,
            "colors" to Source.COLORS,
            "accounts" to ops.accounts().map { s ->
                linkedMapOf(
                    "id" to s.id, "name" to s.name, "url" to s.url, "epg" to s.epg,
                    "color" to s.color, "locked" to s.locked, "count" to s.count,
                    "error" to s.error, "active" to (s.id == active), "checking" to (checking && s.checkedMs == 0L),
                )
            },
        )
    }

    private fun select(body: Map<String, Any?>): Map<String, Any?> {
        val id = body["id"] as? String ?: return mapOf("error" to "Hesap seçilmedi")
        val src = ops.accounts().firstOrNull { it.id == id } ?: return mapOf("error" to "Hesap bulunamadı")
        pinGate(src.locked, body)?.let { return it }
        ops.select(id)
        return ok()
    }

    private fun save(body: Map<String, Any?>): Map<String, Any?> {
        val id = (body["id"] as? String)?.takeIf { it.isNotBlank() }
        val existing = id?.let { i -> ops.accounts().firstOrNull { it.id == i } }
        if (id != null && existing == null) return mapOf("error" to "Hesap bulunamadı")
        val url = (body["url"] as? String)?.trim().orEmpty()
        if (!Backup.isHttpUrl(url)) return mapOf("error" to "Playlist linki http:// veya https:// ile başlamalı.")
        val epg = (body["epg"] as? String)?.trim().orEmpty()
        val badEpg = epg.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { !Backup.isHttpUrl(it) }
        if (badEpg != null) return mapOf("error" to "EPG linki http:// veya https:// ile başlamalı.")
        val locked = body["locked"] == true && ops.hasPin()
        if (existing == null && ops.accounts().size >= Backup.MAX_ACCOUNTS) return mapOf("error" to "En fazla ${Backup.MAX_ACCOUNTS} hesap eklenebilir.")
        if (existing == null && ops.accounts().any { it.url == url }) return mapOf("error" to "Bu link zaten ekli.")
        pinGate(locked || existing?.locked == true, body)?.let { return it }

        val color = (body["color"] as? String)?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: existing?.color ?: ops.nextColor()
        val name = (body["name"] as? String)?.trim()?.take(40).orEmpty()
            .ifEmpty { existing?.name ?: "Hesap ${ops.accounts().size + 1}" }
        val src = if (existing != null) {
            // Link değiştiyse eski kanal sayısı/durum geçersiz.
            val sameUrl = existing.url == url
            existing.copy(
                name = name, url = url, epg = epg, color = color, locked = locked,
                count = if (sameUrl) existing.count else 0, error = if (sameUrl) existing.error else "",
            )
        } else {
            Source(id = ops.newId(), name = name, url = url, color = color, epg = epg, locked = locked)
        }
        ops.upsert(src, body["openNow"] == true)
        return ok("id" to src.id)
    }

    private fun delete(body: Map<String, Any?>): Map<String, Any?> {
        val id = body["id"] as? String ?: return mapOf("error" to "Hesap seçilmedi")
        val src = ops.accounts().firstOrNull { it.id == id } ?: return mapOf("error" to "Hesap bulunamadı")
        pinGate(src.locked, body)?.let { return it }
        ops.delete(id)
        return ok()
    }

    private fun search(body: Map<String, Any?>): Map<String, Any?> {
        val q = (body["q"] as? String)?.trim().orEmpty()
        if (q.length < 2) return mapOf("results" to emptyList<Any>(), "account" to ops.activeName())
        val (list, more) = ops.search(q, SEARCH_LIMIT)
        return linkedMapOf("results" to list, "more" to more, "account" to ops.activeName())
    }

    private fun play(body: Map<String, Any?>): Map<String, Any?> {
        val key = body["key"] as? String ?: return mapOf("error" to "Kanal seçilmedi")
        val locked = ops.channelLocked(key) ?: return mapOf("error" to "Kanal bulunamadı (liste değişmiş olabilir)")
        pinGate(locked, body)?.let { return it }
        ops.play(key)
        return ok()
    }

    private fun backup(body: Map<String, Any?>): Map<String, Any?> {
        pinGate(true, body)?.let { return it }
        return ok("backup" to Backup.build(ops.accounts(), ops.settingsForBackup()))
    }

    private fun restore(body: Map<String, Any?>): Map<String, Any?> {
        pinGate(true, body)?.let { return it }
        val (parsed, settings) = try {
            Backup.parse(body["backup"])
        } catch (e: IllegalArgumentException) {
            return mapOf("error" to (e.message ?: "Geçersiz yedek"))
        }
        val replace = body["mode"] == "replace"
        val existing = if (replace) emptyList() else ops.accounts()
        val usedColors = existing.map { it.color }.toMutableSet()
        val fresh = parsed.filter { p -> existing.none { it.url == p.url } }
            .take((Backup.MAX_ACCOUNTS - existing.size).coerceAtLeast(0))
            .map { p ->
                val color = p.color.ifEmpty { Source.COLORS.firstOrNull { it !in usedColors } ?: Source.COLORS[0] }
                usedColors += color
                Source(id = ops.newId(), name = p.name.ifEmpty { "Hesap" }, url = p.url, color = color, epg = p.epg,
                    locked = p.locked && ops.hasPin())
            }
        if (fresh.isEmpty() && !replace) return mapOf("error" to "Yedekteki hesapların hepsi zaten ekli.")
        ops.restore(fresh, replace, settings)
        return ok("added" to fresh.size)
    }

    private fun key(body: Map<String, Any?>): Map<String, Any?> {
        val k = body["k"] as? String ?: return mapOf("error" to "Tuş yok")
        if (k !in KEYS) return mapOf("error" to "Bilinmeyen tuş")
        ops.remoteKey(k)
        return ok()
    }

    private fun seek(body: Map<String, Any?>): Map<String, Any?> {
        val by = (body["by"] as? Number)?.toLong()?.coerceIn(-6 * 3_600_000L, 6 * 3_600_000L)
        val to = (body["to"] as? Number)?.toLong()?.coerceAtLeast(0)
        if (by == null && to == null) return mapOf("error" to "Konum yok")
        ops.seek(by, to)
        return ok()
    }

    companion object {
        const val SEARCH_LIMIT = 60
        val KEYS = setOf("up", "down", "left", "right", "ok", "back", "home", "menu", "play", "chup", "chdown")
    }
}
