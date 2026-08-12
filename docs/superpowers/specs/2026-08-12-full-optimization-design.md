# Twitter Video Downloader 全面优化设计方案

- 日期：2026-08-12
- 状态：已确认
- 范围：功能 Bug 修复 + 性能优化 + 完整 MVVM 架构重构 + 存储迁移到公共 Downloads

## 背景与问题清单

当前代码将全部逻辑（UI、队列、网络、日志、下载）集中在单个 765 行的 `MainActivity` 中，
另有一个前台服务 `ClipboardMonitorService` 每秒轮询剪贴板。已确认的问题：

| 严重度 | 问题 |
|--------|------|
| 功能 Bug | 后台服务用 `startActivity` 携带 extra 通知 Activity，但 Activity 从不读取该 extra（无 `onNewIntent`，`onCreate` 不读），后台检测到的链接实际被丢弃 |
| 功能 Bug | `downloadFile` 只校验 `length() > 0`，HTML 错误页会被保存成 `.mp4` |
| 性能 | 下载循环每读 8KB 就 `runOnUiThread` 一次更新进度，大文件产生上万次 UI 消息，有 ANR 风险 |
| 性能 | 日志每次追加都全量重建 `StringBuilder` 并 `setText`，O(n²) |
| 性能 | Activity 与 Service 各有一个 1 秒剪贴板轮询，浪费电量 |
| 健壮性 | `fetchFromApi` / `extractFromDirectPage` 的 `HttpURLConnection` 未 close/disconnect，连接泄漏 |
| 健壮性 | `activeDownloads` / `failedTasks` 为普通 `mutableListOf`，被多个协程并发读写 |
| 健壮性 | `ConcurrentLinkedQueue.size()` 是 O(n) 扫描 |
| 构建 | release 未开启 R8（`isMinifyEnabled = false`） |
| 安全 | 清单 `usesCleartextTraffic="true"` 全局放行明文流量 |
| 依赖 | ConstraintLayout 声明但未使用 |
| 文档 | README 声称支持"断点续传"，实际未实现 |

## 目标架构

应用级容器持有唯一的 `DownloadManager` 单例，作用域不随 Activity 销毁，后台监控触发的下载
不因界面被划掉而中断。

```
Application (AppContainer)
   └── DownloadManager (应用级单例，持有 CoroutineScope + 3 个下载 worker)
          ├── VideoInfoParser          # 多 API 回退解析 (vxtwitter / twitsor / 直连页面)
          ├── VideoSaver (接口)        # MediaStore 存储，可注入测试替身
          └── 输出 StateFlow<任务列表> / SharedFlow<日志事件> / SharedFlow<进度事件>

MainActivity (变薄：UI + 事件转发)
   └── MainViewModel (收集 DownloadManager 状态 → UI State)
   └── ClipboardMonitorService (前台服务，检测 URL → DownloadManager.submit + 拉起 Activity)
```

## 组件设计

### 1. DownloadManager（核心管线）

- 输入：`Channel<DownloadTask>(UNLIMITED)`，**3 个 worker 协程** `for (task in channel)` 串行消费，
  天然并发安全，替代现有 `ConcurrentLinkedQueue` + `mutableList` 混用。
- `submit(url)`：按 tweetId 去重（queue / active / failed 合并查重），入队后唤醒 worker。
- 重试：失败时若 `retryCount < 3`，短延迟（如 2s）后重新入队；否则进 failed 列表并落盘失败信息
  （保留现有 `saveFailedTaskInfo` 逻辑）。
- 状态机：`status` 改用枚举 `TaskStatus { Queued, Parsing, Downloading, Completed, Failed }` + `progress`，
  废弃魔法字符串。
- 进度节流：worker 只更新内存 `task.progress`；由节流收集器向 UI 发事件，规则：
  相邻两次进度事件间隔至少 250ms，且仅当 progress 有变化时发出。
- 日志：`SharedFlow<String>` 逐条发出；UI 端维护上限 200 行的缓冲区，超出丢最旧。
- 作用域：由 AppContainer 创建，`SupervisorJob() + Dispatchers.Default`（网络层内部切 IO），
  与 Activity 生命周期无关。

### 2. VideoInfoParser

- 职责单一：`suspend fun parse(statusUrl: String): VideoInfo?`（`VideoInfo = thumbnailUrl?, videoUrl`）。
- 保留多 API 回退顺序：vxtwitter → twitsor → 直连页面。
- 所有 `HttpURLConnection` 用 `use {}` 保证关闭，显式设置超时。
- 不依赖 UI，可独立单测。

### 3. VideoSaver（MediaStore 存储）

接口 `interface VideoSaver { suspend fun save(task: DownloadTask, videoUrl: String): String? }`
返回保存后的 URI 字符串或 null。默认实现 `MediaStoreVideoSaver`：

- **API 29+**：插入 `MediaStore.Downloads.EXTERNAL_CONTENT_URI`，`IS_PENDING` 标记下载完成后
  `ContentResolver` 更新释放；无需存储权限。
- **API 26–28**：`getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` + `WRITE_EXTERNAL_STORAGE`
  运行时权限（Manifest 已有 `maxSdkVersion="28"` 声明，补运行时申请）。
- 文件名 `twitter_<tweetId>.mp4`，插入前在 MediaStore 查重 `displayName`，冲突时追加时间戳后缀。
- 下载完成后校验：响应 `Content-Type` 非 `video/*`，或文件头字节为 HTML（`<`），判失败并删除残件。

### 4. 服务通信修复

- Manifest：`MainActivity` 设置 `launchMode="singleTop"`。
- Activity 在 `onCreate` 与 `onNewIntent` 均读取 `EXTRA_TWITTER_URL` 并 `downloadManager.submit(url)`。
- Service 检测到 URL：**先 `downloadManager.submit(url)`（保证后台可下载），再 `startActivity`
  （携带 extra，让用户看到进度）**。
- 删除现存的、从未触发的广播接收器；单一可靠路径 `startActivity + onNewIntent`。

### 5. 剪贴板监控

- Activity 内：`clipboardManager.addPrimaryClipChangedListener` 替代 1 秒轮询。
- Service：同样使用 `OnPrimaryClipChangedListener`（省电）。后台剪贴板读取受 Android 10+
  平台限制，监听器在允许读取的场景下严格优于轮询。
- 保留"启动监控 / 停止监控"前台服务入口与通知。

### 6. UI / MainActivity

- 变薄：只负责 inflate binding、按钮事件、收集 ViewModel 状态渲染、剪贴板监听、
  `onNewIntent`/`onCreate` 处理 URL extra。
- "打开文件夹"改为：用 `ACTION_VIEW` + `content://` URI 打开最新下载文件，由系统文件管理器定位；
  不再用 FileProvider 打开目录。
- 移除 `runOnUiThread` 包装（ViewModel 状态收集天然在主线程）。

### 7. 构建配置与文档

- `app/build.gradle.kts`：release 开启 `isMinifyEnabled = true`，加 R8 keep 规则（保持 proguard-rules.pro）。
- 移除未使用的 ConstraintLayout 依赖。
- 清单移除 `android:usesCleartextTraffic="true"`，新增 `network_security_config.xml` 精确放行
  vxtwitter / twitsor 等解析域名（API 均为 https）。
- README 修正：存储路径更新为公共 Downloads、删除"断点续传"等失实描述、更新架构说明。

## 错误处理

- 解析失败：按 API 顺序回退，全部失败返回 null，任务按重试策略处理。
- 网络异常：统一在 DownloadManager 层捕获，转入重试或 failed 列表，UI 显示失败原因。
- 存储失败（MediaStore 不可用、权限拒绝）：任务进入 failed，错误信息落盘。
- 下载校验失败（内容类型 / HTML 嗅探）：视为失败，删除残件。

## 测试策略

- 单元测试（JVM，不依赖设备）：
  - `VideoInfoParser`：URL 提取与正则逻辑（对构造的 HTML/JSON 响应做解析断言）。
  - `DownloadManager`：去重、重试次数上限、队列并发上限（注入假的 Parser/Saver）。
- 真机验证清单：
  - 复制链接自动下载，进度平滑显示，不卡顿不 ANR。
  - 后台监控：检测到链接后，即使界面不在前台也能完成下载。
  - 下载文件出现在系统 Downloads / 文件管理器中，可打开播放。
  - "打开文件夹"定位到最新文件。

## 验收标准

1. 后台服务检测到的链接能可靠触发下载（原 Bug 修复）。
2. 大文件下载期间 UI 无卡顿/ANR，进度平滑。
3. 视频保存到公共 Downloads，无需存储权限（Android 10+）。
4. 错误页/无效内容不会落盘为 `.mp4`。
5. 所有 `HttpURLConnection` 正确关闭，无连接泄漏。
6. release 构建开启 R8，`assembleRelease` 通过。
7. 单测通过；真机验证清单全部通过。
