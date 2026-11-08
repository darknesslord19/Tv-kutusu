package com.tvkutu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val a1 = Source("a1", "DARKNES TV", "http://x/1.m3u", color = "#3B2470", locked = true)
    private val a2 = Source("a2", "SPOR", "http://x/2.m3u", color = "#0F4A4A")
    private val extras = Backup.Extras(
        favorites = setOf("a1/id:trt1", "a2/n:Spor|http://s"),
        lockedGroups = setOf("grp:a1\u0001Yetişkin"),
        progress = mapOf("a1" to mapOf("p:a1/Korku|Siccin 9" to WatchProgress(2_520_000, 5_437_000, 111))),
        lastChannels = mapOf("a2" to "a2/n:Spor|http://s"),
    )

    private fun roundTrip(): Backup.Data =
        Backup.parse(Json.parse(Json.write(Backup.build(listOf(a1, a2), mapOf("posterMode" to "always"), extras))))

    @Test
    fun buildParseKeepsEverything() {
        val d = roundTrip()
        assertEquals(listOf("a1", "a2"), d.accounts.map { it.ref })
        assertEquals("always", d.settings["posterMode"])
        assertEquals(extras.favorites, d.extras.favorites)
        assertEquals(extras.lockedGroups, d.extras.lockedGroups)
        assertEquals(2_520_000L, d.extras.progress["a1"]!!["p:a1/Korku|Siccin 9"]!!.posMs)
        assertEquals(extras.lastChannels, d.extras.lastChannels)
    }

    @Test
    fun replaceGivesNewIdsAndRemapsKeys() {
        var n = 0
        val plan = Backup.plan(roundTrip(), existing = listOf(Source("old", "Eski", "http://x/1.m3u")), replace = true, hasPin = true) { "n${++n}" }
        assertEquals(listOf("n1", "n2"), plan.newSources.map { it.id })
        assertTrue(plan.newSources[0].locked)
        assertEquals(setOf("n1/id:trt1", "n2/n:Spor|http://s"), plan.extras.favorites)
        assertEquals(setOf("grp:n1\u0001Yetişkin"), plan.extras.lockedGroups)
        assertEquals(setOf("p:n1/Korku|Siccin 9"), plan.extras.progress["n1"]!!.keys)
        assertEquals(mapOf("n2" to "n2/n:Spor|http://s"), plan.extras.lastChannels)
    }

    @Test
    fun mergeReusesExistingAccountWithSameLink() {
        var n = 0
        val existing = listOf(Source("mine", "Benim", "http://x/1.m3u"))
        val plan = Backup.plan(roundTrip(), existing, replace = false, hasPin = false) { "n${++n}" }
        assertEquals(listOf("SPOR"), plan.newSources.map { it.name })
        assertFalse(plan.newSources.any { it.locked })
        assertTrue("mine/id:trt1" in plan.extras.favorites)
        assertEquals(setOf("p:mine/Korku|Siccin 9"), plan.extras.progress["mine"]!!.keys)
    }

    @Test
    fun readsVersion1Backups() {
        val v1 = mapOf("app" to "TvKutu", "v" to 1L, "accounts" to listOf(mapOf("name" to "Eski", "url" to "http://e/1.m3u")))
        val d = Backup.parse(v1)
        assertEquals("r0", d.accounts.single().ref)
        assertTrue(d.extras.isEmpty)
    }

    @Test
    fun remapUnknownAccountIsDropped() {
        assertNull(Backup.remap("zz/id:x", mapOf("a1" to "n1")))
        assertEquals("p:n1/G|A/B", Backup.remap("p:a1/G|A/B", mapOf("a1" to "n1")))
    }
}
