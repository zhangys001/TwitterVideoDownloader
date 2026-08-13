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
