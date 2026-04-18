package com.twitterdownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.twitterdownloader.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var clipboardManager: ClipboardManager
    private var lastClipboardContent: String? = null
    private var isMonitoring = false
    private var isServiceRunning = false

    companion object {
        const val ACTION_NEW_TWITTER_URL = "com.twitterdownloader.app.NEW_TWITTER_URL"
        const val EXTRA_TWITTER_URL = "twitter_url"
    }

    private val downloadQueue = ConcurrentLinkedQueue<DownloadTask>()
    private val failedTasks = mutableListOf<DownloadTask>()
    private val activeDownloads = mutableListOf<DownloadTask>()
    private val maxParallelDownloads = 3

    private val logMessages = StringBuilder()
    private val clipboardCheckHandler = Handler(Looper.getMainLooper())
    private var clipboardCheckRunnable: Runnable? = null

    private val twitterPattern = Pattern.compile(
        "(https?://(mobile\\.)?twitter\\.com/\\w+/status/\\d+|https?://(mobile\\.)?x\\.com/\\w+/status/\\d+)"
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startClipboardMonitoring()
        } else {
            Toast.makeText(this, "需要权限才能正常运行", Toast.LENGTH_SHORT).show()
        }
    }

    data class DownloadTask(
        val url: String,
        val tweetId: String,
        var retryCount: Int = 0,
        var status: String = "等待中",
        var progress: Int = 0,
        var thumbnailPath: String? = null,
        var savedPath: String? = null,
        var errorMessage: String? = null
    ) {
        val maxRetries = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        checkAndRequestPermissions()

        // 注册广播接收器
        val filter = android.content.IntentFilter(ACTION_NEW_TWITTER_URL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, filter)
        }

        // 检查服务是否在运行
        checkServiceStatus()
    }

    private fun checkServiceStatus() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service.className == ClipboardMonitorService::class.java.name) {
                isServiceRunning = true
                isMonitoring = true
                break
            }
        }
    }

    private fun setupUI() {
        binding.btnDownload.setOnClickListener {
            val link = binding.etLinkInput.text?.toString()?.trim()
            if (!link.isNullOrEmpty()) {
                processTwitterLink(link)
                binding.etLinkInput.text?.clear()
            } else {
                Toast.makeText(this, "请输入链接", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStartMonitor.setOnClickListener {
            startClipboardMonitoringService()
            Toast.makeText(this, "已开始后台监控", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopMonitor.setOnClickListener {
            stopClipboardMonitoringService()
            Toast.makeText(this, "已停止后台监控", Toast.LENGTH_SHORT).show()
        }

        binding.btnRetryFailed.setOnClickListener {
            retryFailedDownloads()
        }

        binding.btnClearLog.setOnClickListener {
            synchronized(logMessages) {
                logMessages.clear()
                binding.tvLog.text = ""
            }
        }

        binding.btnOpenFolder.setOnClickListener {
            openDownloadFolder()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        // 使用应用私有目录存储
        val downloadDir = getDownloadDir()
        logMessage("下载目录: $downloadDir")

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startClipboardMonitoring()
        }
    }

    private fun getDownloadDir(): File {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TwitterDownloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun startClipboardMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        clipboardCheckRunnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    checkClipboard()
                    clipboardCheckHandler.postDelayed(this, 1000)
                }
            }
        }
        clipboardCheckHandler.post(clipboardCheckRunnable!!)
        logMessage("剪贴板监控已启动")
    }

    private fun stopClipboardMonitoring() {
        isMonitoring = false
        clipboardCheckRunnable?.let {
            clipboardCheckHandler.removeCallbacks(it)
        }
        clipboardCheckRunnable = null
        logMessage("剪贴板监控已停止")
    }

    private fun startClipboardMonitoringService() {
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
        isMonitoring = true
        logMessage("后台监控服务已启动")
    }

    private fun stopClipboardMonitoringService() {
        if (!isServiceRunning) {
            Toast.makeText(this, "服务未运行", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ClipboardMonitorService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
        isMonitoring = false
        logMessage("后台监控服务已停止")
    }

    private val broadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_NEW_TWITTER_URL) {
                val url = intent.getStringExtra(EXTRA_TWITTER_URL)
                if (!url.isNullOrEmpty()) {
                    logMessage("后台服务检测到链接: $url")
                    processTwitterLink(url)
                }
            }
        }
    }

    private fun checkClipboard() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (text != null && text != lastClipboardContent) {
                    lastClipboardContent = text
                    logMessage("剪贴板: $text")
                    processTwitterLink(text)
                }
            }
        } catch (e: Exception) {
            // 静默处理
        }
    }

    private fun processTwitterLink(text: String) {
        val matcher = twitterPattern.matcher(text)
        if (matcher.find()) {
            val twitterUrl = matcher.group()
            val tweetId = extractTweetId(twitterUrl)

            val existingTask = downloadQueue.find { it.tweetId == tweetId }
                ?: activeDownloads.find { it.tweetId == tweetId }
                ?: failedTasks.find { it.tweetId == tweetId }

            if (existingTask != null) {
                logMessage("已在队列中: $tweetId")
                return
            }

            val task = DownloadTask(url = twitterUrl, tweetId = tweetId)
            downloadQueue.offer(task)
            logMessage("加入队列: $tweetId")

            updateQueueDisplay()
            processNextInQueue()
        }
    }

    private fun processNextInQueue() {
        while (activeDownloads.size < maxParallelDownloads && downloadQueue.isNotEmpty()) {
            val task = downloadQueue.poll() ?: break
            activeDownloads.add(task)
            startDownload(task)
        }
        updateQueueDisplay()
    }

    private fun startDownload(task: DownloadTask) {
        lifecycleScope.launch {
            try {
                task.status = "获取信息..."
                updateTaskDisplay(task)
                logMessage("下载: ${task.tweetId}")

                val videoInfo = extractVideoInfo(task.url)
                if (videoInfo != null && videoInfo.second.isNotEmpty()) {
                    task.thumbnailPath = videoInfo.first
                    val videoUrl = videoInfo.second

                    if (task.thumbnailPath != null) {
                        loadThumbnail(task.thumbnailPath!!)
                    }

                    task.status = "下载中..."
                    updateTaskDisplay(task)
                    logMessage("开始下载视频...")

                    val fileName = "twitter_${task.tweetId}.mp4"
                    val result = downloadFile(videoUrl, fileName, task)

                    if (result != null && result.exists() && result.length() > 0) {
                        task.status = "已完成"
                        task.savedPath = result.absolutePath
                        task.progress = 100
                        logMessage("下载完成: ${result.name}")
                        updateTaskDisplay(task)
                        Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_SHORT).show()
                    } else {
                        handleDownloadFailure(task, "文件无效")
                    }
                } else {
                    handleDownloadFailure(task, "无法获取视频地址")
                }
            } catch (e: Exception) {
                task.errorMessage = e.message
                logMessage("失败: ${e.message}")
                handleDownloadFailure(task, e.message ?: "未知错误")
            } finally {
                activeDownloads.remove(task)
                updateQueueDisplay()
                processNextInQueue()
            }
        }
    }

    private fun handleDownloadFailure(task: DownloadTask, reason: String) {
        task.retryCount++
        if (task.retryCount < task.maxRetries) {
            task.status = "重试${task.retryCount}/${task.maxRetries}"
            logMessage("失败，第${task.retryCount}次重试")
            downloadQueue.offer(task)
        } else {
            task.status = "失败"
            logMessage("已达最大重试次数: $reason")
            failedTasks.add(task)
            saveFailedTaskInfo(task)
        }
        updateQueueDisplay()
    }

    private suspend fun extractVideoInfo(statusUrl: String): Pair<String?, String>? = withContext(Dispatchers.IO) {
        try {
            val tweetId = extractTweetId(statusUrl)
            logMessage("解析推特ID: $tweetId")

            // 方法1: 尝试 vxtwitter API
            try {
                val apiUrl = "https://api.vxtwitter.com/twitter/status/$tweetId"
                val (videoUrl, thumbnailUrl) = fetchFromApi(apiUrl)
                if (videoUrl.isNotEmpty()) {
                    logMessage("vxtwitter API成功")
                    return@withContext Pair(thumbnailUrl.takeIf { it.isNotEmpty() }, videoUrl)
                }
            } catch (e: Exception) {
                logMessage("vxtwitter失败: ${e.message}")
            }

            // 方法2: 尝试 twitsor API
            try {
                val apiUrl = "https://twitsor.com/api/twitter-video?url=$statusUrl"
                val (videoUrl, thumbnailUrl) = fetchFromApi(apiUrl)
                if (videoUrl.isNotEmpty()) {
                    logMessage("twitsor API成功")
                    return@withContext Pair(thumbnailUrl.takeIf { it.isNotEmpty() }, videoUrl)
                }
            } catch (e: Exception) {
                logMessage("twitsor失败: ${e.message}")
            }

            // 方法3: 尝试直接获取 (这个通常不工作但尝试一下)
            try {
                val result = extractFromDirectPage(statusUrl)
                if (result != null) {
                    logMessage("直接解析成功")
                    return@withContext result
                }
            } catch (e: Exception) {
                logMessage("直接解析失败: ${e.message}")
            }

            logMessage("所有方法均失败")
            return@withContext null
        } catch (e: Exception) {
            logMessage("解析异常: ${e.message}")
            return@withContext null
        }
    }

    private suspend fun fetchFromApi(apiUrl: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val responseCode = connection.responseCode
        logMessage("API响应码: $responseCode")

        if (responseCode == 200) {
            val response = connection.inputStream.bufferedReader().readText()
            logMessage("API响应长度: ${response.length}")

            // 尝试解析JSON
            try {
                val json = JSONTokener(response).nextValue() as JSONObject

                val videoUrl = json.optString("url", "")
                    .ifEmpty { json.optString("video_url", "") }
                    .ifEmpty { json.optString("content_url", "") }
                    .ifEmpty { json.optString("download_url", "") }

                val thumbnailUrl = json.optString("thumbnail_url", "")
                    .ifEmpty { json.optString("poster", "") }
                    .ifEmpty { json.optString("thumb", "") }

                if (videoUrl.isNotEmpty()) {
                    return@withContext Pair(videoUrl, thumbnailUrl)
                }

                // 如果JSON中没有，尝试从原始响应中提取
                val videoPatterns = listOf(
                    Pattern.compile("(https?://[^\\s\"',<>]+\\.mp4[^\\s\"',<>]*)"),
                    Pattern.compile("\"url\":\"([^\"]+\\.mp4[^\"]*)\""),
                    Pattern.compile("video_url[\":]+(https?://[^\",\\s]+)")
                )

                for (pattern in videoPatterns) {
                    val matcher = pattern.matcher(response)
                    if (matcher.find()) {
                        val foundUrl = (matcher.group(1) ?: matcher.group(0) ?: "")
                            .replace("\\u002F", "/")
                            .replace("\\/", "/")
                            .replace("\"", "")
                        if (foundUrl.contains(".mp4") || foundUrl.contains("video")) {
                            return@withContext Pair(foundUrl, "")
                        }
                    }
                }
            } catch (e: Exception) {
                logMessage("JSON解析失败: ${e.message}")
                // 可能是纯文本响应，直接返回
                if (response.contains(".mp4") || response.contains("video")) {
                    val mp4Match = Pattern.compile("(https?://[^\\s\"']+\\.mp4[^\"']*)").matcher(response)
                    if (mp4Match.find()) {
                        return@withContext Pair(mp4Match.group(1) ?: "", "")
                    }
                }
            }
        }

        return@withContext Pair("", "")
    }

    private suspend fun extractFromDirectPage(pageUrl: String): Pair<String?, String>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(pageUrl.replace("mobile.", "").replace("x.com", "twitter.com"))
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode
            logMessage("直接访问响应码: $responseCode")

            if (responseCode == 200) {
                val content = connection.inputStream.bufferedReader().readText()

                val videoPatterns = listOf(
                    Pattern.compile("video_url[\":]+(https?://[^\",\\s]+)"),
                    Pattern.compile("\"url\":\"(https?://[^\"]+\\.mp4[^\"]*)\""),
                    Pattern.compile("(https://video[^\"'>\\s]+\\.mp4[^\"'>\\s]*)")
                )

                for (pattern in videoPatterns) {
                    val matcher = pattern.matcher(content)
                    if (matcher.find()) {
                        val videoUrl = (matcher.group(1) ?: "")
                            .replace("\\u002F", "/")
                            .replace("\\/", "/")
                        if (videoUrl.contains("video") || videoUrl.contains(".mp4")) {
                            return@withContext Pair(null, videoUrl)
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            logMessage("直接解析异常: ${e.message}")
            null
        }
    }

    private suspend fun loadThumbnail(thumbnailUrl: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(thumbnailUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val bitmap = BitmapFactory.decodeStream(connection.inputStream)
            if (bitmap != null) {
                withContext(Dispatchers.Main) {
                    binding.ivThumbnail.setImageBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private suspend fun downloadFile(videoUrl: String, fileName: String, task: DownloadTask): File? = withContext(Dispatchers.IO) {
        try {
            logMessage("下载地址: $videoUrl")

            val url = URL(videoUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Referer", "https://twitter.com/")
            connection.connectTimeout = 60000
            connection.readTimeout = 60000

            val downloadDir = getDownloadDir()
            val file = File(downloadDir, fileName)

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(file)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            val fileSize = connection.contentLength.toLong()

            logMessage("文件大小: ${if(fileSize > 0) "${fileSize/1024}KB" else "未知"}")

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (fileSize > 0) {
                    val progress = ((totalBytesRead * 100) / fileSize).toInt()
                    task.progress = progress
                    task.status = "下载 $progress%"
                    updateTaskDisplay(task)
                }
            }

            outputStream.close()
            inputStream.close()

            logMessage("下载完成，大小: ${file.length()/1024}KB")
            return@withContext file
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                logMessage("下载异常: ${e.message}")
            }
            null
        }
    }

    private fun saveFailedTaskInfo(task: DownloadTask) {
        try {
            val failedDir = File(filesDir, "failed_downloads")
            if (!failedDir.exists()) failedDir.mkdirs()

            val infoFile = File(failedDir, "${task.tweetId}.json")
            val json = JSONObject().apply {
                put("url", task.url)
                put("tweetId", task.tweetId)
                put("errorMessage", task.errorMessage ?: "未知错误")
                put("retryCount", task.retryCount)
                put("timestamp", System.currentTimeMillis())
            }

            infoFile.writeText(json.toString())
        } catch (e: Exception) {
            logMessage("保存失败信息失败: ${e.message}")
        }
    }

    private fun retryFailedDownloads() {
        if (failedTasks.isEmpty()) {
            Toast.makeText(this, "没有失败的任务", Toast.LENGTH_SHORT).show()
            return
        }

        logMessage("重试 ${failedTasks.size} 个任务")
        val tasksToRetry = failedTasks.toList()
        failedTasks.clear()

        tasksToRetry.forEach { task ->
            task.retryCount = 0
            task.errorMessage = null
            downloadQueue.offer(task)
        }

        updateQueueDisplay()
        processNextInQueue()
    }

    private fun openDownloadFolder() {
        try {
            val downloadDir = getDownloadDir()
            Toast.makeText(this, "下载目录:\n$downloadDir", Toast.LENGTH_LONG).show()
            logMessage("下载目录: $downloadDir")

            // 尝试通过文件管理器打开
            try {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", downloadDir)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "打开文件夹"))
            } catch (e: Exception) {
                logMessage("FileProvider打开失败: ${e.message}")
                // 尝试直接打开
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(downloadDir), "resource/folder")
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    logMessage("无法打开文件夹")
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "打开文件夹失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTaskDisplay(task: DownloadTask) {
        runOnUiThread {
            binding.tvVideoTitle.text = "推特ID: ${task.tweetId}"
            binding.tvDownloadStatus.text = "状态: ${task.status}"
            if (task.status.startsWith("下载")) {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = task.progress
            } else {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateQueueDisplay() {
        runOnUiThread {
            val activeCount = activeDownloads.size
            val queueCount = downloadQueue.size
            val failedCount = failedTasks.size
            binding.tvQueueCount.text = "$activeCount/$maxParallelDownloads 进行中 | 队列: $queueCount | 失败: $failedCount"
        }
    }

    private fun extractTweetId(url: String): String {
        val pattern = Pattern.compile("status/(\\d+)")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) (matcher.group(1) ?: url.hashCode().toString()) else url.hashCode().toString()
    }

    private fun logMessage(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        runOnUiThread {
            synchronized(logMessages) {
                logMessages.append("[$timestamp] $message\n")
                binding.tvLog.text = logMessages.toString()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isMonitoring) {
            checkClipboard()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardCheckRunnable?.let {
            clipboardCheckHandler.removeCallbacks(it)
        }
        try {
            unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }
}
