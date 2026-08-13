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
        if (savedInstanceState == null) {
            handleIncomingUrl(intent)
        }
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

        val thumbnail = state.currentThumbnail
        if (thumbnail != null) {
            binding.ivThumbnail.setImageBitmap(thumbnail)
        } else {
            binding.ivThumbnail.setImageResource(R.drawable.ic_video_placeholder)
        }
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
        // 旧设备（API 26-28）MediaStoreVideoSaver 返回 file:// URI，
        // 跨应用打开会抛 FileUriExposedException，这里退化为显示路径。
        if (uriString.startsWith("file://")) {
            val path = Uri.parse(uriString).path ?: uriString
            Toast.makeText(this, "文件已保存到:\n$path", Toast.LENGTH_LONG).show()
            viewModel.log("文件路径: $path")
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
            readClipboardAndNotify()
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener!!)
        // 首次进入读取当前剪贴板，覆盖"先复制链接再打开应用"的场景
        readClipboardAndNotify()
    }

    private fun readClipboardAndNotify() {
        val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrEmpty()) {
            viewModel.log("剪贴板: $text")
            viewModel.onClipboardText(text)
        }
    }

    private fun detachClipboardListener() {
        clipboardListener?.let { clipboardManager.removePrimaryClipChangedListener(it) }
        clipboardListener = null
    }
}
