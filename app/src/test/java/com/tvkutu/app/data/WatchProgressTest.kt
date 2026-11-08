package com.tvkutu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {
    private val film = 90 * 60_000L

    @Test
    fun keepsOnlyPartlyWatched() {
        assertFalse(WatchProgress.shouldKeep(20_000, film))          // 20 sn: çok kısa
        assertTrue(WatchProgress.shouldKeep(45_000, film))
        assertTrue(WatchProgress.shouldKeep(10 * 60_000L, film))     // 10 dk
        assertFalse(WatchProgress.shouldKeep(film - 60_000, film))   // son dakika: bitti
        assertTrue(WatchProgress.isFinished(film - 4 * 60_000, film)) // %95 sonrası
        assertFalse(WatchProgress.shouldKeep(10 * 60_000L, 0))       // süresi bilinmeyen (canlı)
    }

    @Test
    fun texts() {
        assertEquals("1:12:30", WatchProgress.clock(4_350_000))
        assertEquals("1:35", WatchProgress.clock(95_000))
        assertEquals("18 dk kaldı", WatchProgress.remainingText(18 * 60_000L - 5_000))
        assertEquals("1 sa 4 dk kaldı", WatchProgress.remainingText(64 * 60_000L))
        assertEquals("2 sa kaldı", WatchProgress.remainingText(120 * 60_000L))
    }

    @Test
    fun progressKeyIgnoresUrl() {
        val a = Channel("s1/n:Film|http://x/1?token=a", "Film", "http://x/1?token=a", null, "Korku", null, null, null, null, "s1")
        val b = a.copy(key = "s1/n:Film|http://x/1?token=b", url = "http://x/1?token=b")
        assertEquals(a.progressKey(), b.progressKey())
    }
}
