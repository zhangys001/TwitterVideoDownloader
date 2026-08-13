package com.twitterdownloader.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoInfoParserTest {

    private val parser = VideoInfoParser()

    @Test
    fun findTwitterUrl_extractsTwitterUrlFromText() {
        val text = "看看这个 https://twitter.com/elonmusk/status/1234567890123456789 有意思"
        assertEquals("https://twitter.com/elonmusk/status/1234567890123456789", parser.findTwitterUrl(text))
    }

    @Test
    fun findTwitterUrl_extractsXComUrl() {
        val text = "https://x.com/foo/status/9876543210987654321"
        assertEquals("https://x.com/foo/status/9876543210987654321", parser.findTwitterUrl(text))
    }

    @Test
    fun findTwitterUrl_returnsNullForNonTwitterText() {
        assertNull(parser.findTwitterUrl("https://www.youtube.com/watch?v=abc"))
    }

    @Test
    fun extractTweetId_parsesStatusId() {
        assertEquals("1234567890123456789", parser.extractTweetId("https://twitter.com/elonmusk/status/1234567890123456789"))
    }

    @Test
    fun extractTweetId_returnsNullWithoutStatus() {
        assertNull(parser.extractTweetId("https://twitter.com/elonmusk"))
    }

    @Test
    fun parseResponseText_extractsVideoUrlFromJson() {
        val json = "{\"url\":\"https://video.twimg.com/ext_tw_video/123.mp4?tag=14\",\"thumbnail_url\":\"https://pbs.twimg.com/media/abc.jpg\"}"
        val info = parser.parseResponseText(json)
        assertEquals("https://video.twimg.com/ext_tw_video/123.mp4?tag=14", info?.videoUrl)
        assertEquals("https://pbs.twimg.com/media/abc.jpg", info?.thumbnailUrl)
    }

    @Test
    fun parseResponseText_extractsFromVideoUrlField() {
        val json = "{\"video_url\":\"https://cdn.example.com/video.mp4\"}"
        assertEquals("https://cdn.example.com/video.mp4", parser.parseResponseText(json)?.videoUrl)
    }

    @Test
    fun parseResponseText_unescapesForwardSlashes() {
        val json = "{\"url\":\"https:\\/\\/video.twimg.com\\/ext_tw_video\\/123.mp4?tag=14\"}"
        assertEquals("https://video.twimg.com/ext_tw_video/123.mp4?tag=14", parser.parseResponseText(json)?.videoUrl)
    }

    @Test
    fun parseResponseText_extractsVideoUrlFromHtmlViaRegex() {
        val text = "<html><a href=\"https://video.example.com/v.mp4\">download</a></html>"
        assertEquals("https://video.example.com/v.mp4", parser.parseResponseText(text)?.videoUrl)
    }

    @Test
    fun parseResponseText_returnsNullForEmptyOrInvalid() {
        assertNull(parser.parseResponseText("{}"))
        assertNull(parser.parseResponseText(""))
        assertNull(parser.parseResponseText("<html></html>"))
    }

    @Test
    fun parseResponseText_readsVxtwitterMediaExtended() {
        val json = "{\"tweetURL\":\"https://twitter.com/a/status/123\",\"mediaURLs\":[\"https://video.twimg.com/ext_tw_video/123.mp4?tag=14\"],\"media_extended\":[{\"type\":\"video\",\"url\":\"https://video.twimg.com/ext_tw_video/123.mp4?tag=14\",\"thumbnail_url\":\"https://pbs.twimg.com/media/abc.jpg\"}]}"
        val info = parser.parseResponseText(json)
        assertEquals("https://video.twimg.com/ext_tw_video/123.mp4?tag=14", info?.videoUrl)
        assertEquals("https://pbs.twimg.com/media/abc.jpg", info?.thumbnailUrl)
    }

    @Test
    fun parseResponseText_readsMediaUrlsArray() {
        val json = "{\"tweetURL\":\"https://twitter.com/a/status/123\",\"mediaURLs\":[\"https://video.twimg.com/ext_tw_video/999.mp4?tag=14\"]}"
        val info = parser.parseResponseText(json)
        assertEquals("https://video.twimg.com/ext_tw_video/999.mp4?tag=14", info?.videoUrl)
    }

    @Test
    fun parseResponseText_rejectsTweetUrlAsVideo() {
        // 顶层 url 是推文页链接而非视频，不得当作视频地址返回
        val json = "{\"url\":\"https://twitter.com/a/status/123\"}"
        assertNull(parser.parseResponseText(json))
    }

    @Test
    fun parseResponseText_rejectsImageOnlyTweet() {
        // 纯图片推文不应被当作视频返回
        val json = "{\"mediaURLs\":[\"https://pbs.twimg.com/media/xyz.jpg\"],\"media_extended\":[{\"type\":\"image\",\"url\":\"https://pbs.twimg.com/media/xyz.jpg\"}]}"
        assertNull(parser.parseResponseText(json))
    }
}
