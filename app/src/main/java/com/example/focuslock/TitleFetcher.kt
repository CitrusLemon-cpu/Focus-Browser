package com.example.focuslock

import android.text.Html

object TitleFetcher {

    /**
     * Fetches the <title> tag from a URL in a background thread.
     * Calls [callback] on the calling thread context (caller must post to UI thread).
     */
    fun fetch(url: String, callback: (String?) -> Unit) {
        kotlin.concurrent.thread {
            try {
                val fullUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    else -> "https://$url"
                }
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
                    // Only need enough to find the <title> tag (usually in <head>)
                    if (content.length > 16000) break
                }
                reader.close()
                connection.disconnect()

                val titleRegex = "<title[^>]*>(.*?)</title>".toRegex(
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                )
                val match = titleRegex.find(content.toString())
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
}
