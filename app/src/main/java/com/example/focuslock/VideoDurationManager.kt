package com.example.focuslock

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object VideoDurationManager {
    private const val PREFS_NAME = "focus_lock_prefs"
    private const val KEY_VIDEO_DURATIONS = "video_durations"
    private const val MAX_RESPONSE_CHARS = 2_000_000

    private val executor = Executors.newFixedThreadPool(3)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun getDuration(context: Context, videoId: String): Long? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIDEO_DURATIONS, null) ?: return null
        return try {
            JSONObject(json).optLong(videoId).takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchAndCache(context: Context, videoId: String, callback: (Long?) -> Unit) {
        getDuration(context, videoId)?.let {
            callback(it)
            return
        }
        if (!inFlight.add(videoId)) return

        val appContext = context.applicationContext
        executor.execute {
            val duration = fetchDuration(videoId)
            if (duration != null) saveDuration(appContext, videoId, duration)
            inFlight.remove(videoId)
            callback(duration)
        }
    }

    fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    internal fun parseDurationSeconds(html: String): Long? {
        val seconds = """"lengthSeconds"\s*:\s*"(\d+)"""".toRegex()
            .find(html)?.groupValues?.get(1)?.toLongOrNull()
        if (seconds != null && seconds > 0) return seconds

        val milliseconds = """"approxDurationMs"\s*:\s*"(\d+)"""".toRegex()
            .find(html)?.groupValues?.get(1)?.toLongOrNull()
        return milliseconds?.takeIf { it > 0 }?.let { (it + 999) / 1000 }
    }

    private fun fetchDuration(videoId: String): Long? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedVideoId = URLEncoder.encode(videoId, Charsets.UTF_8.name())
            connection = URL("https://www.youtube.com/watch?v=$encodedVideoId&hl=en").openConnection() as HttpURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.connect()
            if (connection.responseCode !in 200..299) return null

            val content = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (content.length < MAX_RESPONSE_CHARS) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    content.append(buffer, 0, minOf(count, MAX_RESPONSE_CHARS - content.length))
                }
            }
            parseDurationSeconds(content.toString())
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    @Synchronized
    private fun saveDuration(context: Context, videoId: String, duration: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val obj = try {
            JSONObject(prefs.getString(KEY_VIDEO_DURATIONS, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        obj.put(videoId, duration)
        prefs.edit().putString(KEY_VIDEO_DURATIONS, obj.toString()).apply()
    }
}
