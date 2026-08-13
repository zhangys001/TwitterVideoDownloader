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
