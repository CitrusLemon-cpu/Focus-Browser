package com.example.focuslock

import android.content.Context
import org.json.JSONArray

data class WhitelistEntry(val url: String, val name: String, val folderId: String? = null, val sortOrder: Int = 0, val tags: List<String> = emptyList(), val hidden: Boolean = false, val sourceFolderId: String? = null)

data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,
    val sortOrder: Int = 0,
    val hidden: Boolean = false,
    val blockedUntil: Long? = null,
    val lockInEnabled: Boolean = false,
    val lockInDurationMinutes: Int = 30,
    val lockInWarningEnabled: Boolean = true,
    val isCurated: Boolean = false,
    val iconEmoji: String? = null,
    val maxSites: Int? = null,
    val preventEditWithoutPassword: Boolean = false,
    val ignoreLockInMode: Boolean = false
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
                val tagsArray = element.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsArray != null) {
                    for (j in 0 until tagsArray.length()) {
                        tags.add(tagsArray.getString(j))
                    }
                }
                val hidden = element.optBoolean("hidden", false)
                list.add(WhitelistEntry(
                    url = element.getString("url"),
                    name = element.optString("name", element.getString("url")),
                    folderId = element.optString("folderId", null).takeIf { it?.isNotEmpty() == true },
                    sortOrder = element.optInt("sortOrder", 0),
                    tags = tags,
                    hidden = hidden,
                    sourceFolderId = element.optString("sourceFolderId", null).takeIf { it?.isNotEmpty() == true }
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
            val maxOrder = list.filter { it.folderId == folderId }.maxOfOrNull { it.sortOrder } ?: -1
            list.add(WhitelistEntry(url = normalizedUrl, name = displayName, folderId = folderId, sortOrder = maxOrder + 1))
            saveWhitelist(context, list)
        }
    }

    fun removeEntry(context: Context, url: String) {
        val list = getWhitelist(context).toMutableList()
        val entry = list.find { it.url == url }
        if (entry?.folderId != null) {
            val session = getLockInSession(context, entry.folderId)
            if (session != null && normalizeUrl(session.first!!) == normalizeUrl(url)) {
                endLockInSession(context, entry.folderId)
            }
        }
        list.removeAll { it.url == url }
        saveWhitelist(context, list)
    }

    fun updateEntry(context: Context, oldUrl: String, newUrl: String, newName: String) {
        val normalizedNew = normalizeUrl(newUrl)
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == oldUrl }
        if (index != -1) {
            val displayName = if (newName.isBlank()) normalizedNew else newName.trim()
            list[index] = list[index].copy(url = normalizedNew, name = displayName)
            saveWhitelist(context, list)
        }
    }

    fun updateEntryName(context: Context, url: String, newName: String) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            val displayName = if (newName.isBlank()) url else newName.trim()
            list[index] = list[index].copy(name = displayName)
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
                parentId = obj.optString("parentId", null).takeIf { it?.isNotEmpty() == true },
                sortOrder = obj.optInt("sortOrder", 0),
                hidden = obj.optBoolean("hidden", false),
                blockedUntil = obj.optLong("blockedUntil", 0).takeIf { it > 0 },
                lockInEnabled = obj.optBoolean("lockInEnabled", false),
                lockInDurationMinutes = obj.optInt("lockInDurationMinutes", 30),
                lockInWarningEnabled = obj.optBoolean("lockInWarningEnabled", true),
                isCurated = obj.optBoolean("isCurated", false),
                iconEmoji = obj.optString("iconEmoji", null).takeIf { it?.isNotEmpty() == true },
                maxSites = obj.optInt("maxSites", 0).takeIf { it > 0 },
                preventEditWithoutPassword = obj.optBoolean("preventEditWithoutPassword", false),
                ignoreLockInMode = obj.optBoolean("ignoreLockInMode", false)
            ))
        }
        return list
    }

    fun getSubfolders(context: Context, parentId: String?): List<Folder> {
        return getFolders(context).filter { it.parentId == parentId }.sortedBy { it.sortOrder }
    }

    fun getEntriesInFolder(context: Context, folderId: String?): List<WhitelistEntry> {
        return getWhitelist(context).filter { it.folderId == folderId }.sortedBy { it.sortOrder }
    }

    fun createFolder(context: Context, name: String, parentId: String?): Folder {
        val allFolders = getFolders(context).toMutableList()
        val maxOrder = allFolders.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1
        val folder = Folder(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            parentId = parentId,
            sortOrder = maxOrder + 1
        )
        allFolders.add(folder)
        saveFolders(context, allFolders)
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
            val maxOrder = list.filter { it.folderId == folderId }.maxOfOrNull { it.sortOrder } ?: -1
            list[index] = list[index].copy(folderId = folderId, sortOrder = maxOrder + 1)
            saveWhitelist(context, list)
        }
    }

    fun moveFolderToParent(context: Context, folderId: String, newParentId: String?) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            val maxOrder = list.filter { it.parentId == newParentId }.maxOfOrNull { it.sortOrder } ?: -1
            list[index] = list[index].copy(parentId = newParentId, sortOrder = maxOrder + 1)
            saveFolders(context, list)
        }
    }

    fun reorderEntries(context: Context, folderId: String?, orderedUrls: List<String>) {
        val list = getWhitelist(context).toMutableList()
        for ((index, url) in orderedUrls.withIndex()) {
            val i = list.indexOfFirst { it.url == url }
            if (i != -1) {
                list[i] = list[i].copy(sortOrder = index)
            }
        }
        saveWhitelist(context, list)
    }

    fun reorderFolders(context: Context, parentId: String?, orderedIds: List<String>) {
        val list = getFolders(context).toMutableList()
        for ((index, id) in orderedIds.withIndex()) {
            val i = list.indexOfFirst { it.id == id }
            if (i != -1) {
                list[i] = list[i].copy(sortOrder = index)
            }
        }
        saveFolders(context, list)
    }

    private fun saveWhitelist(context: Context, list: List<WhitelistEntry>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = org.json.JSONObject()
            obj.put("url", entry.url)
            obj.put("name", entry.name)
            if (entry.folderId != null) obj.put("folderId", entry.folderId)
            obj.put("sortOrder", entry.sortOrder)
            if (entry.tags.isNotEmpty()) {
                val tagsArr = JSONArray()
                for (tag in entry.tags) { tagsArr.put(tag) }
                obj.put("tags", tagsArr)
            }
            if (entry.hidden) obj.put("hidden", true)
            if (entry.sourceFolderId != null) obj.put("sourceFolderId", entry.sourceFolderId)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WHITELIST, array.toString())
            .apply()
    }

    fun setEntryTags(context: Context, url: String, tags: List<String>) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            list[index] = list[index].copy(tags = tags.map { it.trim().lowercase() }.distinct())
            saveWhitelist(context, list)
        }
    }

    fun addTagToEntry(context: Context, url: String, tag: String) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            val normalizedTag = tag.trim().lowercase()
            if (normalizedTag.isNotEmpty() && normalizedTag !in list[index].tags) {
                list[index] = list[index].copy(tags = list[index].tags + normalizedTag)
                saveWhitelist(context, list)
            }
        }
    }

    fun removeTagFromEntry(context: Context, url: String, tag: String) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            val normalizedTag = tag.trim().lowercase()
            list[index] = list[index].copy(tags = list[index].tags - normalizedTag)
            saveWhitelist(context, list)
        }
    }

    fun getAllTags(context: Context): Set<String> {
        return getWhitelist(context).flatMap { it.tags }.toSet()
    }

    fun getEntriesByTag(context: Context, tag: String): List<WhitelistEntry> {
        val normalizedTag = tag.trim().lowercase()
        return getWhitelist(context).filter { normalizedTag in it.tags }
    }

    fun setEntryHidden(context: Context, url: String, hidden: Boolean) {
        val list = getWhitelist(context).toMutableList()
        val index = list.indexOfFirst { it.url == url }
        if (index != -1) {
            list[index] = list[index].copy(hidden = hidden)
            saveWhitelist(context, list)
        }
    }

    fun setFolderHidden(context: Context, folderId: String, hidden: Boolean) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(hidden = hidden)
            saveFolders(context, list)
        }
    }

    fun isFolderEffectivelyHidden(context: Context, folderId: String): Boolean {
        val allFolders = getFolders(context)
        var currentId: String? = folderId
        while (currentId != null) {
            val folder = allFolders.find { it.id == currentId } ?: return false
            if (folder.hidden) return true
            currentId = folder.parentId
        }
        return false
    }

    fun isEntryEffectivelyHidden(context: Context, entry: WhitelistEntry): Boolean {
        if (entry.hidden) return true
        val folderId = entry.folderId ?: return false
        return isFolderEffectivelyHidden(context, folderId)
    }

    fun blockFolder(context: Context, folderId: String, durationMillis: Long) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(blockedUntil = System.currentTimeMillis() + durationMillis)
            saveFolders(context, list)
        }
    }

    fun unblockFolder(context: Context, folderId: String) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(blockedUntil = null)
            saveFolders(context, list)
        }
    }

    fun isFolderBlocked(context: Context, folderId: String): Boolean {
        val folder = getFolders(context).find { it.id == folderId } ?: return false
        val blockedUntil = folder.blockedUntil ?: return false
        if (System.currentTimeMillis() >= blockedUntil) {
            unblockFolder(context, folderId)
            return false
        }
        return true
    }

    fun isFolderEffectivelyBlocked(context: Context, folderId: String): Boolean {
        val allFolders = getFolders(context)
        var currentId: String? = folderId
        while (currentId != null) {
            val folder = allFolders.find { it.id == currentId } ?: return false
            val blockedUntil = folder.blockedUntil
            if (blockedUntil != null) {
                if (System.currentTimeMillis() >= blockedUntil) {
                    unblockFolder(context, currentId)
                } else {
                    return true
                }
            }
            currentId = folder.parentId
        }
        return false
    }

    fun getFolderBlockTimeRemaining(context: Context, folderId: String): Long {
        val folder = getFolders(context).find { it.id == folderId } ?: return 0
        val blockedUntil = folder.blockedUntil ?: return 0
        return (blockedUntil - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun isUrlInBlockedFolder(context: Context, url: String): Pair<Boolean, String?> {
        val normalizedUrl = normalizeUrl(url)
        val whitelist = getWhitelist(context)
        for (entry in whitelist) {
            val normalizedEntry = normalizeUrl(entry.url)
            val matches = if (!normalizedEntry.contains("/")) {
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")
            } else {
                normalizedUrl.startsWith(normalizedEntry)
            }
            if (matches && entry.folderId != null && isFolderEffectivelyBlocked(context, entry.folderId)) {
                val allFolders = getFolders(context)
                var currentId: String? = entry.folderId
                var blockedFolderName: String? = null
                while (currentId != null) {
                    val folder = allFolders.find { it.id == currentId } ?: break
                    if (folder.blockedUntil != null && System.currentTimeMillis() < folder.blockedUntil) {
                        blockedFolderName = folder.name
                        break
                    }
                    currentId = folder.parentId
                }
                return Pair(true, blockedFolderName)
            }
        }
        return Pair(false, null)
    }

    fun startLockInSession(context: Context, folderId: String, lockedUrl: String) {
        val folder = getFolders(context).find { it.id == folderId } ?: return
        if (isFolderEffectivelyBlocked(context, folderId)) return
        val expiresAt = System.currentTimeMillis() + (folder.lockInDurationMinutes * 60 * 1000L)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("lockin_${folderId}_url", lockedUrl)
            .putLong("lockin_${folderId}_expires", expiresAt)
            .apply()
    }

    fun endLockInSession(context: Context, folderId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("lockin_${folderId}_url")
            .remove("lockin_${folderId}_expires")
            .apply()
    }

    fun getLockInSession(context: Context, folderId: String): Pair<String?, Long>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString("lockin_${folderId}_url", null) ?: return null
        val expires = prefs.getLong("lockin_${folderId}_expires", 0)
        if (System.currentTimeMillis() >= expires) {
            endLockInSession(context, folderId)
            return null
        }
        return Pair(url, expires)
    }

    fun getEffectiveLockInFolderId(context: Context, folderId: String): String? {
        val allFolders = getFolders(context)
        var currentId: String? = folderId
        while (currentId != null) {
            val folder = allFolders.find { it.id == currentId } ?: return null
            if (folder.lockInEnabled) return currentId
            currentId = folder.parentId
        }
        return null
    }

    fun isLockInArmed(context: Context, folderId: String): Boolean {
        val effectiveId = getEffectiveLockInFolderId(context, folderId) ?: return false
        return getLockInSession(context, effectiveId) == null
    }

    fun isLockInActive(context: Context, folderId: String): Boolean {
        val effectiveId = getEffectiveLockInFolderId(context, folderId) ?: return false
        return getLockInSession(context, effectiveId) != null
    }

    fun setLockInEnabled(context: Context, folderId: String, enabled: Boolean, durationMinutes: Int = 30) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(lockInEnabled = enabled, lockInDurationMinutes = durationMinutes)
            saveFolders(context, list)
            if (!enabled) endLockInSession(context, folderId)
        }
    }

    fun setLockInWarningEnabled(context: Context, folderId: String, enabled: Boolean) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(lockInWarningEnabled = enabled)
            saveFolders(context, list)
        }
    }

    fun createCuratedFolder(context: Context, name: String, iconEmoji: String): Folder {
        val allFolders = getFolders(context).toMutableList()
        val folder = Folder(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            parentId = null,
            sortOrder = -1,
            isCurated = true,
            iconEmoji = iconEmoji
        )
        allFolders.add(folder)
        saveFolders(context, allFolders)
        return folder
    }

    fun getCuratedFolders(context: Context): List<Folder> {
        return getFolders(context).filter { it.isCurated }
    }

    fun copyEntryToCuratedFolder(context: Context, entryUrl: String, curatedFolderId: String): Boolean {
        val folder = getFolders(context).find { it.id == curatedFolderId } ?: return false
        if (!folder.isCurated) return false

        if (folder.maxSites != null) {
            val currentCount = getEntriesInFolder(context, curatedFolderId).size
            if (currentCount >= folder.maxSites) return false
        }

        val originalEntry = getWhitelist(context).find { it.url == entryUrl } ?: return false

        val existing = getWhitelist(context).find { it.url == entryUrl && it.folderId == curatedFolderId }
        if (existing != null) return false

        val list = getWhitelist(context).toMutableList()
        val maxOrder = list.filter { it.folderId == curatedFolderId }.maxOfOrNull { it.sortOrder } ?: -1
        list.add(WhitelistEntry(
            url = originalEntry.url,
            name = originalEntry.name,
            folderId = curatedFolderId,
            sortOrder = maxOrder + 1,
            tags = originalEntry.tags,
            sourceFolderId = originalEntry.folderId
        ))
        saveWhitelist(context, list)
        return true
    }

    fun getEntriesInCuratedFolder(context: Context, folderId: String): List<WhitelistEntry> {
        return getWhitelist(context).filter { it.folderId == folderId }.sortedBy { it.sortOrder }
    }

    fun setCuratedFolderMaxSites(context: Context, folderId: String, maxSites: Int?) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(maxSites = maxSites)
            saveFolders(context, list)
        }
    }

    fun setCuratedPreventEdit(context: Context, folderId: String, prevent: Boolean) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(preventEditWithoutPassword = prevent)
            saveFolders(context, list)
        }
    }

    fun setCuratedIgnoreLockIn(context: Context, folderId: String, ignore: Boolean) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(ignoreLockInMode = ignore)
            saveFolders(context, list)
        }
    }

    fun setCuratedFolderIcon(context: Context, folderId: String, emoji: String) {
        val list = getFolders(context).toMutableList()
        val index = list.indexOfFirst { it.id == folderId }
        if (index != -1) {
            list[index] = list[index].copy(iconEmoji = emoji)
            saveFolders(context, list)
        }
    }

    fun removeEntryFromFolder(context: Context, url: String, folderId: String) {
        val list = getWhitelist(context).toMutableList()
        list.removeAll { it.url == url && it.folderId == folderId }
        saveWhitelist(context, list)
    }

    fun isUrlBlockedByLockIn(context: Context, url: String): Boolean {
        val normalizedUrl = normalizeUrl(url)
        val whitelist = getWhitelist(context)
        var anyBlocked = false
        for (entry in whitelist) {
            val normalizedEntry = normalizeUrl(entry.url)
            val matches = if (!normalizedEntry.contains("/")) {
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")
            } else {
                normalizedUrl.startsWith(normalizedEntry)
            }
            if (!matches) continue
            if (entry.folderId == null) return false
            val effectiveId = getEffectiveLockInFolderId(context, entry.folderId)
            if (effectiveId == null) return false
            val session = getLockInSession(context, effectiveId)
            if (session == null) return false
            if (normalizeUrl(session.first!!) == normalizedEntry) return false
            anyBlocked = true
        }
        return anyBlocked
    }

    fun getLockInBlockTimeRemaining(context: Context, url: String): Long {
        val normalizedUrl = normalizeUrl(url)
        val whitelist = getWhitelist(context)
        var maxRemaining = 0L
        for (entry in whitelist) {
            val normalizedEntry = normalizeUrl(entry.url)
            val matches = if (!normalizedEntry.contains("/")) {
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")
            } else {
                normalizedUrl.startsWith(normalizedEntry)
            }
            if (!matches) continue
            if (entry.folderId == null) return 0
            val effectiveId = getEffectiveLockInFolderId(context, entry.folderId)
            if (effectiveId == null) return 0
            val session = getLockInSession(context, effectiveId) ?: return 0
            if (normalizeUrl(session.first!!) == normalizedEntry) return 0
            val remaining = (session.second - System.currentTimeMillis()).coerceAtLeast(0)
            if (remaining > maxRemaining) maxRemaining = remaining
        }
        return maxRemaining
    }

    private fun saveFolders(context: Context, list: List<Folder>) {
        val array = JSONArray()
        for (folder in list) {
            val obj = org.json.JSONObject()
            obj.put("id", folder.id)
            obj.put("name", folder.name)
            if (folder.parentId != null) obj.put("parentId", folder.parentId)
            obj.put("sortOrder", folder.sortOrder)
            if (folder.hidden) obj.put("hidden", true)
            if (folder.blockedUntil != null) obj.put("blockedUntil", folder.blockedUntil)
            if (folder.lockInEnabled) obj.put("lockInEnabled", true)
            if (folder.lockInDurationMinutes != 30) obj.put("lockInDurationMinutes", folder.lockInDurationMinutes)
            if (!folder.lockInWarningEnabled) obj.put("lockInWarningEnabled", false)
            if (folder.isCurated) obj.put("isCurated", true)
            if (folder.iconEmoji != null) obj.put("iconEmoji", folder.iconEmoji)
            if (folder.maxSites != null) obj.put("maxSites", folder.maxSites)
            if (folder.preventEditWithoutPassword) obj.put("preventEditWithoutPassword", true)
            if (folder.ignoreLockInMode) obj.put("ignoreLockInMode", true)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDERS, array.toString())
            .apply()
    }
}
