package com.twitterdownloader.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class VideoInfo(val videoUrl: String, val thumbnailUrl: String?)

open class VideoInfoParser {

    private val twitterPattern = Pattern.compile(
        "(https?://(mobile\\.)?twitter\\.com/\\w+/status/\\d+|https?://(mobile\\.)?x\\.com/\\w+/status/\\d+)"
    )
    private val tweetIdPattern = Pattern.compile("status/(\\d+)")

    /** 从任意文本中扫描出第一个推特/x 链接。 */
    fun findTwitterUrl(text: String): String? {
        val matcher = twitterPattern.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    fun extractTweetId(statusUrl: String): String? {
        val matcher = tweetIdPattern.matcher(statusUrl)
        return if (matcher.find()) matcher.group(1) else null
    }

    /** 多 API 回退解析：vxtwitter → twitsor → 直连页面。 */
    open suspend fun parse(statusUrl: String): VideoInfo? = withContext(Dispatchers.IO) {
        val tweetId = extractTweetId(statusUrl) ?: return@withContext null
        try {
            val v1 = fetchFromApi("https://api.vxtwitter.com/twitter/status/$tweetId")
            if (v1?.videoUrl?.isNotEmpty() == true) return@withContext v1
        } catch (_: Exception) { }
        try {
            val v2 = fetchFromApi("https://twitsor.com/api/twitter-video?url=$statusUrl")
            if (v2?.videoUrl?.isNotEmpty() == true) return@withContext v2
        } catch (_: Exception) { }
        try {
            val v3 = extractFromDirectPage(statusUrl)
            if (v3?.videoUrl?.isNotEmpty() == true) return@withContext v3
        } catch (_: Exception) { }
        null
    }

    private suspend fun fetchFromApi(apiUrl: String): VideoInfo? = withContext(Dispatchers.IO) {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (connection.responseCode == 200) {
                parseResponseText(connection.inputStream.bufferedReader().readText())
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 从响应文本提取视频地址（纯逻辑，供单测）。 */
    internal fun parseResponseText(response: String): VideoInfo? {
        runCatching {
            val json = JSONTokener(response).nextValue() as JSONObject

            // vxtwitter/BetterTwitFix：视频在 media_extended[].url / mediaURLs[]，
            // tweetURL/url 是推文页链接而非视频，不得作为视频地址。
            extractFromMediaFields(json)?.let { return it }

            val videoUrl = json.optString("url", "")
                .ifEmpty { json.optString("video_url", "") }
                .ifEmpty { json.optString("content_url", "") }
                .ifEmpty { json.optString("download_url", "") }
            if (videoUrl.isNotEmpty() && looksLikeMediaUrl(videoUrl)) {
                val thumbnailUrl = json.optString("thumbnail_url", "")
                    .ifEmpty { json.optString("poster", "") }
                    .ifEmpty { json.optString("thumb", "") }
                return VideoInfo(videoUrl, thumbnailUrl.ifEmpty { null })
            }
        }

        val patterns = listOf(
            Pattern.compile("(https?://[^\\s\"',<>]+\\.mp4[^\\s\"',<>]*)"),
            Pattern.compile("\"url\":\"([^\"]+\\.mp4[^\"]*)\""),
            Pattern.compile("video_url[\":]+(https?://[^\",\\s]+)")
        )
        for (pattern in patterns) {
            val matcher = pattern.matcher(response)
            if (matcher.find()) {
                val found = (matcher.group(1) ?: matcher.group(0) ?: "")
                    .replace("\\u002F", "/")
                    .replace("\\/", "/")
                    .replace("\"", "")
                if (found.contains(".mp4") || found.contains("video")) {
                    return VideoInfo(found, null)
                }
            }
        }
        return null
    }

    /** 从 media_extended / mediaURLs 提取视频地址；无则返回 null。 */
    private fun extractFromMediaFields(json: JSONObject): VideoInfo? {
        val extended = json.optJSONArray("media_extended")
        if (extended != null) {
            for (i in 0 until extended.length()) {
                val entry = extended.optJSONObject(i) ?: continue
                val url = entry.optString("url", "")
                val type = entry.optString("type", "")
                if (url.isNotEmpty() && (type == "video" || type.isEmpty()) && looksLikeMediaUrl(url)) {
                    return VideoInfo(url, entry.optString("thumbnail_url", "").ifEmpty { null })
                }
            }
        }
        val mediaUrls = json.optJSONArray("mediaURLs")
        if (mediaUrls != null) {
            for (i in 0 until mediaUrls.length()) {
                val url = mediaUrls.optString(i, "")
                if (url.isNotEmpty() && looksLikeMediaUrl(url)) {
                    return VideoInfo(url, null)
                }
            }
        }
        return null
    }

    /** 粗判是否像是媒体地址（排除推文页、静态图片等普通 URL）。 */
    private fun looksLikeMediaUrl(url: String): Boolean =
        !url.contains("/status/") &&
            (url.contains(".mp4") || url.contains("video") || url.contains("video.twimg.com"))

    private suspend fun extractFromDirectPage(statusUrl: String): VideoInfo? = withContext(Dispatchers.IO) {
        val pageUrl = statusUrl.replace("mobile.", "").replace("x.com", "twitter.com")
        val connection = URL(pageUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15"
            )
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (connection.responseCode != 200) return@withContext null
            val content = connection.inputStream.bufferedReader().readText()
            val patterns = listOf(
                Pattern.compile("video_url[\":]+(https?://[^\",\\s]+)"),
                Pattern.compile("\"url\":\"(https?://[^\"]+\\.mp4[^\"]*)\""),
                Pattern.compile("(https://video[^\"'>\\s]+\\.mp4[^\"'>\\s]*)")
            )
            for (pattern in patterns) {
                val matcher = pattern.matcher(content)
                if (matcher.find()) {
                    val videoUrl = (matcher.group(1) ?: "")
                        .replace("\\u002F", "/")
                        .replace("\\/", "/")
                    if (videoUrl.contains("video") || videoUrl.contains(".mp4")) {
                        return@withContext VideoInfo(videoUrl, null)
                    }
                }
            }
            null
        } finally {
            connection.disconnect()
        }
    }
}
