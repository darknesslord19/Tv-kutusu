package com.tvkutu.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * XMLTV (xml, xml.gz, zip) akış ayrıştırıcı. Sadece playlist'teki kanallara ait ve
 * belirtilen zaman penceresindeki programları belleğe alır.
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        channels: List<Channel>,
        fromMs: Long,
        toMs: Long,
    ): EpgIndex {
        val idToKeys = HashMap<String, MutableList<String>>()
        val nameToKeys = HashMap<String, MutableList<String>>()
        for (c in channels) {
            c.tvgId?.let { idToKeys.getOrPut(it.lowercase()) { ArrayList() }.add(c.key) }
            nameToKeys.getOrPut(normalize(c.tvgName ?: c.name)) { ArrayList() }.add(c.key)
            if (c.tvgName != null) nameToKeys.getOrPut(normalize(c.name)) { ArrayList() }.add(c.key)
        }

        val resolved = HashMap<String, List<String>>() // xmltv kanal id -> kanal anahtarları
        val result = HashMap<String, MutableList<Programme>>()
        val logos = HashMap<String, String>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(unwrap(input), null)

        var curChannelId: String? = null
        val curNames = ArrayList<String>()
        var curIcon: String? = null

        var progChannel: String? = null
        var progStart = 0L
        var progEnd = 0L
        var progTitle: String? = null
        var progDesc: String? = null
        var inProgramme = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        curChannelId = parser.getAttributeValue(null, "id")
                        curNames.clear()
                        curIcon = null
                    }
                    "icon" -> if (curChannelId != null && curIcon == null) {
                        curIcon = parser.getAttributeValue(null, "src")?.trim()?.ifEmpty { null }
                    }
                    "display-name" -> if (curChannelId != null) curNames += parser.nextText()
                    "programme" -> {
                        val chId = parser.getAttributeValue(null, "channel")
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        inProgramme = chId != null && start != null &&
                            (stop ?: (start + 3_600_000)) > fromMs && start < toMs
                        if (inProgramme) {
                            progChannel = chId
                            progStart = start!!
                            progEnd = stop ?: (start + 3_600_000)
                            progTitle = null
                            progDesc = null
                        }
                    }
                    "title" -> if (inProgramme && progTitle == null) progTitle = parser.nextText()
                    "desc" -> if (inProgramme && progDesc == null) progDesc = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = curChannelId
                        if (id != null) {
                            val keys = LinkedHashSet<String>()
                            idToKeys[id.lowercase()]?.let { keys.addAll(it) }
                            curNames.forEach { n -> nameToKeys[normalize(n)]?.let { keys.addAll(it) } }
                            if (keys.isNotEmpty()) {
                                resolved[id] = keys.toList()
                                curIcon?.let { icon -> keys.forEach { k -> logos.putIfAbsent(k, icon) } }
                            }
                        }
                        curChannelId = null
                    }
                    "programme" -> {
                        if (inProgramme) {
                            val id = progChannel!!
                            val keys = resolved[id] ?: idToKeys[id.lowercase()]
                            if (keys != null) {
                                val p = Programme(progStart, progEnd, progTitle?.trim().orEmpty(), progDesc?.trim())
                                keys.forEach { k -> result.getOrPut(k) { ArrayList() }.add(p) }
                            }
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        }

        return EpgIndex(
            result.mapValues { (_, list) -> list.sortedBy { it.startMs }.distinctBy { it.startMs } },
            logos,
        )
    }

    /** gzip / zip / düz XML'i otomatik tanır. */
    private fun unwrap(raw: InputStream): InputStream {
        val bis = BufferedInputStream(raw, 64 * 1024)
        bis.mark(4)
        val b0 = bis.read()
        val b1 = bis.read()
        bis.reset()
        return when {
            b0 == 0x1f && b1 == 0x8b -> BufferedInputStream(GZIPInputStream(bis, 64 * 1024))
            b0 == 'P'.code && b1 == 'K'.code -> {
                val zis = ZipInputStream(bis)
                var entry = zis.nextEntry
                while (entry != null && (entry.isDirectory || !entry.name.endsWith(".xml", true))) {
                    entry = zis.nextEntry
                }
                requireNotNull(entry) { "ZIP içinde XML dosyası bulunamadı" }
                zis
            }
            else -> bis
        }
    }

    internal fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("""\b(hd|fhd|uhd|sd|4k|hevc|h265)\b"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")

    /** "20261104180000 +0300" → epoch ms. Ofset yoksa UTC kabul edilir. */
    internal fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        val digits = t.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        return try {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt() - 1,
                digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(),
                digits.substring(10, 12).toInt(),
                if (digits.length >= 14) digits.substring(12, 14).toInt() else 0,
            )
            var ms = cal.timeInMillis
            val rest = t.substring(digits.length).trim()
            val m = Regex("""^([+-])(\d{2}):?(\d{2})""").find(rest)
            if (m != null) {
                val sign = if (m.groupValues[1] == "-") -1 else 1
                val off = (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * 60_000L
                ms -= sign * off
            }
            ms
        } catch (e: Exception) {
            null
        }
    }
}
