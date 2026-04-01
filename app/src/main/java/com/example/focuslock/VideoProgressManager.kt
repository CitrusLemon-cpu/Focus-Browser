package com.example.focuslock

import android.content.Context
import android.net.Uri
import org.json.JSONObject

object VideoProgressManager {
    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_VIDEO_PROGRESS = "video_progress"

    private val invidiousHosts = listOf("yewtu.be", "invidious.nadeko.net")

    data class VideoProgress(
        val videoId: String,
        val currentTime: Double,
        val duration: Double,
        val lastWatched: Long
    ) {
        val percentage: Float get() = if (duration > 0) (currentTime / duration).toFloat().coerceIn(0f, 1f) else 0f
        val isFinished: Boolean get() = percentage >= 0.9f
        val isStarted: Boolean get() = currentTime > 5.0
    }

    fun extractVideoId(url: String): String? {
        val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val uri = try { Uri.parse(fullUrl) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        if (host.contains("youtube.com") || host.contains("youtu.be") ||
            invidiousHosts.any { host == it || host.endsWith(".$it") }) {
            if (host == "youtu.be") {
                return uri.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() }
            }
            if (uri.path == "/watch" || uri.path == "/watch/") {
                return uri.getQueryParameter("v")?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun isYouTubeOrInvidiousUrl(url: String): Boolean {
        return extractVideoId(url) != null
    }

    fun getAllProgress(context: Context): Map<String, VideoProgress> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_VIDEO_PROGRESS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, VideoProgress>()
            for (key in obj.keys()) {
                val entry = obj.getJSONObject(key)
                map[key] = VideoProgress(
                    videoId = key,
                    currentTime = entry.getDouble("currentTime"),
                    duration = entry.getDouble("duration"),
                    lastWatched = entry.getLong("lastWatched")
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun updateProgress(context: Context, videoId: String, currentTime: Double, duration: Double) {
        val all = getAllProgress(context).toMutableMap()
        val existing = all[videoId]
        if (existing != null && existing.isFinished && currentTime < existing.currentTime) {
            return
        }
        all[videoId] = VideoProgress(
            videoId = videoId,
            currentTime = currentTime,
            duration = duration,
            lastWatched = System.currentTimeMillis()
        )
        saveAll(context, all)
    }

    fun getProgress(context: Context, videoId: String): VideoProgress? {
        return getAllProgress(context)[videoId]
    }

    fun resetProgress(context: Context, videoId: String) {
        val all = getAllProgress(context).toMutableMap()
        all.remove(videoId)
        saveAll(context, all)
    }

    fun resetAllProgress(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VIDEO_PROGRESS)
            .apply()
    }

    fun isFinished(context: Context, videoId: String): Boolean {
        return getProgress(context, videoId)?.isFinished == true
    }

    private fun saveAll(context: Context, map: Map<String, VideoProgress>) {
        val obj = JSONObject()
        for ((key, vp) in map) {
            val entry = JSONObject()
            entry.put("currentTime", vp.currentTime)
            entry.put("duration", vp.duration)
            entry.put("lastWatched", vp.lastWatched)
            obj.put(key, entry)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIDEO_PROGRESS, obj.toString())
            .apply()
    }
}
