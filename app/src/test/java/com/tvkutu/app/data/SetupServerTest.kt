package com.tvkutu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SetupServerTest {

    private fun form(vararg kv: Pair<String, String>) = kv.toList()

    @Test
    fun rowsPairNamesWithUrls() {
        val sub = SetupServer.toSubmission(form(
            "name" to "Ulusal", "url" to " https://a.com/1.m3u ",
            "name" to "", "url" to "http://b.com/2.m3u8",
            "name" to "Boş", "url" to "",
            "epg" to "https://e.com/epg.xml.gz\n\n https://e2.com/x.xml ",
        ))
        assertEquals(listOf("Ulusal" to "https://a.com/1.m3u", "" to "http://b.com/2.m3u8"), sub.entries)
        assertEquals("https://e.com/epg.xml.gz\nhttps://e2.com/x.xml", sub.epg)
        assertNull(SetupServer.validate(sub))
    }

    @Test
    fun severalUrlsPastedIntoOneFieldAndBulk() {
        val sub = SetupServer.toSubmission(form(
            "name" to "Spor", "url" to "https://s.com/a.m3u https://s.com/b.m3u",
            "bulk" to "https://x.com/x.m3u\nBelgesel | https://d.com/d.m3u\n\nhttps://s.com/a.m3u",
        ))
        assertEquals(
            listOf(
                "Spor 1" to "https://s.com/a.m3u",
                "Spor 2" to "https://s.com/b.m3u",
                "" to "https://x.com/x.m3u",
                "Belgesel" to "https://d.com/d.m3u",
            ),
            sub.entries,
        )
    }

    @Test
    fun validationErrors() {
        assertNotNull(SetupServer.validate(SetupServer.toSubmission(emptyList())))
        assertNotNull(SetupServer.validate(SetupServer.toSubmission(form("name" to "", "url" to "ftp://x"))))
        assertNotNull(SetupServer.validate(SetupServer.toSubmission(form("url" to "https://a.com", "epg" to "dosya.xml"))))
    }

    @Test
    fun formDecodingKeepsOrderAndUnicode() {
        val list = SetupServer.parseFormList("name=T%C3%BCrk&url=https%3A%2F%2Fa.com%2Fl.m3u%3Fu%3D1%26p%3D2&name=&url=")
        assertEquals(listOf("name" to "Türk", "url" to "https://a.com/l.m3u?u=1&p=2", "name" to "", "url" to ""), list)
    }
}
