package com.tvkutu.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cihazda bulunabilen bir yedek dosyası. */
data class BackupEntry(val label: String, val detail: String, val timeMs: Long, val read: () -> String)

/**
 * Yedek dosyalarını kaydetme/bulma. Dosya seçme penceresi olmayan TV kutularında yedek
 * "İndirilenler/TvKutu" klasörüne yazılır (Android 10+), daha eskilerde uygulamanın kendi
 * klasörüne.
 */
object BackupFiles {
    private const val FOLDER = "TvKutu"

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "tvkutu-yedek-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(now)) + ".json"

    /** Dosya seçici olmadan kaydeder; nereye kaydedildiğini döndürür. */
    fun saveToDownloads(context: Context, json: String): String {
        val name = fileName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("İndirilenler klasörüne yazılamadı")
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw IllegalStateException("İndirilenler klasörüne yazılamadı")
            return "İndirilenler/$FOLDER/$name"
        }
        val d = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        val f = File(d, name)
        f.writeText(json)
        return f.absolutePath
    }

    fun writeTo(context: Context, uri: Uri, json: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
            ?: throw IllegalStateException("Dosyaya yazılamadı")
    }

    fun readFrom(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Dosya okunamadı")

    /** Otomatik yedekler + İndirilenler/TvKutu içindeki (bu uygulamanın kaydettiği) yedekler, en yeni önce. */
    fun list(context: Context, repo: Repository): List<BackupEntry> {
        val out = ArrayList<BackupEntry>()
        repo.autoBackups().forEach { f ->
            val t = f.name.removePrefix("auto-").removeSuffix(".json").toLongOrNull() ?: f.lastModified()
            out += BackupEntry("Otomatik yedek", "Cihazın içinde", t) { f.readText() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val proj = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/$FOLDER%", "%.json"), null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)
                        val t = c.getLong(2) * 1000
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        out += BackupEntry(name, "İndirilenler/$FOLDER", t) { readFrom(context, uri) }
                    }
                }
            }
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).listFiles { f -> f.name.endsWith(".json") }
                ?.forEach { f -> out += BackupEntry(f.name, "Uygulama klasörü", f.lastModified()) { f.readText() } }
        }
        return out.sortedByDescending { it.timeMs }
    }
}
