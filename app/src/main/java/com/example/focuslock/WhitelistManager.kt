package com.example.focuslock

import android.content.Context
import org.json.JSONArray

data class WhitelistEntry(val url: String, val name: String, val folderId: String? = null)

data class Folder(
    val id: String,
    val name: String,
    val parentId: String?
)

object WhitelistManager {

    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_FOLDERS = "folders"

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
                    name = element.optString("name", element.getString("url")),
                    folderId = element.optString("folderId", null).takeIf { it?.isNotEmpty() == true }
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

    fun addEntry(context: Context, url: String, name: String, folderId: String? = null) {
        val normalizedUrl = normalizeUrl(url)
        val list = getWhitelist(context).toMutableList()
        if (list.none { it.url == normalizedUrl }) {
            val displayName = if (name.isBlank()) normalizedUrl else name.trim()
            list.add(WhitelistEntry(url = normalizedUrl, name = displayName, folderId = folderId))
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
            list[index] = WhitelistEntry(url = normalizedNew, name = displayName, folderId = list[index].folderId)
            saveWhitelist(context, list)
        }
    }

    fun updateEntryName(context: Context, url: String, newName: String) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            val displayName = if (newName.isBlank()) url else newName.trim()
            list[index] = WhitelistEntry(url = url, name = displayName, folderId = list[index].folderId)
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

    fun getFolders(context: Context): List<Folder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FOLDERS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Folder>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Folder(
                id = obj.getString("id"),
                name = obj.getString("name"),
                parentId = obj.optString("parentId", null).takeIf { it?.isNotEmpty() == true }
            ))
        }
        return list
    }

    fun getSubfolders(context: Context, parentId: String?): List<Folder> {
        return getFolders(context).filter { it.parentId == parentId }
    }

    fun getEntriesInFolder(context: Context, folderId: String?): List<WhitelistEntry> {
        return getWhitelist(context).filter { it.folderId == folderId }
    }

    fun createFolder(context: Context, name: String, parentId: String?): Folder {
        val folder = Folder(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            parentId = parentId
        )
        val list = getFolders(context).toMutableList()
        list.add(folder)
        saveFolders(context, list)
        return folder
    }

    fun renameFolder(context: Context, folderId: String, newName: String) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(name = newName.trim())
            saveFolders(context, list)
        }
    }

    fun deleteFolder(context: Context, folderId: String) {
        val allFolders = getFolders(context)
        val idsToDelete = mutableSetOf(folderId)
        fun collectChildren(parentId: String) {
            allFolders.filter { it.parentId == parentId }.forEach {
                idsToDelete.add(it.id)
                collectChildren(it.id)
            }
        }
        collectChildren(folderId)

        val entries = getWhitelist(context).toMutableList()
        entries.removeAll { it.folderId in idsToDelete }
        saveWhitelist(context, entries)

        val remainingFolders = allFolders.filter { it.id !in idsToDelete }
        saveFolders(context, remainingFolders.toMutableList())
    }

    fun moveEntryToFolder(context: Context, url: String, folderId: String?) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            list[index] = WhitelistEntry(url = list[index].url, name = list[index].name, folderId = folderId)
            saveWhitelist(context, list)
        }
    }

    private fun saveWhitelist(context: Context, list: List<WhitelistEntry>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = org.json.JSONObject()
            obj.put("url", entry.url)
            obj.put("name", entry.name)
            if (entry.folderId != null) obj.put("folderId", entry.folderId)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WHITELIST, array.toString())
            .apply()
    }

    private fun saveFolders(context: Context, list: List<Folder>) {
        val array = JSONArray()
        for (folder in list) {
            val obj = org.json.JSONObject()
            obj.put("id", folder.id)
            obj.put("name", folder.name)
            if (folder.parentId != null) obj.put("parentId", folder.parentId)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDERS, array.toString())
            .apply()
    }
}
