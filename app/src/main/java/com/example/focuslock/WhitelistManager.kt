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
        // Preserve trailing * wildcard
        val hasWildcard = result.endsWith("*")
        if (hasWildcard) {
            result = result.removeSuffix("*")
        }
        // Strip query string and fragment
        result = result.split("?")[0].split("#")[0]
        // Lowercase
        result = result.lowercase()
        // Strip trailing slash only if it's the only slash
        // e.g. "youtube.com/" → "youtube.com" but "instagram.com/inbox/" stays
        val slashCount = result.count { it == '/' }
        if (slashCount == 1 && result.endsWith("/")) {
            result = result.removeSuffix("/")
        }
        // Re-add wildcard
        if (hasWildcard) {
            result = "$result*"
        }
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
        val normalized = normalizeUrl(url)
        val whitelist = getWhitelist(context)

        for (entry in whitelist) {
            // Entries are already normalized on save, but normalize again for safety
            val normalizedEntry = normalizeUrl(entry)

            if (!normalizedEntry.endsWith("*")) {
                // Case 1: Exact domain match, any path
                val urlDomain = normalized.split("/")[0]
                if (urlDomain == normalizedEntry) return true
            } else {
                val pattern = normalizedEntry.removeSuffix("*")
                if (pattern.contains("/")) {
                    // Case 3: Domain + path prefix match
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
                    // Case 2: Domain + all subdomains
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
