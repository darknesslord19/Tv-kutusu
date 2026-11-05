package com.tvkutu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz, http://alt/epg.xml" tvg-shift="0"
        #EXTINF:-1 tvg-id="trt1.tr" tvg-name="TRT 1 HD" tvg-logo="http://logo/trt1.png" group-title="Ulusal",TRT 1 HD
        http://stream.example/trt1.m3u8
        #EXTINF:-1 tvg-id="ntv.tr" group-title="Haber, Gündem",NTV, Canlı
        #EXTVLCOPT:http-user-agent=MyAgent/1.0
        #EXTVLCOPT:http-referrer=http://ref.example/
        http://stream.example/ntv
        #EXTINF:0,Gruplu Kanal
        #EXTGRP:Belgesel
        http://stream.example/doc.ts|User-Agent=Pipe/2&Referer=http://pipe.ref/
        #EXTINF:-1 tvg-id="trt1.tr",TRT 1 Yedek
        http://backup.example/trt1.m3u8
        #EXTINF:-1,Grupsuz
        http://stream.example/x
    """.trimIndent()

    @Test
    fun parsesHeaderAndChannels() {
        val pl = M3uParser.parse(sample)
        assertEquals("http://epg.example/guide.xml.gz", pl.epgUrl)
        assertEquals(5, pl.channels.size)

        val trt = pl.channels[0]
        assertEquals("TRT 1 HD", trt.name)
        assertEquals("trt1.tr", trt.tvgId)
        assertEquals("http://logo/trt1.png", trt.logo)
        assertEquals("Ulusal", trt.group)
        assertEquals("id:trt1.tr", trt.key)
        assertNull(trt.userAgent)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotBreakName() {
        val ntv = M3uParser.parse(sample).channels[1]
        assertEquals("NTV, Canlı", ntv.name)
        assertEquals("Haber, Gündem", ntv.group)
        assertEquals("MyAgent/1.0", ntv.userAgent)
        assertEquals("http://ref.example/", ntv.referrer)
    }

    @Test
    fun extgrpAndPipeHeaders() {
        val doc = M3uParser.parse(sample).channels[2]
        assertEquals("Belgesel", doc.group)
        assertEquals("http://stream.example/doc.ts", doc.url)
        assertEquals("Pipe/2", doc.userAgent)
        assertEquals("http://pipe.ref/", doc.referrer)
    }

    @Test
    fun duplicateTvgIdGetsUniqueKeyAndOptionsDoNotLeak() {
        val chans = M3uParser.parse(sample).channels
        assertEquals(chans.size, chans.map { it.key }.toSet().size)
        assertEquals("Diğer", chans[4].group)
        assertNull(chans[3].userAgent)
    }

    @Test
    fun sourcePrefixMultipleEpgAndLogos() {
        val text = """
            #EXTM3U x-tvg-url="http://e1/a.xml,http://e2/b.xml.gz"
            #EXTINF:-1 tvg-id="a" logo="logos/a.png",A
            http://s/a
            #EXTINF:-1 tvg-logo="//cdn.x/b logo.png",B
            http://s/b
            #EXTINF:-1 tvg-logo="",C
            http://s/c
        """.trimIndent()
        val pl = M3uParser.parse(text, sourceId = "s9", baseUrl = "http://host.com/dir/list.m3u")
        assertEquals(listOf("http://e1/a.xml", "http://e2/b.xml.gz"), pl.epgUrls)
        assertEquals("s9/id:a", pl.channels[0].key)
        assertEquals("s9", pl.channels[0].sourceId)
        assertEquals("http://host.com/dir/logos/a.png", pl.channels[0].logo)
        assertEquals("https://cdn.x/b%20logo.png", pl.channels[1].logo)
        assertNull(pl.channels[2].logo)
    }

    @Test
    fun mergeKeepsOrderAndDistinctEpg() {
        val a = M3uParser.parse("#EXTM3U url-tvg=\"http://e/1\"\n#EXTINF:-1,A\nhttp://a", sourceId = "s1")
        val b = M3uParser.parse("#EXTM3U url-tvg=\"http://e/1\"\n#EXTINF:-1,A\nhttp://a", sourceId = "s2")
        val m = Playlist.merge(listOf(a, b))
        assertEquals(listOf("s1", "s2"), m.channels.map { it.sourceId })
        assertEquals(2, m.channels.map { it.key }.toSet().size)
        assertEquals(listOf("http://e/1"), m.epgUrls)
    }
}
