package com.twitterdownloader.app

import android.app.Application
import android.content.Context
import com.twitterdownloader.app.network.VideoInfoParser
import com.twitterdownloader.app.storage.MediaStoreVideoSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            parser = VideoInfoParser(),
            saver = MediaStoreVideoSaver(context.applicationContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onTaskFailed = { task -> FailedTaskStore(context.applicationContext).save(task) }
        )
    }
}
