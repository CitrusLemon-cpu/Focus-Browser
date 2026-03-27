package com.example.focuslock

import android.content.Context
import java.security.MessageDigest

object PasswordManager {

    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_PASSWORD_HASH = "password_hash"

    fun isPasswordSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_PASSWORD_HASH)
    }

    fun setPassword(context: Context, password: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PASSWORD_HASH, hashPassword(password))
            .apply()
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        return stored == hashPassword(password)
    }

    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
