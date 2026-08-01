package com.zg.sensormonitor.ui

import android.os.Bundle
import android.view.View
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    override fun setContentView(view: View?) {
        super.setContentView(view)
        view?.let { applySystemBarInsets(it) }
    }

    private fun applySystemBarInsets(root: View) {
        val startTop = root.paddingTop
        val startBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                target.paddingLeft,
                startTop + bars.top,
                target.paddingRight,
                startBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun preferredMineMode(): Boolean =
        getSharedPreferences("sensor_monitor", MODE_PRIVATE).getBoolean("mine_mode", true)
}

fun android.content.Context.themeColor(@AttrRes attribute: Int): Int {
    val value = android.util.TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) { "Missing theme attribute $attribute" }
    return value.data
}
