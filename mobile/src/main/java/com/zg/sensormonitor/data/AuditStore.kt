package com.zg.sensormonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AuditEvent(val time: Long, val device: String?, val action: String, val result: String, val detail: String?)

class AuditStore(private val context: Context) : SQLiteOpenHelper(context, "audit.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,device TEXT,action TEXT NOT NULL,result TEXT NOT NULL,detail TEXT)")
        db.execSQL("CREATE INDEX audit_time ON audit(time)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun record(action: String, result: String, device: String? = null, detail: String? = null) {
        writableDatabase.insert("audit", null, ContentValues().apply {
            put("time", System.currentTimeMillis()); put("device", device); put("action", action)
            put("result", result); put("detail", detail?.take(500))
        })
        prune()
    }

    @Synchronized
    fun recent(limit: Int = 500): List<AuditEvent> {
        val result = mutableListOf<AuditEvent>()
        readableDatabase.query("audit", arrayOf("time", "device", "action", "result", "detail"), null, null, null, null, "time DESC", limit.toString()).use { c ->
            while (c.moveToNext()) result += AuditEvent(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
        }
        return result
    }

    fun exportDiagnostic(appVersion: String, bleSummary: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "diagnostic-${System.currentTimeMillis()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            entry("system.txt", "app=$appVersion\nandroid=${android.os.Build.VERSION.RELEASE}\nsdk=${android.os.Build.VERSION.SDK_INT}\ndevice=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n$bleSummary\n")
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
            entry("audit.csv", buildString {
                appendLine("time,device,action,result,detail")
                recent().reversed().forEach { e ->
                    append(csv(format.format(Date(e.time)))).append(',').append(csv(e.device)).append(',')
                    append(csv(e.action)).append(',').append(csv(e.result)).append(',').appendLine(csv(e.detail))
                }
            })
        }
        return file
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000
        writableDatabase.delete("audit", "time < ?", arrayOf(cutoff.toString()))
        writableDatabase.execSQL("DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY time DESC LIMIT 10000)")
    }

    private fun csv(value: String?): String = "\"${value.orEmpty().replace("\"", "\"\"")}\""
}
