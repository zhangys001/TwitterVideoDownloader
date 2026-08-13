package com.twitterdownloader.app

import com.twitterdownloader.app.network.VideoInfoParser
import com.twitterdownloader.app.storage.VideoSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
        } catch (e: CancellationException) {
            throw e
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
            runCatching { onTaskFailed?.invoke(task) }
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
        _tasks.update { current ->
            DownloadTasksState(
                revision = current.revision + 1,
                tasks = transform(current.tasks)
            )
        }
    }

    private fun emitLog(message: String) {
        _logEvents.tryEmit(message)
    }
}

class DownloadException(message: String) : Exception(message)
