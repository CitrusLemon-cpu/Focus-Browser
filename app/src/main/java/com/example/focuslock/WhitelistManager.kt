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

    fun normalizeUrl(input: String): String {
        var result = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("m.")
        // Strip fragment only — preserve query strings
        result = result.split("#")[0]
        // Lowercase
        result = result.lowercase()
        // Strip trailing slash only for bare domains (no path component)
        // e.g. "youtube.com/" → "youtube.com" but "instagram.com/direct/" stays as-is
        if (!result.contains("/")) {
            // bare domain, no slash to strip
        } else if (result.indexOf("/") == result.length - 1) {
            // single trailing slash on what looks like a bare domain → strip
            result = result.removeSuffix("/")
        }
        // For path entries (multiple slashes or path with content), preserve as-is
        return result
    }

    fun addEntry(context: Context, entry: String) {
        val normalized = normalizeUrl(entry)
        val list = getWhitelist(context).toMutableList()
        if (!list.contains(normalized)) {
            list.add(normalized)
            saveWhitelist(context, list)
        }
    }

    fun removeEntry(context: Context, entry: String) {
        val list = getWhitelist(context).toMutableList()
        list.remove(entry)
        saveWhitelist(context, list)
    }

    fun updateEntry(context: Context, oldEntry: String, newEntry: String) {
        val normalizedNew = normalizeUrl(newEntry)
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOf(oldEntry)
        if (index != -1) {
            list[index] = normalizedNew
            saveWhitelist(context, list)
        }
    }

    fun isUrlAllowed(context: Context, url: String): Boolean {
        val normalizedUrl = normalizeUrl(url)
        val whitelist = getWhitelist(context)

        for (rawEntry in whitelist) {
            // Strip any legacy '*' suffix for backward compatibility
            val entry = rawEntry.trimEnd('*')
            val normalizedEntry = normalizeUrl(entry)

            if (!normalizedEntry.contains("/")) {
                // Rule 1: Domain-only entry — allow any subdomain + any path
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                if (urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")) {
                    return true
                }
            } else {
                // Rule 2: Path entry — URL must start with this prefix
                if (normalizedUrl.startsWith(normalizedEntry)) {
                    return true
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
