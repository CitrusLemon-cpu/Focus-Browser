package com.example.focuslock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

data class ArchivedEntry(
    val url: String,
    val name: String,
    val archiveFolderId: String? = null,
    val tags: List<String> = emptyList(),
    val description: String? = null,
    val archivedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

data class ArchiveFolder(
    val id: String,
    val name: String,
    val parentId: String?,
    val sortOrder: Int = 0
)

object ArchiveManager {
    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_ARCHIVE_ENTRIES = "archive_entries"
    private const val KEY_ARCHIVE_FOLDERS = "archive_folders"
    private const val KEY_SHOW_IN_SEARCH = "archive_show_in_search"
    private const val KEY_DATE_VIEW = "archive_date_view"
    private const val KEY_HIDE_IF_LOCK_IN_ACTIVE = "archive_hide_if_lock_in_active"
    private const val KEY_LOCK_IN_ENABLED = "archive_lock_in_enabled"
    private const val KEY_LOCK_IN_DURATION_MINUTES = "archive_lock_in_duration_minutes"
    private const val KEY_LOCK_IN_URL = "archive_lockin_url"
    private const val KEY_LOCK_IN_EXPIRES = "archive_lockin_expires"

    fun getArchivedEntries(context: Context): List<ArchivedEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ARCHIVE_ENTRIES, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<ArchivedEntry>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val tagsArray = obj.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArray != null) {
                for (j in 0 until tagsArray.length()) {
                    tags.add(tagsArray.optString(j))
                }
            }
            list.add(
                ArchivedEntry(
                    url = obj.optString("url"),
                    name = obj.optString("name", obj.optString("url")),
                    archiveFolderId = obj.optString("archiveFolderId", null).takeIf { !it.isNullOrEmpty() },
                    tags = tags.filter { it.isNotBlank() },
                    description = obj.optString("description", null).takeIf { !it.isNullOrBlank() },
                    archivedAt = obj.optLong("archivedAt", System.currentTimeMillis()),
                    sortOrder = obj.optInt("sortOrder", 0)
                )
            )
        }
        return list
    }

    fun saveArchivedEntries(context: Context, list: List<ArchivedEntry>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = JSONObject()
            obj.put("url", entry.url)
            obj.put("name", entry.name)
            if (entry.archiveFolderId != null) obj.put("archiveFolderId", entry.archiveFolderId)
            if (entry.tags.isNotEmpty()) {
                val tagsArray = JSONArray()
                entry.tags.forEach { tagsArray.put(it) }
                obj.put("tags", tagsArray)
            }
            if (!entry.description.isNullOrBlank()) obj.put("description", entry.description)
            obj.put("archivedAt", entry.archivedAt)
            obj.put("sortOrder", entry.sortOrder)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ARCHIVE_ENTRIES, array.toString())
            .apply()
    }

    fun getEntriesInArchiveFolder(context: Context, archiveFolderId: String?): List<ArchivedEntry> {
        return getArchivedEntries(context)
            .filter { it.archiveFolderId == archiveFolderId }
            .sortedBy { it.sortOrder }
    }

    fun isArchivedUrl(context: Context, url: String): Boolean {
        val normalizedUrl = WhitelistManager.normalizeUrl(url)
        return getArchivedEntries(context).any { entry ->
            val normalizedEntry = WhitelistManager.normalizeUrl(entry.url)
            if (!normalizedEntry.contains("/")) {
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")
            } else {
                normalizedUrl.startsWith(normalizedEntry)
            }
        }
    }

    fun getArchiveFolders(context: Context): List<ArchiveFolder> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ARCHIVE_FOLDERS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<ArchiveFolder>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(
                ArchiveFolder(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    parentId = obj.optString("parentId", null).takeIf { !it.isNullOrEmpty() },
                    sortOrder = obj.optInt("sortOrder", 0)
                )
            )
        }
        return list
    }

    fun saveArchiveFolders(context: Context, list: List<ArchiveFolder>) {
        val array = JSONArray()
        for (folder in list) {
            val obj = JSONObject()
            obj.put("id", folder.id)
            obj.put("name", folder.name)
            if (folder.parentId != null) obj.put("parentId", folder.parentId)
            obj.put("sortOrder", folder.sortOrder)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ARCHIVE_FOLDERS, array.toString())
            .apply()
    }

    fun getArchiveSubfolders(context: Context, parentId: String?): List<ArchiveFolder> {
        return getArchiveFolders(context)
            .filter { it.parentId == parentId }
            .sortedBy { it.sortOrder }
    }

    fun createArchiveFolder(context: Context, name: String, parentId: String?): ArchiveFolder {
        val folders = getArchiveFolders(context).toMutableList()
        val maxOrder = folders.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1
        val folder = ArchiveFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            parentId = parentId,
            sortOrder = maxOrder + 1
        )
        folders.add(folder)
        saveArchiveFolders(context, folders)
        return folder
    }

    fun renameArchiveFolder(context: Context, folderId: String, newName: String) {
        val folders = getArchiveFolders(context).toMutableList()
        val index = folders.indexOfFirst { it.id == folderId }
        if (index != -1) {
            folders[index] = folders[index].copy(name = newName.trim())
            saveArchiveFolders(context, folders)
        }
    }

    fun deleteArchiveFolder(context: Context, folderId: String) {
        val folders = getArchiveFolders(context)
        val folder = folders.find { it.id == folderId } ?: return
        val idsToDelete = mutableSetOf(folderId)
        fun collectChildren(parentId: String) {
            folders.filter { it.parentId == parentId }.forEach {
                idsToDelete.add(it.id)
                collectChildren(it.id)
            }
        }
        collectChildren(folderId)

        val targetParentId = folder.parentId
        val entries = getArchivedEntries(context).toMutableList()
        val targetExisting = entries.filter { it.archiveFolderId == targetParentId && it.archiveFolderId !in idsToDelete }
        var nextSortOrder = (targetExisting.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val movedEntries = entries
            .filter { it.archiveFolderId in idsToDelete }
            .sortedWith(compareBy<ArchivedEntry> { it.sortOrder }.thenByDescending { it.archivedAt })
            .map { entry ->
                entry.copy(archiveFolderId = targetParentId, sortOrder = nextSortOrder++)
            }

        val remainingEntries = entries.filter { it.archiveFolderId !in idsToDelete }.toMutableList()
        remainingEntries.addAll(movedEntries)
        saveArchivedEntries(context, remainingEntries)
        saveArchiveFolders(context, folders.filter { it.id !in idsToDelete })
    }

    fun moveEntryToArchiveFolder(context: Context, url: String, archiveFolderId: String?) {
        val normalizedUrl = WhitelistManager.normalizeUrl(url)
        val entries = getArchivedEntries(context).toMutableList()
        val index = entries.indexOfFirst { WhitelistManager.normalizeUrl(it.url) == normalizedUrl }
        if (index != -1) {
            val maxOrder = entries.filter { it.archiveFolderId == archiveFolderId }.maxOfOrNull { it.sortOrder } ?: -1
            entries[index] = entries[index].copy(archiveFolderId = archiveFolderId, sortOrder = maxOrder + 1)
            saveArchivedEntries(context, entries)
        }
    }

    fun archiveEntry(context: Context, entry: WhitelistEntry) {
        WhitelistManager.removeEntry(context, entry.url)
        val normalizedUrl = WhitelistManager.normalizeUrl(entry.url)
        val entries = getArchivedEntries(context)
            .filterNot { WhitelistManager.normalizeUrl(it.url) == normalizedUrl }
            .toMutableList()
        val maxOrder = entries.filter { it.archiveFolderId == null }.maxOfOrNull { it.sortOrder } ?: -1
        entries.add(
            ArchivedEntry(
                url = normalizedUrl,
                name = entry.name,
                tags = entry.tags,
                description = entry.description,
                archivedAt = System.currentTimeMillis(),
                sortOrder = maxOrder + 1
            )
        )
        saveArchivedEntries(context, entries)
    }

    fun restoreEntry(context: Context, archivedEntry: ArchivedEntry, targetFolderId: String?) {
        val normalizedUrl = WhitelistManager.normalizeUrl(archivedEntry.url)
        val entries = getArchivedEntries(context)
            .filterNot { WhitelistManager.normalizeUrl(it.url) == normalizedUrl }
        saveArchivedEntries(context, entries)
        WhitelistManager.addEntry(context, archivedEntry.url, archivedEntry.name, targetFolderId)
        WhitelistManager.setEntryTags(context, normalizedUrl, archivedEntry.tags)
        if (!archivedEntry.description.isNullOrBlank()) {
            WhitelistManager.setEntryDescription(context, normalizedUrl, archivedEntry.description ?: "")
        }
    }

    fun updateArchivedEntry(context: Context, url: String, newName: String, newTags: List<String>, newDescription: String?) {
        val normalizedUrl = WhitelistManager.normalizeUrl(url)
        val entries = getArchivedEntries(context).toMutableList()
        val index = entries.indexOfFirst { WhitelistManager.normalizeUrl(it.url) == normalizedUrl }
        if (index != -1) {
            entries[index] = entries[index].copy(
                name = newName.trim().ifEmpty { entries[index].url },
                tags = newTags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct(),
                description = newDescription?.trim()?.takeIf { it.isNotEmpty() }
            )
            saveArchivedEntries(context, entries)
        }
    }

    fun getArchivedEntriesByMonth(context: Context): LinkedHashMap<String, List<ArchivedEntry>> {
        val formatter = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val grouped = linkedMapOf<String, MutableList<ArchivedEntry>>()
        val entries = getArchivedEntries(context).sortedByDescending { it.archivedAt }
        for (entry in entries) {
            val label = formatter.format(Date(entry.archivedAt))
            grouped.getOrPut(label) { mutableListOf() }.add(entry)
        }
        return LinkedHashMap(grouped.mapValues { (_, value) -> value.sortedByDescending { it.archivedAt } })
    }

    fun getArchiveLockInSession(context: Context): Pair<String, Long>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_LOCK_IN_URL, null) ?: return null
        val expiresAt = prefs.getLong(KEY_LOCK_IN_EXPIRES, 0L)
        if (expiresAt <= 0L || System.currentTimeMillis() >= expiresAt) {
            endArchiveLockIn(context)
            return null
        }
        return Pair(url, expiresAt)
    }

    fun startArchiveLockIn(context: Context, url: String) {
        val durationMinutes = getArchiveLockInDurationMinutes(context)
        val expiresAt = System.currentTimeMillis() + (durationMinutes * 60_000L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCK_IN_URL, WhitelistManager.normalizeUrl(url))
            .putLong(KEY_LOCK_IN_EXPIRES, expiresAt)
            .apply()
    }

    fun endArchiveLockIn(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LOCK_IN_URL)
            .remove(KEY_LOCK_IN_EXPIRES)
            .apply()
    }

    fun isArchiveLockInSessionActive(context: Context): Boolean {
        return getArchiveLockInSession(context) != null
    }

    fun isShowInSearch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_IN_SEARCH, false)
    }

    fun setShowInSearch(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_IN_SEARCH, enabled)
            .apply()
    }

    fun isDateView(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DATE_VIEW, false)
    }

    fun setDateView(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DATE_VIEW, enabled)
            .apply()
    }

    fun isHideIfLockInActive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_IF_LOCK_IN_ACTIVE, false)
    }

    fun setHideIfLockInActive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_IF_LOCK_IN_ACTIVE, enabled)
            .apply()
    }

    fun isArchiveLockInEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCK_IN_ENABLED, false)
    }

    fun setArchiveLockInEnabled(context: Context, enabled: Boolean, durationMinutes: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCK_IN_ENABLED, enabled)
            .putInt(KEY_LOCK_IN_DURATION_MINUTES, durationMinutes.coerceAtLeast(1))
            .apply()
        if (!enabled) endArchiveLockIn(context)
    }

    fun getArchiveLockInDurationMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LOCK_IN_DURATION_MINUTES, 30)
    }
}
