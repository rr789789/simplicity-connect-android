package com.zg.sensormonitor.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zg.sensormonitor.BuildConfig
import com.zg.sensormonitor.R
import com.zg.sensormonitor.SensorMonitorApplication
import com.zg.sensormonitor.data.PasswordResult
import com.zg.sensormonitor.databinding.ActivitySettingsBinding

class SettingsActivity : ThemedActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val app get() = application as SensorMonitorApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.modeGroup.check(if (app.preferences.mineMode) R.id.mineMode else R.id.normalMode)
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mineMode = checkedId == R.id.mineMode
            if (mineMode != app.preferences.mineMode) {
                app.preferences.mineMode = mineMode
                recreate()
            }
        }
        binding.rssiSlider.progress = app.preferences.rssiThreshold + 100
        updateRssi(binding.rssiSlider.progress)
        binding.rssiSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) { updateRssi(progress); if (fromUser) app.preferences.rssiThreshold = progress - 100 }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        binding.changePassword.setOnClickListener { showPasswordChange() }
        binding.exportDiagnostics.setOnClickListener { exportDiagnostics() }
        binding.version.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
    private fun updateRssi(progress: Int) { binding.rssiValue.text = "${progress - 100} dBm" }
    private fun showPasswordChange() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0) }
        val old = EditText(this).apply { hint = "当前密码"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val next = EditText(this).apply { hint = "新密码（至少8位）"; inputType = old.inputType }
        val confirm = EditText(this).apply { hint = "再次输入新密码"; inputType = old.inputType }
        box.addView(old); box.addView(next); box.addView(confirm)
        MaterialAlertDialogBuilder(this).setTitle("修改维护密码").setView(box).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
            when (val result = app.preferences.verifyPassword(old.text.toString().toCharArray())) {
                is PasswordResult.Valid -> if (next.text.toString() != confirm.text.toString()) toast("两次新密码不一致") else runCatching { app.preferences.changePassword(next.text.toString().toCharArray()) }.onSuccess { app.audit.record("维护密码", "已修改"); toast("密码已修改") }.onFailure { toast(it.message ?: "修改失败") }
                is PasswordResult.Invalid -> toast("当前密码错误")
                is PasswordResult.Locked -> toast("已锁定${result.seconds}秒")
            }
        }.show()
    }
    private fun exportDiagnostics() {
        runCatching { app.audit.exportDiagnostic(BuildConfig.VERSION_NAME, app.ble.summary()) }.onSuccess { file ->
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "分享工业调试包"))
        }.onFailure { toast(it.message ?: "导出失败") }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
