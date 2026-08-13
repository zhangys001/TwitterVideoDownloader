# Twitter Video Downloader 全面优化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持功能不变的前提下，把单 Activity 巨石代码重构为完整 MVVM（应用级 DownloadManager + ViewModel），修复后台监控链接丢失与错误页存成 mp4 的 Bug，引入 MediaStore 公共目录存储、剪贴板监听器、进度节流等性能与健壮性优化。

**Architecture:** 应用级 `AppContainer` 持有唯一 `DownloadManager` 单例（3 个 worker 协程消费 `Channel<DownloadTask>`），输出带版本号的 `StateFlow<DownloadTasksState>` 与日志 `SharedFlow`。`MainViewModel` 收集其状态渲染 UI。`ClipboardMonitorService` 检测到链接后先提交到共享 manager，再用 `singleTop` + `onNewIntent` 拉起 Activity 展示进度。

**Tech Stack:** Kotlin, AndroidX AppCompat/Material/Lifecycle, kotlinx-coroutines (Channel/StateFlow/SharedFlow), MediaStore, JUnit4 + coroutines-test（单测）。

**计划文档对照 spec：** `docs/superpowers/specs/2026-08-12-full-optimization-design.md`（已确认）。

---

## 文件结构

**新建：**
- `app/src/main/java/com/twitterdownloader/app/DownloadTask.kt` — 任务模型 + `TaskStatus` 枚举 + 中文状态标签
- `app/src/main/java/com/twitterdownloader/app/network/VideoInfoParser.kt` — 多 API 回退解析，含可单测的 `parseResponseText`/`findTwitterUrl`/`extractTweetId`
- `app/src/main/java/com/twitterdownloader/app/storage/VideoSaver.kt` — 存储接口（可注入测试替身）
- `app/src/main/java/com/twitterdownloader/app/storage/MediaStoreVideoSaver.kt` — MediaStore 实现（API 29+）/ 传统目录（API 26–28）+ 下载校验 + HTML 嗅探
- `app/src/main/java/com/twitterdownloader/app/FailedTaskStore.kt` — 失败任务落盘
- `app/src/main/java/com/twitterdownloader/app/App.kt` — `Application` + `AppContainer`（DownloadManager 单例）
- `app/src/main/java/com/twitterdownloader/app/DownloadManager.kt` — 核心管线（Channel + 3 worker、去重、重试、进度节流）
- `app/src/main/java/com/twitterdownloader/app/MainViewModel.kt` — `AndroidViewModel`，暴露 `StateFlow<MainUiState>`
- `app/src/main/res/xml/network_security_config.xml` — 精确放行解析域名
- `app/src/test/java/com/twitterdownloader/app/network/VideoInfoParserTest.kt`
- `app/src/test/java/com/twitterdownloader/app/DownloadManagerTest.kt`

**重写：**
- `app/src/main/java/com/twitterdownloader/app/MainActivity.kt` — 变薄，只做 UI + 事件转发 + 剪贴板监听
- `app/src/main/java/com/twitterdownloader/app/ClipboardMonitorService.kt` — 剪贴板监听器 + 提交共享 manager

**修改：**
- `app/src/main/AndroidManifest.xml` — `android:name=".App"`、`launchMode="singleTop"`、移除 cleartext、移除 FileProvider、加 networkSecurityConfig
- `app/build.gradle.kts` — 测试依赖；后续移除 ConstraintLayout、开启 R8
- `app/proguard-rules.pro` — ViewModel keep 规则
- `README.md` — 更新功能/结构/原理/存储路径描述

**删除：**
- `app/src/main/res/xml/file_paths.xml`（FileProvider 不再使用）

---

### Task 1: 添加单元测试依赖

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: 修改 dependencies**

在 `app/build.gradle.kts` 的 `dependencies {}` 块末尾追加：

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
```

说明：`org.json:json` 让 JVM 单测能真实解析 JSON（Android 框架的 `org.json` 在单测里是"not mocked"桩）。

- [ ] **Step 2: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: add unit test dependencies (junit, coroutines-test, org.json)"
```

---

### Task 2: 任务模型 DownloadTask + TaskStatus

**Files:**
- Create: `app/src/main/java/com/twitterdownloader/app/DownloadTask.kt`

- [ ] **Step 1: 创建模型文件**

```kotlin
package com.twitterdownloader.app

/** 任务状态机。 */
enum class TaskStatus {
    QUEUED, PARSING, DOWNLOADING, COMPLETED, FAILED;

    /** 是否仍在处理中（用于去重判断）。 */
    val isActive: Boolean
        get() = this == QUEUED || this == PARSING || this == DOWNLOADING
}

/** 单个下载任务。字段在 worker 协程内原地更新（跨线程只写 Int/String 引用，用于 UI 展示可接受）。 */
data class DownloadTask(
    val url: String,
    val tweetId: String,
    var retryCount: Int = 0,
    var status: TaskStatus = TaskStatus.QUEUED,
    var progress: Int = 0,
    var thumbnailUrl: String? = null,
    var savedUri: String? = null,
    var errorMessage: String? = null
) {
    companion object {
        const val MAX_RETRIES = 3
    }
}

/** 中文状态标签（替代原魔法字符串匹配）。 */
val DownloadTask.statusLabel: String
    get() = when (status) {
        TaskStatus.QUEUED -> "等待中"
        TaskStatus.PARSING -> "获取信息..."
        TaskStatus.DOWNLOADING -> "下载中"
        TaskStatus.COMPLETED -> "已完成"
        TaskStatus.FAILED -> "失败"
    }
```

- [ ] **Step 2: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/DownloadTask.kt
git commit -m "feat: add DownloadTask model and TaskStatus enum"
```

---

### Task 3: VideoInfoParser + 单元测试（TDD）

**Files:**
- Test: `app/src/test/java/com/twitterdownloader/app/network/VideoInfoParserTest.kt`
- Create: `app/src/main/java/com/twitterdownloader/app/network/VideoInfoParser.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
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
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.twitterdownloader.app.network.VideoInfoParserTest"`
Expected: `FAILED`（`VideoInfoParser` 未定义，编译错误）

- [ ] **Step 3: 实现 VideoInfoParser**

```kotlin
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
            val videoUrl = json.optString("url", "")
                .ifEmpty { json.optString("video_url", "") }
                .ifEmpty { json.optString("content_url", "") }
                .ifEmpty { json.optString("download_url", "") }
            val thumbnailUrl = json.optString("thumbnail_url", "")
                .ifEmpty { json.optString("poster", "") }
                .ifEmpty { json.optString("thumb", "") }
            if (videoUrl.isNotEmpty()) return VideoInfo(videoUrl, thumbnailUrl.ifEmpty { null })
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
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.twitterdownloader.app.network.VideoInfoParserTest"`
Expected: 10 tests PASS（代码质量审查补充了 vxtwitter 字段解析后为 13 个）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/network/VideoInfoParser.kt app/src/test/java/com/twitterdownloader/app/network/VideoInfoParserTest.kt
git commit -m "feat: add VideoInfoParser with multi-API fallback and unit tests"
```

---

### Task 4: VideoSaver 接口 + MediaStoreVideoSaver + FailedTaskStore

**Files:**
- Create: `app/src/main/java/com/twitterdownloader/app/storage/VideoSaver.kt`
- Create: `app/src/main/java/com/twitterdownloader/app/storage/MediaStoreVideoSaver.kt`
- Create: `app/src/main/java/com/twitterdownloader/app/FailedTaskStore.kt`

- [ ] **Step 1: 创建存储接口**

```kotlin
package com.twitterdownloader.app.storage

import com.twitterdownloader.app.DownloadTask

interface VideoSaver {
    /**
     * 下载视频并保存，返回保存后的 URI 字符串；失败抛出异常或返回 null。
     * @param onProgress 进度回调（0-100），实现方可在此节流调用频率。
     */
    suspend fun save(task: DownloadTask, videoUrl: String, onProgress: (Int) -> Unit): String?
}
```

- [ ] **Step 2: 创建 MediaStore 实现**

```kotlin
package com.twitterdownloader.app.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.twitterdownloader.app.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MediaStoreVideoSaver(private val context: Context) : VideoSaver {

    override suspend fun save(
        task: DownloadTask,
        videoUrl: String,
        onProgress: (Int) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(task, videoUrl, onProgress)
        } else {
            saveToLegacyDir(task, videoUrl, onProgress)
        }
    }

    /** API 29+：写入公共 Downloads 集合，无需存储权限。 */
    private fun saveToMediaStore(
        task: DownloadTask,
        videoUrl: String,
        onProgress: (Int) -> Unit
    ): String? {
        val resolver = context.contentResolver
        val displayName = uniqueDisplayName(resolver, "twitter_${task.tweetId}.mp4")
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            val output = resolver.openOutputStream(uri) ?: return null
            output.use { downloadStream(task, videoUrl, it, onProgress) }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    /** API 26–28：写公共 Downloads 目录，需 WRITE_EXTERNAL_STORAGE。 */
    private fun saveToLegacyDir(
        task: DownloadTask,
        videoUrl: String,
        onProgress: (Int) -> Unit
    ): String? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "twitter_${task.tweetId}.mp4")
        return try {
            file.outputStream().use { downloadStream(task, videoUrl, it, onProgress) }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** 流式下载到 output，带内容类型校验 + HTML 嗅探 + 进度回调（百分比变化时才回调）。 */
    private fun downloadStream(
        task: DownloadTask,
        videoUrl: String,
        output: java.io.OutputStream,
        onProgress: (Int) -> Unit
    ) {
        val connection = URL(videoUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Referer", "https://twitter.com/")
            connection.connectTimeout = 60000
            connection.readTimeout = 60000

            val contentType = connection.contentType ?: ""
            if (contentType.isNotEmpty() &&
                !contentType.startsWith("video/") &&
                !contentType.contains("octet-stream")
            ) {
                throw IllegalStateException("非视频响应: $contentType")
            }

            val fileSize = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0L
                var lastProgress = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    // HTML 嗅探：仅检查开头几个字节
                    if (total < 4) {
                        for (i in 0 until minOf(read, 4 - total.toInt())) {
                            val b = buffer[i].toInt().and(0xFF)
                            if (b == '<'.code) throw IllegalStateException("响应不是视频内容(HTML)")
                        }
                    }
                    output.write(buffer, 0, read)
                    total += read
                    if (fileSize > 0) {
                        val percent = ((total * 100) / fileSize).toInt()
                        if (percent != lastProgress) {
                            lastProgress = percent
                            task.progress = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    /** MediaStore 内按 displayName 查重，冲突时追加 _1/_2/... 后缀。 */
    private fun uniqueDisplayName(resolver: ContentResolver, baseName: String): String {
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        var candidate = baseName
        var n = 1
        while (true) {
            val cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(candidate),
                null
            )
            val exists = cursor?.use { it.moveToFirst() } ?: false
            if (!exists) return candidate
            val dot = baseName.lastIndexOf('.')
            candidate = baseName.substring(0, dot) + "_$n" + baseName.substring(dot)
            n++
        }
    }
}
```

- [ ] **Step 3: 创建失败任务存储**

```kotlin
package com.twitterdownloader.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 失败任务信息落盘到 filesDir/failed_downloads/。 */
class FailedTaskStore(private val context: Context) {

    fun save(task: DownloadTask) {
        runCatching {
            val dir = File(context.filesDir, "failed_downloads")
            if (!dir.exists()) dir.mkdirs()
            val infoFile = File(dir, "${task.tweetId}.json")
            val json = JSONObject().apply {
                put("url", task.url)
                put("tweetId", task.tweetId)
                put("errorMessage", task.errorMessage ?: "未知错误")
                put("retryCount", task.retryCount)
                put("timestamp", System.currentTimeMillis())
            }
            infoFile.writeText(json.toString())
        }
    }
}
```

- [ ] **Step 4: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/storage/VideoSaver.kt app/src/main/java/com/twitterdownloader/app/storage/MediaStoreVideoSaver.kt app/src/main/java/com/twitterdownloader/app/FailedTaskStore.kt
git commit -m "feat: add VideoSaver interface, MediaStoreVideoSaver and FailedTaskStore"
```

---

### Task 5: DownloadManager + 单元测试（TDD）

**Files:**
- Test: `app/src/test/java/com/twitterdownloader/app/DownloadManagerTest.kt`
- Create: `app/src/main/java/com/twitterdownloader/app/DownloadManager.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.twitterdownloader.app

import com.twitterdownloader.app.network.VideoInfo
import com.twitterdownloader.app.network.VideoInfoParser
import com.twitterdownloader.app.storage.VideoSaver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    private class FakeParser(var videoInfo: VideoInfo? = VideoInfo("https://cdn.example.com/v.mp4", null)) :
        VideoInfoParser() {
        override suspend fun parse(statusUrl: String): VideoInfo? = videoInfo
    }

    private class FakeSaver(var failEveryTime: Boolean = false) : VideoSaver {
        var calls = 0
        var currentConcurrent = 0
        var maxConcurrentSeen = 0

        override suspend fun save(task: DownloadTask, videoUrl: String, onProgress: (Int) -> Unit): String? {
            calls++
            if (failEveryTime) throw RuntimeException("网络错误")
            currentConcurrent++
            if (currentConcurrent > maxConcurrentSeen) maxConcurrentSeen = currentConcurrent
            delay(1000) // 模拟 IO 耗时，让并发测试能观察到并行
            currentConcurrent--
            task.progress = 100
            return "content://fake/$calls"
        }
    }

    @Test
    fun submit_deduplicatesSameTweet() = runTest {
        val manager = DownloadManager(FakeParser(), FakeSaver(), this)
        manager.submit("https://twitter.com/a/status/111")
        manager.submit("https://twitter.com/a/status/111")
        manager.submit("https://x.com/a/status/111")
        advanceUntilIdle()
        assertEquals(1, manager.tasks.value.tasks.size)
    }

    @Test
    fun submit_ignoresNonTwitterText() = runTest {
        val manager = DownloadManager(FakeParser(), FakeSaver(), this)
        val added = manager.submit("hello world no link here")
        advanceUntilIdle()
        assertEquals(false, added)
        assertEquals(0, manager.tasks.value.tasks.size)
    }

    @Test
    fun download_completesAndSavesUri() = runTest {
        val manager = DownloadManager(FakeParser(), FakeSaver(), this)
        manager.submit("https://twitter.com/a/status/222")
        advanceUntilIdle()
        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals("content://fake/1", task.savedUri)
        assertEquals(100, task.progress)
    }

    @Test
    fun failedDownload_retriesUpToMaxThenFails() = runTest {
        val saver = FakeSaver(failEveryTime = true)
        val manager = DownloadManager(FakeParser(), saver, this, retryDelayMs = 0)
        manager.submit("https://twitter.com/a/status/333")
        advanceUntilIdle()
        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(DownloadTask.MAX_RETRIES, task.retryCount)
        assertEquals(DownloadTask.MAX_RETRIES, saver.calls)
    }

    @Test
    fun parserReturningNull_failsWithoutSaverCall() = runTest {
        val saver = FakeSaver()
        val manager = DownloadManager(FakeParser(videoInfo = null), saver, this, retryDelayMs = 0)
        manager.submit("https://twitter.com/a/status/444")
        advanceUntilIdle()
        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(0, saver.calls)
    }

    @Test
    fun maxParallel_boundsConcurrentDownloads() = runTest {
        val saver = FakeSaver()
        val manager = DownloadManager(FakeParser(), saver, this, maxParallel = 2, retryDelayMs = 0)
        repeat(5) { i -> manager.submit("https://twitter.com/a/status/1$i") }
        advanceUntilIdle()
        assertEquals(5, manager.tasks.value.tasks.size)
        assertEquals(2, saver.maxConcurrentSeen)
    }

    @Test
    fun retryFailed_requeuesFailedTasks() = runTest {
        val saver = FakeSaver(failEveryTime = true)
        val manager = DownloadManager(FakeParser(), saver, this, retryDelayMs = 0)
        manager.submit("https://twitter.com/a/status/555")
        advanceUntilIdle()
        assertEquals(TaskStatus.FAILED, manager.tasks.value.tasks.single().status)

        saver.failEveryTime = false // 让后续重试成功
        manager.retryFailed()
        advanceUntilIdle()

        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(0, task.retryCount)
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.twitterdownloader.app.DownloadManagerTest"`
Expected: `FAILED`（`DownloadManager` 未定义）

> **测试作用域说明（2026-08-13 修正）**：kotlinx-coroutines-test 1.9.0 的 `runTest` 在结束时**等待** TestScope 的活动子协程，而 worker 协程永不完成，直接传入 `this` 会抛 `UncompletedCoroutinesError`；传入 `backgroundScope` 又不会被 `advanceUntilIdle()` 驱动。正确做法是注入一个挂在测试调度器上、但不作为 TestScope 子任务的 scope：
>
> ```kotlin
> private fun TestScope.workerScope(): CoroutineScope =
>     CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
> ```
>
> 测试中所有 `DownloadManager(..., this)` 改用 `DownloadManager(..., workerScope())`，生产代码保持 `scope.launch { workerLoop() }`（继承注入 scope 的调度器）不变。

- [ ] **Step 3: 实现 DownloadManager**

```kotlin
package com.twitterdownloader.app

import com.twitterdownloader.app.network.VideoInfoParser
import com.twitterdownloader.app.storage.VideoSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 任务列表快照，revision 保证原地变更也能触发 StateFlow 发射。 */
data class DownloadTasksState(
    val revision: Long = 0L,
    val tasks: List<DownloadTask> = emptyList()
)

class DownloadManager(
    private val parser: VideoInfoParser,
    private val saver: VideoSaver,
    private val scope: CoroutineScope,
    private val maxParallel: Int = 3,
    private val retryDelayMs: Long = 2_000L,
    private val onTaskFailed: ((DownloadTask) -> Unit)? = null
) {
    companion object {
        private const val PROGRESS_INTERVAL_NS = 250_000_000L // 250ms 节流
    }

    private val taskChannel = Channel<DownloadTask>(Channel.UNLIMITED)

    private val _tasks = MutableStateFlow(DownloadTasksState())
    val tasks: StateFlow<DownloadTasksState> = _tasks

    private val _logEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logEvents: SharedFlow<String> = _logEvents

    private val progressLock = Any()
    private var lastProgressEmit = 0L

    init {
        require(maxParallel > 0) { "maxParallel must be > 0" }
        repeat(maxParallel) {
            scope.launch { workerLoop() }
        }
    }

    private suspend fun workerLoop() {
        for (task in taskChannel) {
            processTask(task)
        }
    }

    /** 提交剪贴板文本或链接，返回是否加入了新任务。 */
    fun submit(text: String): Boolean {
        val tweetUrl = parser.findTwitterUrl(text) ?: run {
            emitLog("未检测到推特链接")
            return false
        }
        val tweetId = parser.extractTweetId(tweetUrl) ?: return false
        if (_tasks.value.tasks.any { it.tweetId == tweetId && it.status.isActive }) {
            emitLog("已在队列中: $tweetId")
            return false
        }
        val task = DownloadTask(url = tweetUrl, tweetId = tweetId)
        updateTasks { it + task }
        emitLog("加入队列: $tweetId")
        taskChannel.trySend(task)
        return true
    }

    /** 将全部失败任务重新入队。 */
    fun retryFailed() {
        val failed = _tasks.value.tasks.filter { it.status == TaskStatus.FAILED }
        if (failed.isEmpty()) return
        emitLog("重试 ${failed.size} 个失败任务")
        failed.forEach { task ->
            task.retryCount = 0
            task.errorMessage = null
            task.status = TaskStatus.QUEUED
            updateTasks { it }
            taskChannel.trySend(task)
        }
    }

    private suspend fun processTask(task: DownloadTask) {
        try {
            task.status = TaskStatus.PARSING
            emitTaskUpdate(task)
            emitLog("解析: ${task.tweetId}")

            val videoInfo = parser.parse(task.url)
            if (videoInfo == null || videoInfo.videoUrl.isEmpty()) {
                throw DownloadException("无法获取视频地址")
            }

            task.thumbnailUrl = videoInfo.thumbnailUrl
            task.status = TaskStatus.DOWNLOADING
            emitTaskUpdate(task)
            emitLog("开始下载: ${task.tweetId}")

            val savedUri = saver.save(task, videoInfo.videoUrl) { percent ->
                emitProgressThrottled(task, percent)
            }
            if (savedUri == null) throw DownloadException("保存失败")

            task.savedUri = savedUri
            task.progress = 100
            task.status = TaskStatus.COMPLETED
            emitTaskUpdate(task)
            emitLog("下载完成: ${task.tweetId}")
        } catch (e: Exception) {
            handleFailure(task, e)
        }
    }

    private suspend fun handleFailure(task: DownloadTask, e: Exception) {
        task.errorMessage = e.message ?: "未知错误"
        task.retryCount++
        if (task.retryCount < DownloadTask.MAX_RETRIES) {
            task.status = TaskStatus.QUEUED
            emitTaskUpdate(task)
            emitLog("失败(${task.retryCount}/${DownloadTask.MAX_RETRIES}): ${e.message}")
            delay(retryDelayMs)
            taskChannel.send(task)
        } else {
            task.status = TaskStatus.FAILED
            emitTaskUpdate(task)
            emitLog("已达最大重试次数: ${e.message}")
            onTaskFailed?.invoke(task)
        }
    }

    private fun emitProgressThrottled(task: DownloadTask, percent: Int) {
        task.progress = percent
        synchronized(progressLock) {
            val now = System.nanoTime()
            if (now - lastProgressEmit >= PROGRESS_INTERVAL_NS) {
                lastProgressEmit = now
                emitTaskUpdate(task)
            }
        }
    }

    private fun emitTaskUpdate(task: DownloadTask) {
        updateTasks { list -> list.map { if (it === task) task else it } }
    }

    private fun updateTasks(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        val current = _tasks.value
        _tasks.value = DownloadTasksState(
            revision = current.revision + 1,
            tasks = transform(current.tasks)
        )
    }

    private fun emitLog(message: String) {
        _logEvents.tryEmit(message)
    }
}

class DownloadException(message: String) : Exception(message)
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.twitterdownloader.app.DownloadManagerTest"`
Expected: 7 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/DownloadManager.kt app/src/test/java/com/twitterdownloader/app/DownloadManagerTest.kt
git commit -m "feat: add DownloadManager with channel workers, dedup, retry and tests"
```

---

### Task 6: App / AppContainer（DownloadManager 单例宿主）

**Files:**
- Create: `app/src/main/java/com/twitterdownloader/app/App.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 创建 Application + AppContainer**

```kotlin
package com.twitterdownloader.app

import android.app.Application
import android.content.Context
import com.twitterdownloader.app.network.VideoInfoParser
import com.twitterdownloader.app.storage.MediaStoreVideoSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            parser = VideoInfoParser(),
            saver = MediaStoreVideoSaver(context.applicationContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onTaskFailed = { task -> FailedTaskStore(context.applicationContext).save(task) }
        )
    }
}
```

- [ ] **Step 2: Manifest 注册 Application**

在 `app/src/main/AndroidManifest.xml` 的 `<application>` 标签加上 `android:name=".App"`：

```xml
<application
    android:name=".App"
    android:allowBackup="true"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:supportsRtl="true"
    android:theme="@style/Theme.TwitterVideoDownloader"
    android:usesCleartextTraffic="true">
```

- [ ] **Step 3: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/App.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add Application container hosting app-scoped DownloadManager"
```

---

### Task 7: MainViewModel

**Files:**
- Create: `app/src/main/java/com/twitterdownloader/app/MainViewModel.kt`

- [ ] **Step 1: 创建 ViewModel**

```kotlin
package com.twitterdownloader.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val activeTask: DownloadTask? = null,
    val activeCount: Int = 0,
    val queueCount: Int = 0,
    val failedCount: Int = 0,
    val currentThumbnail: Bitmap? = null,
    val logText: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val manager: DownloadManager = (app as App).container.downloadManager

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val logBuffer = ArrayDeque<String>()
    private val maxLogLines = 200

    private var lastActiveTaskId: String? = null
    private var lastThumbnailTaskId: String? = null

    init {
        viewModelScope.launch {
            manager.tasks.collect { state -> onTasksChanged(state.tasks) }
        }
        viewModelScope.launch {
            manager.logEvents.collect { message -> appendLog(message) }
        }
    }

    fun submitUrl(url: String) {
        manager.submit(url)
    }

    fun onClipboardText(text: String) {
        manager.submit(text)
    }

    fun retryFailed() = manager.retryFailed()

    fun clearLog() {
        synchronized(logBuffer) { logBuffer.clear() }
        _uiState.update { it.copy(logText = "") }
    }

    /** 打开最近一个已下载文件；无则返回 null。 */
    fun openLatestDownload(): String? =
        _uiState.value.activeTask?.takeIf { it.status == TaskStatus.COMPLETED }?.savedUri

    /** 供 Activity 写入一次性日志（权限、目录等）。 */
    fun log(message: String) = appendLog(message)

    private fun onTasksChanged(tasks: List<DownloadTask>) {
        val activeTask = tasks.lastOrNull { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PARSING }
            ?: tasks.lastOrNull { it.status == TaskStatus.QUEUED }
            ?: tasks.lastOrNull()

        if (activeTask?.tweetId != lastActiveTaskId) {
            lastActiveTaskId = activeTask?.tweetId
            _uiState.update { it.copy(currentThumbnail = null) }
        }

        _uiState.update {
            it.copy(
                activeTask = activeTask,
                activeCount = tasks.count { t -> t.status.isActive },
                queueCount = tasks.count { t -> t.status == TaskStatus.QUEUED },
                failedCount = tasks.count { t -> t.status == TaskStatus.FAILED }
            )
        }
        loadThumbnailIfNeeded()
    }

    private fun loadThumbnailIfNeeded() {
        val task = _uiState.value.activeTask ?: return
        val url = task.thumbnailUrl ?: return
        if (lastThumbnailTaskId == task.tweetId) return
        lastThumbnailTaskId = task.tweetId
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = loadBitmap(url)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(currentThumbnail = bitmap) }
            }
        }
    }

    private fun loadBitmap(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            BitmapFactory.decodeStream(connection.inputStream)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message"
        val text = synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > maxLogLines) logBuffer.removeFirst()
            logBuffer.joinToString("\n")
        }
        _uiState.update { it.copy(logText = text) }
    }
}
```

- [ ] **Step 2: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/MainViewModel.kt
git commit -m "feat: add MainViewModel exposing UI state from DownloadManager"
```

---

### Task 8: 重构 MainActivity（变薄 + 修复链接丢失 Bug）

**Files:**
- Rewrite: `app/src/main/java/com/twitterdownloader/app/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 重写 MainActivity**

用以下完整内容覆盖 `MainActivity.kt`（删除旧的队列/网络/日志/轮询代码）：

```kotlin
package com.twitterdownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.twitterdownloader.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private var isServiceRunning = false

    companion object {
        const val ACTION_NEW_TWITTER_URL = "com.twitterdownloader.app.NEW_TWITTER_URL"
        const val EXTRA_TWITTER_URL = "twitter_url"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isEmpty()) {
            viewModel.log("所有权限已授予")
        } else {
            val list = denied.joinToString(", ") { getPermissionChineseName(it) }
            viewModel.log("权限被拒绝: $list")
            showPermissionDeniedDialog(list)
        }
    }

    private fun getPermissionChineseName(permission: String): String = when (permission) {
        Manifest.permission.POST_NOTIFICATIONS -> "通知权限"
        Manifest.permission.WRITE_EXTERNAL_STORAGE -> "存储权限"
        else -> permission
    }

    private fun showPermissionDeniedDialog(deniedPermissions: String) {
        AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("以下权限被拒绝，可能会影响应用功能：\n\n$deniedPermissions\n\n点击\"去设置\"在应用详情页面手动开启权限。")
            .setPositiveButton("去设置") { _, _ -> openAppSettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        setupUI()
        setupObservers()
        checkAndRequestPermissions()
        handleIncomingUrl(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUrl(intent)
    }

    /** 后台服务或外部启动带 extra 的链接，读取并提交（修复原链接丢失 Bug）。 */
    private fun handleIncomingUrl(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_TWITTER_URL)
        if (!url.isNullOrEmpty()) {
            viewModel.log("收到链接: $url")
            viewModel.submitUrl(url)
        }
    }

    private fun setupUI() {
        binding.btnDownload.setOnClickListener {
            val link = binding.etLinkInput.text?.toString()?.trim()
            if (link.isNullOrEmpty()) {
                Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.submitUrl(link)
                binding.etLinkInput.text?.clear()
            }
        }

        binding.btnStartMonitor.setOnClickListener { startMonitoringService() }
        binding.btnStopMonitor.setOnClickListener { stopMonitoringService() }
        binding.btnRetryFailed.setOnClickListener { viewModel.retryFailed() }
        binding.btnClearLog.setOnClickListener { viewModel.clearLog() }
        binding.btnOpenFolder.setOnClickListener { openLatestDownload() }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: MainUiState) {
        val task = state.activeTask
        binding.tvVideoTitle.text = task?.let { "推特ID: ${it.tweetId}" } ?: "等待下载..."
        binding.tvDownloadStatus.text = task?.let { "状态: ${it.statusLabel}" } ?: "状态: 空闲"

        if (task?.status == TaskStatus.DOWNLOADING) {
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = task.progress
        } else {
            binding.progressBar.visibility = View.GONE
        }

        state.currentThumbnail?.let { binding.ivThumbnail.setImageBitmap(it) }
        binding.tvQueueCount.text =
            "${state.activeCount}/3 进行中 | 队列: ${state.queueCount} | 失败: ${state.failedCount}"
        binding.tvLog.text = state.logText
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            viewModel.log("权限检查完成（Android 10+ 使用 MediaStore 公共目录，无需存储权限）")
        }
    }

    private fun startMonitoringService() {
        if (isServiceRunning) {
            Toast.makeText(this, "服务已在运行", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ClipboardMonitorService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        viewModel.log("后台监控服务已启动")
    }

    private fun stopMonitoringService() {
        if (!isServiceRunning) {
            Toast.makeText(this, "服务未运行", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ClipboardMonitorService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
        viewModel.log("后台监控服务已停止")
    }

    private fun openLatestDownload() {
        val uriString = viewModel.openLatestDownload()
        if (uriString == null) {
            Toast.makeText(this, "还没有已下载的文件", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开视频"))
        } catch (e: Exception) {
            viewModel.log("打开文件失败: ${e.message}")
            Toast.makeText(this, "打开文件失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        attachClipboardListener()
    }

    override fun onPause() {
        super.onPause()
        detachClipboardListener()
    }

    private fun attachClipboardListener() {
        if (clipboardListener != null) return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                viewModel.log("剪贴板: $text")
                viewModel.onClipboardText(text)
            }
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener!!)
    }

    private fun detachClipboardListener() {
        clipboardListener?.let { clipboardManager.removePrimaryClipChangedListener(it) }
        clipboardListener = null
    }
}
```

- [ ] **Step 2: Manifest 设置 singleTop**

在 `app/src/main/AndroidManifest.xml` 的 MainActivity 标签加上 `android:launchMode="singleTop"`：

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:theme="@style/Theme.TwitterVideoDownloader">
```

- [ ] **Step 3: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "refactor: thin MainActivity onto ViewModel, fix background URL delivery via onNewIntent"
```

---

### Task 9: 重构 ClipboardMonitorService（监听器 + 提交共享 manager）

**Files:**
- Rewrite: `app/src/main/java/com/twitterdownloader/app/ClipboardMonitorService.kt`

- [ ] **Step 1: 重写 Service**

用以下完整内容覆盖 `ClipboardMonitorService.kt`（删除 1 秒轮询，改用 `OnPrimaryClipChangedListener`）：

```kotlin
package com.twitterdownloader.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ClipboardMonitorService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val downloadManager: DownloadManager
        get() = (applicationContext as App).container.downloadManager

    private val channelId = "clipboard_monitor_channel"
    private val notificationId = 1001

    companion object {
        const val ACTION_START = "com.twitterdownloader.app.START_MONITOR"
        const val ACTION_STOP = "com.twitterdownloader.app.STOP_MONITOR"
        private const val TAG = "ClipboardService"
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        startForeground(notificationId, createNotification())
        attachClipboardListener()
        Log.d(TAG, "Clipboard monitoring started")
    }

    private fun stopMonitoring() {
        detachClipboardListener()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Clipboard monitoring stopped")
    }

    private fun attachClipboardListener() {
        if (clipboardListener != null) return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            onClipboardChanged()
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener!!)
    }

    private fun detachClipboardListener() {
        clipboardListener?.let { clipboardManager.removePrimaryClipChangedListener(it) }
        clipboardListener = null
    }

    private fun onClipboardChanged() {
        try {
            val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            if (text.isNullOrEmpty()) return
            Log.d(TAG, "Clipboard changed: $text")
            // 先提交到共享 manager，保证后台也能下载
            val added = downloadManager.submit(text)
            // 有新任务时再拉起界面让用户看到进度
            if (added) notifyMainActivity()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard: ${e.message}")
        }
    }

    private fun notifyMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            action = MainActivity.ACTION_NEW_TWITTER_URL
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "剪贴板监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于后台监控剪贴板中的推特链接"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Twitter Video Downloader")
            .setContentText("正在监控剪贴板中的链接...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止监控", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        detachClipboardListener()
        Log.d(TAG, "Service destroyed")
    }
}
```

- [ ] **Step 2: 验证构建通过**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/twitterdownloader/app/ClipboardMonitorService.kt
git commit -m "refactor: service uses clipboard listener and submits to shared DownloadManager"
```

---

### Task 10: 网络配置 + 清单清理 + 构建加固

**Files:**
- Create: `app/src/main/res/xml/network_security_config.xml`
- Delete: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: 创建 network_security_config.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- 只放行解析/下载涉及的域名；其余默认禁止明文。 -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">vxtwitter.com</domain>
        <domain includeSubdomains="true">twitsor.com</domain>
        <domain includeSubdomains="true">twitter.com</domain>
        <domain includeSubdomains="true">x.com</domain>
        <domain includeSubdomains="true">twimg.com</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 2: 更新 Manifest**

- `<application>`：移除 `android:usesCleartextTraffic="true"`，改为 `android:networkSecurityConfig="@xml/network_security_config"`。
- 删除整个 `<provider>`（FileProvider）块及其 `meta-data`。

修改后的 `<application>`：

```xml
<application
    android:name=".App"
    android:allowBackup="true"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:networkSecurityConfig="@xml/network_security_config"
    android:supportsRtl="true"
    android:theme="@style/Theme.TwitterVideoDownloader">
```

删除后 Manifest 不再包含 FileProvider provider 块。

- [ ] **Step 3: 删除 file_paths.xml**

Run: `rm app/src/main/res/xml/file_paths.xml`
Expected: 文件消失（`git status` 显示 deleted）

- [ ] **Step 4: 更新 build.gradle.kts（R8 + 移除未用依赖）**

- 在 `buildTypes.release` 里把 `isMinifyEnabled = false` 改为 `isMinifyEnabled = true`。
- 移除 `implementation("androidx.constraintlayout:constraintlayout:2.2.0")` 这一行。

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

- [ ] **Step 5: 追加 ViewModel keep 规则**

在 `app/proguard-rules.pro` 末尾追加：

```
# ViewModel 通过反射实例化，R8 需要保留构造函数
-keep class com.twitterdownloader.app.MainViewModel { <init>(android.app.Application); }
```

- [ ] **Step 6: 验证 debug 与 release 构建**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

Run: `./gradlew :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/xml/network_security_config.xml app/src/main/AndroidManifest.xml app/build.gradle.kts app/proguard-rules.pro
git rm app/src/main/res/xml/file_paths.xml
git commit -m "chore: restrict cleartext, remove unused FileProvider/ConstraintLayout, enable R8"
```

---

### Task 11: 更新 README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新"功能特性"与"技术栈"**

替换相应段落，反映新行为：

- 在"后台监控"描述后补充：检测到的链接先提交给共享下载管线，后台也可继续下载。
- "剪贴板自动监测"改为：前台使用系统剪贴板监听器（不再每秒轮询）。
- "实时预览"保持不变。
- 新增一行：**公共目录存储** - 视频保存到系统 Downloads，用户可直接访问。
- "技术栈"表格的"架构"行改为 `MVVM (ViewModel + StateFlow)`。

- [ ] **Step 2: 更新"查看下载"与"文件结构"**

- "查看下载"小节：把保存路径从 `Android/data/com.twitterdownloader.app/files/Movies/TwitterDownloads/` 改为 `系统公共 Downloads 目录`，并说明 Android 10+ 无需存储权限。
- "文件结构"代码块补充新文件：

```
│       ├── java/com/twitterdownloader/app/
│       │   ├── App.kt                     # Application 与 AppContainer
│       │   ├── DownloadManager.kt         # 下载管线（队列/并发/重试）
│       │   ├── MainViewModel.kt           # UI 状态
│       │   ├── MainActivity.kt            # 主界面逻辑
│       │   ├── ClipboardMonitorService.kt # 后台监控服务
│       │   ├── DownloadTask.kt            # 任务模型
│       │   ├── FailedTaskStore.kt         # 失败任务落盘
│       │   ├── network/VideoInfoParser.kt # 多 API 解析
│       │   └── storage/                   # 存储接口与 MediaStore 实现
│       └── test/java/com/twitterdownloader/app/
│           ├── DownloadManagerTest.kt
│           └── network/VideoInfoParserTest.kt
```

- [ ] **Step 3: 更新"工作原理"**

将第 2、4、5 条改为：

```
2. **链接检测** - 前台使用系统剪贴板监听器，后台使用前台服务 + 剪贴板监听器（Android 10+ 对后台剪贴板访问有限制）
4. **文件下载** - 使用 HttpURLConnection 流式下载，带内容类型校验与 HTML 嗅探，避免错误页落盘
5. **存储管理** - Android 10+ 通过 MediaStore 保存到公共 Downloads，无需存储权限；Android 8/9 使用传统公共目录
```

删除"支持断点续传"的表述（实际未实现）。

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: update README to reflect MVVM, MediaStore storage and clipboard listener"
```

---

### Task 12: 全量验证

**Files:**
- 无（只跑命令）

- [ ] **Step 1: 运行全部单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（VideoInfoParserTest 11 个 + DownloadManagerTest 7 个）

- [ ] **Step 2: 构建 debug 与 release**

Run: `./gradlew :app:assembleDebug :app:assembleRelease`
Expected: 两个 `BUILD SUCCESSFUL`

- [ ] **Step 3: 确认 git 状态干净且提交历史完整**

Run: `git status` 与 `git log --oneline`
Expected: 工作区干净，含 Task 1–11 的提交

- [ ] **Step 4: 真机验证清单（需用户在设备上执行）**

1. 复制一个 twitter/x 链接 → 自动入队下载，进度平滑显示，无卡顿/ANR。
2. 启动监控服务 → 在后台复制链接 → 服务提交后自动拉起界面并下载。
3. 下载完成后文件出现在系统 Downloads / 文件管理器，可播放。
4. "打开视频"能定位到最新文件。
5. 断网/错误链接 → 重试 3 次后进入失败列表，"重试失败"可恢复。
6. 旧版行为不再出现：后台监控不再"空通知"。

---

## 设计决策备忘

- **`DownloadTasksState(revision, tasks)`**：`StateFlow` 用结构相等判重，任务对象原地变更不会触发发射，故用递增 `revision` 强制每次发射。这是对 spec 中"tasks StateFlow + 进度节流"的合并实现（进度经 `emitProgressThrottled` 节流到 ≥250ms）。
- **去重范围**：仅对 `QUEUED/PARSING/DOWNLOADING`（`isActive`）去重，失败/已完成可再次提交 —— 比原代码"queue/active/failed 全去重"更合理（失败后可重下），且 `retryFailed` 不受影响。
- **服务不传 URL extra**：服务先 `submit` 到共享 manager，再 `startActivity` 拉前台。Activity 的 `onCreate`/`onNewIntent` 仍保留读取 extra 的路径以兼容外部启动，去重保证幂等。
- **剪贴板**：Activity 与 Service 均用 `OnPrimaryClipChangedListener`，不再每秒轮询；Android 10+ 后台读取剪贴板受平台限制，此为已知约束。
- **保留**：`saveFailedTaskInfo`（迁至 `FailedTaskStore`）、失败重试次数、3 并发上限、多 API 回退顺序。
- **任务顺序**：DownloadManager（Task 5）在 App/AppContainer（Task 6）之前，因为 `AppContainer` 依赖 `DownloadManager`；各任务在各自 Step 中验证 `assembleDebug` 可编译。
