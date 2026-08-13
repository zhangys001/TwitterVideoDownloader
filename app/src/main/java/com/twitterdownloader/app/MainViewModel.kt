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
    val logText: String = "",
    val lastSavedUri: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val manager: DownloadManager = (app as App).container.downloadManager

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val logBuffer = ArrayDeque<String>()
    private val maxLogLines = 200

    private var lastActiveTaskId: String? = null
    private var lastThumbnailTaskId: String? = null
    private var lastClipboardText: String? = null

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
        if (text == lastClipboardText) return
        lastClipboardText = text
        manager.submit(text)
    }

    fun retryFailed() = manager.retryFailed()

    fun clearLog() {
        synchronized(logBuffer) { logBuffer.clear() }
        _uiState.update { it.copy(logText = "") }
    }

    /** 返回最近一个已下载文件的 URI；无则返回 null。 */
    fun openLatestDownload(): String? = _uiState.value.lastSavedUri

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
                failedCount = tasks.count { t -> t.status == TaskStatus.FAILED },
                lastSavedUri = tasks.lastOrNull { t -> t.status == TaskStatus.COMPLETED }?.savedUri
            )
        }
        loadThumbnailIfNeeded()
    }

    private fun loadThumbnailIfNeeded() {
        val task = _uiState.value.activeTask ?: return
        val url = task.thumbnailUrl ?: return
        if (lastThumbnailTaskId == task.tweetId) return
        lastThumbnailTaskId = task.tweetId
        val targetTaskId = task.tweetId
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = loadBitmap(url)
            withContext(Dispatchers.Main) {
                // 仅当该任务仍是当前展示任务时才应用缩略图，避免旧任务覆盖新任务
                if (_uiState.value.activeTask?.tweetId == targetTaskId) {
                    _uiState.update { it.copy(currentThumbnail = bitmap) }
                }
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
