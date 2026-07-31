package com.zg.sensormonitor

import android.app.Application
import com.zg.sensormonitor.ble.BleCentralManager
import com.zg.sensormonitor.data.AuditStore
import com.zg.sensormonitor.data.HistoryStore
import com.zg.sensormonitor.data.AlarmEvaluator
import com.zg.sensormonitor.data.PreferencesStore
import timber.log.Timber

class SensorMonitorApplication : Application() {
    lateinit var preferences: PreferencesStore
        private set
    lateinit var audit: AuditStore
        private set
    lateinit var ble: BleCentralManager
        private set
    lateinit var history: HistoryStore
        private set
    val alarms = AlarmEvaluator()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        preferences = PreferencesStore(this)
        audit = AuditStore(this)
        history = HistoryStore(this)
        ble = BleCentralManager(this, audit)
    }
}
