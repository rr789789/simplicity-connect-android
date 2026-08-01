package com.zg.sensormonitor.data

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("sensor_monitor", Context.MODE_PRIVATE)

    var rssiThreshold: Int
        get() = prefs.getInt("scan_rssi", -100)
        set(value) = prefs.edit().putInt("scan_rssi", value.coerceIn(-100, -35)).apply()

    var mineMode: Boolean
        get() = prefs.getBoolean("mine_mode", true)
        set(value) = prefs.edit().putBoolean("mine_mode", value).apply()

    var maintenanceMode: Boolean
        get() = prefs.getBoolean("site_maintenance", false)
        set(value) = prefs.edit().putBoolean("site_maintenance", value).apply()

    fun saveRecentReceiver(address: String, name: String) {
        prefs.edit().putString("recent_address", address).putString("recent_name", name).apply()
    }

    fun recentDevice(): Pair<String, String>? {
        val address = prefs.getString("recent_address", null) ?: return null
        return address to prefs.getString("recent_name", "").orEmpty()
    }

    fun tiltZero(address: String): Pair<Int, Int> =
        prefs.getInt("zero_x_$address", 0) to prefs.getInt("zero_y_$address", 0)

    fun setTiltZero(address: String, x: Int, y: Int) {
        prefs.edit().putInt("zero_x_$address", x).putInt("zero_y_$address", y).apply()
    }

    fun alarmPolicy(address: String, slot: Int): AlarmPolicy {
        val key = "alarm_${address}_$slot"
        val low = prefs.getString("${key}_low", null)?.toDoubleOrNull()
        val high = prefs.getString("${key}_high", null)?.toDoubleOrNull()
        return AlarmPolicy(low, high, prefs.getLong("${key}_dwell", 3000), prefs.getString("${key}_hysteresis", "0")?.toDoubleOrNull() ?: 0.0)
    }

    fun setAlarmPolicy(address: String, slot: Int, policy: AlarmPolicy) {
        val key = "alarm_${address}_$slot"
        prefs.edit().putString("${key}_low", policy.low?.toString()).putString("${key}_high", policy.high?.toString())
            .putLong("${key}_dwell", policy.dwellMs).putString("${key}_hysteresis", policy.hysteresis.toString()).apply()
    }

    fun verifyPassword(password: CharArray): PasswordResult {
        val now = System.currentTimeMillis()
        val lockedUntil = prefs.getLong("password_locked_until", 0)
        if (now < lockedUntil) return PasswordResult.Locked((lockedUntil - now + 999) / 1000)
        ensurePassword()
        val salt = Base64.decode(prefs.getString("password_salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("password_hash", ""), Base64.NO_WRAP)
        val actual = derive(password, salt)
        password.fill('\u0000')
        if (MessageDigest.isEqual(expected, actual)) {
            prefs.edit().putInt("password_failures", 0).putLong("password_locked_until", 0).apply()
            return PasswordResult.Valid(prefs.getBoolean("password_is_default", true))
        }
        val failures = prefs.getInt("password_failures", 0) + 1
        val lockSeconds = if (failures >= 5) 30L * (failures / 5) else 0L
        prefs.edit().putInt("password_failures", failures)
            .putLong("password_locked_until", if (lockSeconds > 0) now + lockSeconds * 1000 else 0).apply()
        return PasswordResult.Invalid(5 - failures % 5)
    }

    fun changePassword(newPassword: CharArray) {
        require(newPassword.size >= 8) { "维护密码至少8位" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(newPassword, salt)
        newPassword.fill('\u0000')
        prefs.edit()
            .putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean("password_is_default", false)
            .putInt("password_failures", 0).apply()
    }

    private fun ensurePassword() {
        if (prefs.contains("password_hash")) return
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val initial = "65222kinglong".toCharArray()
        val hash = derive(initial, salt)
        initial.fill('\u0000')
        prefs.edit()
            .putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean("password_is_default", true).apply()
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 120_000, 256)).encoded
}

sealed interface PasswordResult {
    data class Valid(val mustChange: Boolean) : PasswordResult
    data class Invalid(val attemptsBeforeDelay: Int) : PasswordResult
    data class Locked(val seconds: Long) : PasswordResult
}
