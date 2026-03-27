package com.example.focuslock

import android.content.Context
import org.json.JSONArray

data class WhitelistEntry(val url: String, val name: String)

object WhitelistManager {

    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_WHITELIST = "whitelist"

    fun getWhitelist(context: Context): List<WhitelistEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_WHITELIST, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<WhitelistEntry>()
        for (i in 0 until array.length()) {
            val element = array.get(i)
            if (element is org.json.JSONObject) {
                list.add(WhitelistEntry(
                    url = element.getString("url"),
                    name = element.optString("name", element.getString("url"))
                ))
            } else {
                val raw = element.toString()
                val url = normalizeUrl(raw.trimEnd('*'))
                list.add(WhitelistEntry(url = url, name = raw))
            }
        }
        return list
    }

    fun normalizeUrl(input: String): String {
        var result = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("m.")
        result = result.split("#")[0]
        val domainEnd = when {
            result.indexOf("/") != -1 -> result.indexOf("/")
            result.indexOf("?") != -1 -> result.indexOf("?")
            else -> result.length
        }
        result = result.substring(0, domainEnd).lowercase() + result.substring(domainEnd)
        if (!result.contains("/")) {
            // bare domain, no slash to strip
        } else if (result.indexOf("/") == result.length - 1) {
            result = result.removeSuffix("/")
        }
        return result
    }

    fun addEntry(context: Context, url: String, name: String) {
        val normalizedUrl = normalizeUrl(url)
        val list = getWhitelist(context).toMutableList()
        if (list.none { it.url == normalizedUrl }) {
            val displayName = if (name.isBlank()) normalizedUrl else name.trim()
            list.add(WhitelistEntry(url = normalizedUrl, name = displayName))
            saveWhitelist(context, list)
        }
    }

    fun removeEntry(context: Context, url: String) {
        val list = getWhitelist(context).toMutableList()
        list.removeAll { it.url == url }
        saveWhitelist(context, list)
    }

    fun updateEntry(context: Context, oldUrl: String, newUrl: String, newName: String) {
        val normalizedNew = normalizeUrl(newUrl)
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == oldUrl }
        if (index != -1) {
            val displayName = if (newName.isBlank()) normalizedNew else newName.trim()
            list[index] = WhitelistEntry(url = normalizedNew, name = displayName)
            saveWhitelist(context, list)
        }
    }

    fun updateEntryName(context: Context, url: String, newName: String) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            val displayName = if (newName.isBlank()) url else newName.trim()
            list[index] = WhitelistEntry(url = url, name = displayName)
            saveWhitelist(context, list)
        }
    }

    fun isUrlAllowed(context: Context, url: String): Boolean {
        val normalizedUrl = normalizeUrl(url)
        val whitelist = getWhitelist(context)

        for (whitelistEntry in whitelist) {
            val normalizedEntry = normalizeUrl(whitelistEntry.url)

            if (!normalizedEntry.contains("/")) {
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                if (urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")) {
                    return true
                }
            } else {
                if (normalizedUrl.startsWith(normalizedEntry)) {
                    return true
                }
            }
        }
        return false
    }

    private fun saveWhitelist(context: Context, list: List<WhitelistEntry>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = org.json.JSONObject()
            obj.put("url", entry.url)
            obj.put("name", entry.name)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WHITELIST, array.toString())
            .apply()
    }
}
