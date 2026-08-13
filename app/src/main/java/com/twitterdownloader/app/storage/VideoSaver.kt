package com.twitterdownloader.app.storage

import com.twitterdownloader.app.DownloadTask

interface VideoSaver {
    /**
     * 下载视频并保存，返回保存后的 URI 字符串；失败抛出异常或返回 null。
     * @param onProgress 进度回调（0-100），实现方可在此节流调用频率。
     */
    suspend fun save(task: DownloadTask, videoUrl: String, onProgress: (Int) -> Unit): String?
}
