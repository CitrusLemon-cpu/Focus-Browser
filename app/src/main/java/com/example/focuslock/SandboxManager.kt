package com.example.focuslock

import android.content.Context

object SandboxManager {

    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_SANDBOX_EXPIRES_AT = "sandbox_expires_at"

    fun isSandboxActive(context: Context): Boolean {
        val expiresAt = getExpiresAt(context)
        if (expiresAt <= 0L) return false
        return if (System.currentTimeMillis() < expiresAt) {
            true
        } else {
            endSandbox(context)
            false
        }
    }

    fun getExpiresAt(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SANDBOX_EXPIRES_AT, 0L)
    }

    fun startSandbox(context: Context, durationMillis: Long) {
        val expiresAt = System.currentTimeMillis() + durationMillis
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SANDBOX_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun endSandbox(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SANDBOX_EXPIRES_AT)
            .apply()
    }

    fun getTimeRemaining(context: Context): Long {
        val expiresAt = getExpiresAt(context)
        if (expiresAt <= 0L) return 0L
        return maxOf(0L, expiresAt - System.currentTimeMillis())
    }
}
