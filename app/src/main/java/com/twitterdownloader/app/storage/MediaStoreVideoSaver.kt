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
            val output = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开输出流")
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
