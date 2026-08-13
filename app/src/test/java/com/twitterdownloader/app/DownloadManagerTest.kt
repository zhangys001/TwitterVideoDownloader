package com.twitterdownloader.app

import com.twitterdownloader.app.network.VideoInfo
import com.twitterdownloader.app.network.VideoInfoParser
import com.twitterdownloader.app.storage.VideoSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 创建一个挂在测试调度器上、但不作为 TestScope 子任务的 scope。
 * 这样 DownloadManager 的 worker 协程既能被 advanceUntilIdle 驱动（前台任务），
 * 又不会在 runTest 结束时因仍是 TestScope 的活动子任务而抛出 UncompletedCoroutinesError。
 */
private fun TestScope.workerScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    private class FakeParser(var videoInfo: VideoInfo? = VideoInfo("https://cdn.example.com/v.mp4", null)) :
        VideoInfoParser() {
        override suspend fun parse(statusUrl: String): VideoInfo? = videoInfo
    }

    private class FakeSaver(var failEveryTime: Boolean = false) : VideoSaver {
        var calls = 0
        var currentConcurrent = 0
        var maxConcurrentSeen = 0

        override suspend fun save(task: DownloadTask, videoUrl: String, onProgress: (Int) -> Unit): String? {
            calls++
            if (failEveryTime) throw RuntimeException("网络错误")
            currentConcurrent++
            if (currentConcurrent > maxConcurrentSeen) maxConcurrentSeen = currentConcurrent
            delay(1000) // 模拟 IO 耗时，让并发测试能观察到并行
            currentConcurrent--
            task.progress = 100
            return "content://fake/$calls"
        }
    }

    @Test
    fun submit_deduplicatesSameTweet() = runTest {
        val manager = DownloadManager(FakeParser(), FakeSaver(), workerScope())
        manager.submit("https://twitter.com/a/status/111")
        manager.submit("https://twitter.com/a/status/111")
        manager.submit("https://x.com/a/status/111")
        advanceUntilIdle()
        assertEquals(1, manager.tasks.value.tasks.size)
    }

    @Test
    fun submit_ignoresNonTwitterText() = runTest {
        val manager = DownloadManager(FakeParser(), FakeSaver(), workerScope())
        val added = manager.submit("hello world no link here")
        advanceUntilIdle()
        assertEquals(false, added)
        assertEquals(0, manager.tasks.value.tasks.size)
    }

    @Test
    fun download_completesAndSavesUri() = runTest {
        val manager = DownloadManager(FakeParser(), FakeSaver(), workerScope())
        manager.submit("https://twitter.com/a/status/222")
        advanceUntilIdle()
        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals("content://fake/1", task.savedUri)
        assertEquals(100, task.progress)
    }

    @Test
    fun failedDownload_retriesUpToMaxThenFails() = runTest {
        val saver = FakeSaver(failEveryTime = true)
        val manager = DownloadManager(FakeParser(), saver, workerScope(), retryDelayMs = 0)
        manager.submit("https://twitter.com/a/status/333")
        advanceUntilIdle()
        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(DownloadTask.MAX_RETRIES, task.retryCount)
        assertEquals(DownloadTask.MAX_RETRIES, saver.calls)
    }

    @Test
    fun parserReturningNull_failsWithoutSaverCall() = runTest {
        val saver = FakeSaver()
        val manager = DownloadManager(FakeParser(videoInfo = null), saver, workerScope(), retryDelayMs = 0)
        manager.submit("https://twitter.com/a/status/444")
        advanceUntilIdle()
        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(0, saver.calls)
    }

    @Test
    fun maxParallel_boundsConcurrentDownloads() = runTest {
        val saver = FakeSaver()
        val manager = DownloadManager(FakeParser(), saver, workerScope(), maxParallel = 2, retryDelayMs = 0)
        repeat(5) { i -> manager.submit("https://twitter.com/a/status/1$i") }
        advanceUntilIdle()
        assertEquals(5, manager.tasks.value.tasks.size)
        assertEquals(2, saver.maxConcurrentSeen)
    }

    @Test
    fun retryFailed_requeuesFailedTasks() = runTest {
        val saver = FakeSaver(failEveryTime = true)
        val manager = DownloadManager(FakeParser(), saver, workerScope(), retryDelayMs = 0)
        manager.submit("https://twitter.com/a/status/555")
        advanceUntilIdle()
        assertEquals(TaskStatus.FAILED, manager.tasks.value.tasks.single().status)

        saver.failEveryTime = false // 让后续重试成功
        manager.retryFailed()
        advanceUntilIdle()

        val task = manager.tasks.value.tasks.single()
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals(0, task.retryCount)
    }
}
