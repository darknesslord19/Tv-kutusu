package com.tvkutu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApiTest {

    @Test
    fun jsonRoundTrip() {
        val v = mapOf("a" to listOf(1L, 2.5, true, null, "x\"<y>\nç"), "b" to mapOf("c" to "d"))
        val text = Json.write(v)
        assertFalse(text.contains("<"))
        assertEquals(v, Json.parse(text))
        assertEquals(mapOf("k" to "é"), Json.parse("""{ "k" : "é" }"""))
    }

    @Test
    fun stateListsAccountsWithActiveAndStatus() {
        val api = RemoteApi(FakeOps())
        @Suppress("UNCHECKED_CAST")
        val acc = api.handle("state", emptyMap())["accounts"] as List<Map<String, Any?>>
        assertEquals(listOf("a1", "a2"), acc.map { it["id"] })
        assertEquals(true, acc[0]["active"])
        assertEquals("Sunucu hatası: HTTP 403", acc[1]["error"])
    }

    @Test
    fun selectAndAddNewAccountThatOpensOnTv() {
        val ops = FakeOps()
        val api = RemoteApi(ops)
        assertEquals(true, api.handle("select", mapOf("id" to "a2"))["ok"])
        assertEquals("a2", ops.active)

        val r = api.handle("save", mapOf("name" to "Spor", "url" to " https://s.com/s.m3u ", "color" to "#1D4A2E", "openNow" to true))
        assertEquals(true, r["ok"])
        val added = ops.list.last()
        assertEquals("Spor", added.name)
        assertEquals("https://s.com/s.m3u", added.url)
        assertEquals("#1D4A2E", added.color)
        assertEquals(added.id, ops.active)

        // Aynı link ikinci kez eklenemez; geçersiz link reddedilir.
        assertTrue((api.handle("save", mapOf("url" to "https://s.com/s.m3u"))["error"] as String).contains("zaten"))
        assertTrue(api.handle("save", mapOf("url" to "ftp://x"))["error"] != null)
    }

    @Test
    fun editKeepsIdAndResetsStatusWhenLinkChanges() {
        val ops = FakeOps()
        val api = RemoteApi(ops)
        api.handle("save", mapOf("id" to "a2", "name" to "Yeni Ad", "url" to "http://yeni.net/l.m3u", "openNow" to false))
        val s = ops.list.first { it.id == "a2" }
        assertEquals("Yeni Ad", s.name)
        assertEquals(0, s.count)
        assertEquals("", s.error)
        assertEquals("a1", ops.active)
    }

    @Test
    fun lockedAccountNeedsPin() {
        val ops = FakeOps(pin = "1234")
        ops.list[1] = ops.list[1].copy(locked = true)
        val api = RemoteApi(ops)

        assertEquals(true, api.handle("select", mapOf("id" to "a2"))["needPin"])
        assertEquals("a1", ops.active)
        assertEquals(true, api.handle("select", mapOf("id" to "a2", "pin" to "0000"))["needPin"])
        assertEquals(true, api.handle("select", mapOf("id" to "a2", "pin" to "1234"))["ok"])
        assertEquals("a2", ops.active)

        assertEquals(true, api.handle("delete", mapOf("id" to "a2"))["needPin"])
        assertEquals(true, api.handle("delete", mapOf("id" to "a2", "pin" to "1234"))["ok"])
        assertEquals(1, ops.list.size)
    }

    @Test
    fun lockingNeedsPinAndIsIgnoredWithoutPin() {
        val noPin = FakeOps()
        RemoteApi(noPin).handle("save", mapOf("url" to "https://k.com/k.m3u", "locked" to true))
        assertFalse(noPin.list.last().locked)

        val withPin = FakeOps(pin = "1234")
        val api = RemoteApi(withPin)
        assertEquals(true, api.handle("save", mapOf("url" to "https://k.com/k.m3u", "locked" to true))["needPin"])
        assertEquals(2, withPin.list.size)
        api.handle("save", mapOf("url" to "https://k.com/k.m3u", "locked" to true, "pin" to "1234"))
        assertTrue(withPin.list.last().locked)
    }

    @Test
    fun searchHidesLockedAndPlayChecksPin() {
        val ops = FakeOps(pin = "1234")
        val api = RemoteApi(ops)
        @Suppress("UNCHECKED_CAST")
        val res = api.handle("search", mapOf("q" to "trt"))["results"] as List<Map<String, Any?>>
        assertEquals(listOf("TRT 1 HD", "TRT Spor"), res.map { it["name"] })
        assertEquals(0, (api.handle("search", mapOf("q" to "kan"))["results"] as List<*>).size)

        assertEquals(true, api.handle("play", mapOf("key" to "a1/id:trt1"))["ok"])
        assertEquals("a1/id:trt1", ops.played)
        assertEquals(true, api.handle("play", mapOf("key" to "a1/n:gece"))["needPin"])
        assertEquals(true, api.handle("play", mapOf("key" to "a1/n:gece", "pin" to "1234"))["ok"])
        assertTrue(api.handle("play", mapOf("key" to "yok"))["error"] != null)
    }

    @Test
    fun backupAndRestoreMergeOrReplace() {
        val src = FakeOps()
        @Suppress("UNCHECKED_CAST")
        val backup = RemoteApi(src).handle("backup", emptyMap())["backup"] as Map<String, Any?>
        // JSON üzerinden geçir (telefonda indirilen dosya gibi).
        val restored = Json.parse(Json.write(backup))

        val dst = FakeOps()
        dst.list = mutableListOf(Source("x", "Eski", "http://ornek.net/1.m3u"))
        val api = RemoteApi(dst)
        val merged = api.handle("restore", mapOf("backup" to restored, "mode" to "merge"))
        assertEquals(1L.toInt(), (merged["added"] as Int))
        assertEquals(listOf("Eski", "DARKNES TV-1"), dst.list.map { it.name })
        assertEquals(mapOf("posterMode" to "auto"), dst.settingsApplied)

        val replaced = api.handle("restore", mapOf("backup" to restored, "mode" to "replace"))
        assertEquals(2, replaced["added"])
        assertEquals(listOf("DARKNES TV", "DARKNES TV-1"), dst.list.map { it.name })

        assertTrue(api.handle("restore", mapOf("backup" to mapOf("app" to "Baska")))["error"] != null)
        // Hesaplar zaten ekli ama favori var: hata değil, veriler birleştirilir.
        assertEquals(0, api.handle("restore", mapOf("backup" to restored, "mode" to "merge"))["added"])
    }

    @Test
    fun backupNeedsPinWhenSet() {
        val api = RemoteApi(FakeOps(pin = "4321"))
        assertEquals(true, api.handle("backup", emptyMap())["needPin"])
        assertNull(api.handle("backup", mapOf("pin" to "4321"))["error"])
    }

    @Test
    fun remoteKeysSeekAndVolume() {
        val ops = FakeOps()
        val api = RemoteApi(ops)
        assertEquals(true, api.handle("key", mapOf("k" to "ok"))["ok"])
        assertEquals(true, api.handle("key", mapOf("k" to "chup"))["ok"])
        assertTrue(api.handle("key", mapOf("k" to "power"))["error"] != null)
        assertEquals(listOf("ok", "chup"), ops.keys)

        api.handle("seek", mapOf("by" to -10_000L))
        assertEquals(-10_000L, ops.seekBy)
        api.handle("seek", mapOf("to" to 4_350_000.0))
        assertEquals(4_350_000L, ops.seekTo)
        assertTrue(api.handle("seek", emptyMap())["error"] != null)

        assertEquals(8, api.handle("vol", mapOf("d" to 1L))["vol"])
        assertEquals(true, api.handle("vol", mapOf("d" to 0L))["muted"])
        assertEquals("TRT 1 HD", api.handle("now", emptyMap())["title"])
    }
}
