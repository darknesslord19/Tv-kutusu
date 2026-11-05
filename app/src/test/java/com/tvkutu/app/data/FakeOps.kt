package com.tvkutu.app.data

/** Testler için bellekte çalışan sahte TV. */
class FakeOps(pin: String? = null) : RemoteOps {
    var list = mutableListOf(
        Source("a1", "DARKNES TV", "http://ornek.net/1.m3u", color = "#3B2470", count = 9201),
        Source("a2", "DARKNES TV-1", "http://ornek.net/2.m3u", color = "#5A1A1F", count = 4380, error = "Sunucu hatası: HTTP 403"),
    )
    var active: String? = "a1"
    var pinCode: String? = pin
    var played: String? = null
    var checkCalls = 0
    var settingsApplied: Map<String, Any?>? = null
    private var n = 0

    val channels = listOf(
        mapOf("key" to "a1/id:trt1", "name" to "TRT 1 HD", "group" to "Ulusal", "logo" to null, "now" to "Ana Haber", "locked" to false),
        mapOf("key" to "a1/id:trtspor", "name" to "TRT Spor", "group" to "Spor", "logo" to null, "now" to null, "locked" to false),
        mapOf("key" to "a1/n:gece", "name" to "Gece Kanalı", "group" to "Yetişkin", "logo" to null, "now" to null, "locked" to true),
    )

    override fun accounts() = list.toList()
    override fun activeId() = active
    override fun activeName() = list.firstOrNull { it.id == active }?.name
    override fun hasPin() = pinCode != null
    override fun checkPin(pin: String?) = pinCode == null || pin == pinCode
    override fun isChecking() = false
    override fun select(id: String) { active = id }
    override fun upsert(src: Source, openNow: Boolean) {
        val i = list.indexOfFirst { it.id == src.id }
        if (i >= 0) list[i] = src else list += src
        if (openNow) active = src.id
    }
    override fun delete(id: String) {
        list.removeAll { it.id == id }
        if (active == id) active = list.firstOrNull()?.id
    }
    override fun startCheckAll() { checkCalls++ }
    override fun search(q: String, limit: Int): Pair<List<Map<String, Any?>>, Boolean> {
        val hits = channels.filter { it["locked"] == false && (it["name"] as String).contains(q, ignoreCase = true) }
        return hits.take(limit).map { it - "locked" } to (hits.size > limit)
    }
    override fun channelLocked(key: String) = channels.firstOrNull { it["key"] == key }?.get("locked") as Boolean?
    override fun play(key: String) { played = key }
    override fun settingsForBackup() = mapOf("posterMode" to "auto")
    override fun restore(accounts: List<Source>, replace: Boolean, settings: Map<String, Any?>) {
        if (replace) list.clear()
        list.addAll(accounts)
        if (replace) active = list.firstOrNull()?.id
        settingsApplied = settings
    }
    override fun newId() = "n${++n}"
    override fun nextColor() = "#0F4A4A"
}
