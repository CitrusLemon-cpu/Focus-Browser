package com.example.focuslock

import android.text.Html
import org.json.JSONObject

object TitleFetcher {

    fun fetch(url: String, callback: (String?) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val fullUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    else -> "https://$url"
                }

                // YouTube: use oEmbed API for reliable video titles
                if (isYouTubeUrl(fullUrl)) {
                    val title = fetchYouTubeTitle(fullUrl)
                    if (title != null) {
                        callback(title)
                        return@thread
                    }
                    // Fall through to generic fetch if oEmbed fails
                }

                // Generic: fetch HTML page and parse og:title or <title>
                val connection = java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                connection.connect()

                val reader = connection.inputStream.bufferedReader()
                val content = StringBuilder()
                val buffer = CharArray(4096)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    content.append(buffer, 0, charsRead)
                    if (content.length > 100000) break  // 100KB should cover og:title on most sites
                }
                reader.close()
                connection.disconnect()

                val html = content.toString()

                // Try og:title first (cleaner, no site name suffix)
                val ogTitle = parseOgTitle(html)
                if (ogTitle != null) {
                    callback(ogTitle)
                    return@thread
                }

                // Fall back to <title> tag
                val titleRegex = "<title[^>]*>(.*?)</title>".toRegex(
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                )
                val match = titleRegex.find(html)
                val rawTitle = match?.groupValues?.get(1)?.trim()
                val decodedTitle = rawTitle?.let {
                    Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                }
                callback(decodedTitle)
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
    }

    private fun fetchYouTubeTitle(videoUrl: String): String? {
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=${java.net.URLEncoder.encode(videoUrl, "UTF-8")}&format=json"
            val connection = java.net.URL(oembedUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            if (connection.responseCode == 200) {
                val json = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val obj = JSONObject(json)
                obj.optString("title", null)?.takeIf { it.isNotEmpty() }
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseOgTitle(html: String): String? {
        // Match: <meta property="og:title" content="...">
        // Handle both single and double quotes, and content before or after property
        val patterns = listOf(
            """<meta[^>]+property\s*=\s*["']og:title["'][^>]+content\s*=\s*["']([^"']+)["']""",
            """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+property\s*=\s*["']og:title["']"""
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(html)
            if (match != null) {
                val raw = match.groupValues[1].trim()
                return Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            }
        }
        return null
    }
}
