package com.zg.sensormonitor.ui

import android.os.Bundle
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import com.zg.sensormonitor.R

abstract class ThemedActivity : AppCompatActivity() {
    private var appliedMineMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedMineMode = preferredMineMode()
        setTheme(if (appliedMineMode) R.style.Theme_SensorMonitor_Mine else R.style.Theme_SensorMonitor_Normal)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (preferredMineMode() != appliedMineMode) recreate()
    }

    private fun preferredMineMode(): Boolean =
        getSharedPreferences("sensor_monitor", MODE_PRIVATE).getBoolean("mine_mode", true)
}

fun android.content.Context.themeColor(@AttrRes attribute: Int): Int {
    val value = android.util.TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) { "Missing theme attribute $attribute" }
    return value.data
}
