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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class ClipboardMonitorService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private var lastClipboardContent: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var clipboardRunnable: Runnable? = null

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
        startClipboardCheck()
        Log.d(TAG, "Clipboard monitoring started")
    }

    private fun stopMonitoring() {
        stopClipboardCheck()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Clipboard monitoring stopped")
    }

    private fun startClipboardCheck() {
        clipboardRunnable = object : Runnable {
            override fun run() {
                checkClipboard()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(clipboardRunnable!!)
    }

    private fun stopClipboardCheck() {
        clipboardRunnable?.let {
            handler.removeCallbacks(it)
        }
        clipboardRunnable = null
    }

    private fun checkClipboard() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                if (text != null && text != lastClipboardContent) {
                    lastClipboardContent = text

                    val matcher = twitterPattern.matcher(text)
                    if (matcher.find()) {
                        val twitterUrl = matcher.group()
                        Log.d(TAG, "Found Twitter URL: $twitterUrl")
                        notifyMainActivity(twitterUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard: ${e.message}")
        }
    }

    private fun notifyMainActivity(url: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            action = MainActivity.ACTION_NEW_TWITTER_URL
            putExtra(MainActivity.EXTRA_TWITTER_URL, url)
        }
        startActivity(intent)
    }

    private val twitterPattern = java.util.regex.Pattern.compile(
        "(https?://(mobile\\.)?twitter\\.com/\\w+/status/\\d+|https?://(mobile\\.)?x\\.com/\\w+/status/\\d+)"
    )

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

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
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
        stopClipboardCheck()
        Log.d(TAG, "Service destroyed")
    }
}
