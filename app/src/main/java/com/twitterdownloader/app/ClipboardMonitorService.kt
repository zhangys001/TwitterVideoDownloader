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
            ACTION_STOP -> stopMonitoring()
            // null：系统因 START_STICKY 在进程被杀后重启服务，按启动处理（startMonitoring 幂等）
            else -> startMonitoring()
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
            Log.d(TAG, "Clipboard changed (${text.length} chars)")
            // 先提交到共享 manager，保证后台也能下载（即使 Activity 无法被拉起也不丢链接）
            val added = downloadManager.submit(text)
            // 有新任务时再拉起界面让用户看到进度（尽力而为）
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
