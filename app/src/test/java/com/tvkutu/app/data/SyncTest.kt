package com.tvkutu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private val u1 = "http://x/1.m3u"
    private val u2 = "http://x/2.m3u"
    private val s1 = Sync.sid(u1)
    private val s2 = Sync.sid(u2)

    @Test
    fun sidIsStableAndSlashFree() {
        assertEquals(s1, Sync.sid(" $u1 "))
        assertFalse(s1.contains('/'))
        assertTrue(s1 != s2)
    }

    @Test
    fun mergeLastWriterWins() {
        val a = Sync.Snap(
            accounts = mapOf(s1 to Sync.Acc(u1, "Eski ad", "#111111", "", false, ts = 100)),
            favorites = mapOf("$s1/id:trt1" to 50, "$s1/id:spor" to 60),
            progress = mapOf("p:$s1/Korku|Film" to WatchProgress(600_000, 5_400_000, 100)),
            settings = mapOf("theme" to "gece"), settingsMs = 10,
        )
        val b = Sync.Snap(
            accounts = mapOf(
                s1 to Sync.Acc(u1, "Yeni ad", "#222222", "", false, ts = 200),
                s2 to Sync.Acc(u2, "Spor", "#333333", "", false, ts = 150),
            ),
            favorites = mapOf("$s1/id:trt1" to -70), // b'de daha sonra favoriden çıkarıldı
            progress = mapOf("p:$s1/Korku|Film" to WatchProgress(1_800_000, 5_400_000, 300)),
            settings = mapOf("theme" to "okyanus"), settingsMs = 20,
        )
        val m = Sync.merge(a, b)
        assertEquals("Yeni ad", m.accounts[s1]!!.name)
        assertEquals("Spor", m.accounts[s2]!!.name)
        assertEquals(-70L, m.favorites["$s1/id:trt1"])
        assertEquals(60L, m.favorites["$s1/id:spor"])
        assertEquals(1_800_000L, m.progress["p:$s1/Korku|Film"]!!.posMs)
        assertEquals("okyanus", m.settings["theme"])
        assertEquals(m, Sync.merge(b, a)) // sıra fark etmez
    }

    @Test
    fun deletionWinsOnlyIfNewer() {
        val local = listOf(
            Source("a", "Bir", u1, updatedMs = 100),
            Source("b", "İki", u2, updatedMs = 500),
        )
        val snap = Sync.Snap(accounts = mapOf(
            s1 to Sync.Acc(u1, "Bir", "", "", false, ts = 300, deleted = true), // sonra silinmiş → sil
            s2 to Sync.Acc(u2, "İki", "", "", false, ts = 400, deleted = true), // yerelde sonra düzenlenmiş → kalsın
        ))
        val plan = Sync.planAccounts(local, snap, replace = false, now = 1000) { "new" }
        assertEquals(listOf("b"), plan.sources.map { it.id })
        assertEquals(setOf(u1), plan.deletedUrls.keys)
    }

    @Test
    fun planAddsUpdatesAndReplaces() {
        val u3 = "http://x/3.m3u"
        val local = listOf(Source("a", "Bir", u1, updatedMs = 100), Source("c", "Üç", u3, updatedMs = 100))
        val snap = Sync.Snap(accounts = mapOf(
            s1 to Sync.Acc(u1, "Bir (yeni)", "#123456", "", true, ts = 200),
            s2 to Sync.Acc(u2, "İki", "#654321", "", false, ts = 50),
        ))
        var n = 0
        val merge = Sync.planAccounts(local, snap, replace = false, now = 1000) { "n${++n}" }
        assertEquals(listOf("a", "c", "n1"), merge.sources.map { it.id })
        assertEquals("Bir (yeni)", merge.sources[0].name)
        assertTrue(merge.sources[0].locked)

        val replace = Sync.planAccounts(local, snap, replace = true, now = 1000) { "r" }
        assertEquals(listOf("a", "r"), replace.sources.map { it.id })
        assertEquals(setOf(u3), replace.deletedUrls.keys)
    }

    @Test
    fun keyConversionAndJsonRoundTrip() {
        val idToSid = mapOf("a" to s1)
        val shared = Sync.toShared("p:a/Korku|Film", idToSid)
        assertEquals("p:$s1/Korku|Film", shared)
        assertEquals("p:zz/Korku|Film", Sync.toLocal(shared!!, mapOf(s1 to "zz")))
        assertNull(Sync.toShared("q/id:x", idToSid))

        val snap = Sync.Snap(
            accounts = mapOf(s1 to Sync.Acc(u1, "Bir", "#111111", "http://e/x.xml", true, 5, deleted = false)),
            favorites = mapOf("$s1/id:a" to -9), locks = mapOf("grp:$s1\u0001Yetişkin" to 3),
            progress = mapOf("p:$s1/G|F" to WatchProgress(-1, 0, 77)),
            settings = mapOf("theme" to "altin"), settingsMs = 4,
        )
        assertEquals(snap, Sync.fromJson(Json.parse(Json.write(Sync.toJson(snap)))))
    }
}
