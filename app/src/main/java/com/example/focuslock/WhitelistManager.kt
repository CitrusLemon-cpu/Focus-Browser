package com.example.focuslock

import android.content.Context
import org.json.JSONArray

object WhitelistManager {

    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_WHITELIST = "whitelist"

    fun getWhitelist(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_WHITELIST, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    fun addEntry(context: Context, entry: String) {
        val list = getWhitelist(context).toMutableList()
        if (!list.contains(entry)) {
            list.add(entry)
            saveWhitelist(context, list)
        }
    }

    fun removeEntry(context: Context, entry: String) {
        val list = getWhitelist(context).toMutableList()
        list.remove(entry)
        saveWhitelist(context, list)
    }

    fun isUrlAllowed(context: Context, url: String): Boolean {
        val normalized = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .split("?")[0]
            .split("#")[0]
        val whitelist = getWhitelist(context)

        for (entry in whitelist) {
            val normalizedEntry = entry.removePrefix("www.")

            if (!normalizedEntry.endsWith("*")) {
                val domain = normalized.split("/")[0]
                if (domain == normalizedEntry) return true
            } else {
                val pattern = normalizedEntry.removeSuffix("*")
                if (pattern.contains("/")) {
                    val entryDomain = pattern.split("/")[0]
                    val entryPathPrefix = pattern.substring(pattern.indexOf("/"))
                    val urlDomain = normalized.split("/")[0]
                    val urlPath = if (normalized.contains("/")) {
                        "/" + normalized.substringAfter("/", "")
                    } else {
                        ""
                    }
                    if (urlDomain == entryDomain && urlPath.startsWith(entryPathPrefix)) return true
                } else {
                    val urlDomain = normalized.split("/")[0]
                    if (urlDomain == pattern || urlDomain.endsWith(".$pattern")) return true
                }
            }
        }
        return false
    }

    private fun saveWhitelist(context: Context, list: List<String>) {
        val array = JSONArray()
        for (entry in list) {
            array.put(entry)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WHITELIST, array.toString())
            .apply()
    }
}
