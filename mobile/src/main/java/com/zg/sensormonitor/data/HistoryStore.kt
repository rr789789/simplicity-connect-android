package com.zg.sensormonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zg.sensormonitor.domain.SensorPayload
import java.io.File

data class HistoryPoint(val minute: Long, val min: Double, val max: Double, val average: Double, val last: Double)

class HistoryStore(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples(device TEXT NOT NULL,slot INTEGER NOT NULL,minute INTEGER NOT NULL,type INTEGER NOT NULL,min REAL NOT NULL,max REAL NOT NULL,sum REAL NOT NULL,count INTEGER NOT NULL,last REAL NOT NULL,PRIMARY KEY(device,slot,minute))")
        db.execSQL("CREATE INDEX samples_minute ON samples(minute)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun add(device: String, slot: Int, type: Int, payload: SensorPayload) {
        val minute = System.currentTimeMillis() / 60_000
        val value = if (type == 2) payload.xAngle / 10.0 else payload.pressure / 10.0
        writableDatabase.execSQL(
            "INSERT INTO samples(device,slot,minute,type,min,max,sum,count,last) VALUES(?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(device,slot,minute) DO UPDATE SET min=MIN(min,excluded.min),max=MAX(max,excluded.max),sum=sum+excluded.sum,count=count+1,last=excluded.last",
            arrayOf(device, slot, minute, type, value, value, value, 1, value)
        )
        writableDatabase.delete("samples", "minute < ?", arrayOf((minute - 90L * 24 * 60).toString()))
    }

    fun recent(device: String, slot: Int, limit: Int = 1440): List<HistoryPoint> {
        val result = mutableListOf<HistoryPoint>()
        readableDatabase.query("samples", arrayOf("minute", "min", "max", "sum", "count", "last"), "device=? AND slot=?", arrayOf(device, slot.toString()), null, null, "minute DESC", limit.toString()).use { c ->
            while (c.moveToNext()) result += HistoryPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3) / c.getInt(4).coerceAtLeast(1), c.getDouble(5))
        }
        return result.reversed()
    }

    fun exportCsv(context: Context, device: String, slot: Int): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, "history-${device.replace(":", "")}-slot${slot + 1}.csv").apply {
            writeText(buildString {
                appendLine("minute,min,max,average,last")
                recent(device, slot, 90 * 24 * 60).forEach { appendLine("${it.minute},${it.min},${it.max},${it.average},${it.last}") }
            })
        }
    }
}

data class AlarmPolicy(
    val low: Double? = null,
    val high: Double? = null,
    val dwellMs: Long = 3000,
    val hysteresis: Double = 0.0
)

class AlarmEvaluator {
    private val pendingSince = mutableMapOf<String, Long>()
    private val active = mutableSetOf<String>()
    private val activeDirection = mutableMapOf<String, Int>()

    fun evaluate(key: String, value: Double, policy: AlarmPolicy, now: Long = System.currentTimeMillis()): AlarmChange? {
        val direction = when { policy.low?.let { value < it } == true -> -1; policy.high?.let { value > it } == true -> 1; else -> 0 }
        val existing = activeDirection[key]
        if (existing != null) {
            val recovered = when (existing) {
                -1 -> policy.low == null || value >= policy.low + policy.hysteresis
                1 -> policy.high == null || value <= policy.high - policy.hysteresis
                else -> true
            }
            if (recovered) { active.remove(key); activeDirection.remove(key); pendingSince.remove(key); return AlarmChange.Cleared }
            return null
        }
        val out = direction != 0
        if (out) {
            val since = pendingSince.getOrPut(key) { now }
            if (now - since >= policy.dwellMs && active.add(key)) { activeDirection[key] = direction; return AlarmChange.Raised }
        } else {
            pendingSince.remove(key)
        }
        return null
    }
}

enum class AlarmChange { Raised, Cleared }
