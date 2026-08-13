# Twitter Video Downloader

一个简洁高效的 Android 推特视频下载器，支持自动监测剪贴板中的链接并批量下载视频。

## 功能特性

- **后台监控** - 使用前台服务持续监控剪贴板，应用在后台时也能自动检测链接。检测到的链接会先提交给共享下载管线，即使界面不在前台也能继续下载
- **剪贴板自动监测** - 前台使用系统剪贴板监听器自动检测链接（不再每秒轮询）
- **公共目录存储** - 视频保存到系统公共 Downloads 目录，Android 10+ 无需存储权限，可直接在相册/文件管理器中访问
- **多平台支持** - 同时支持 twitter.com 和 x.com 链接
- **多API fallback** - 内置 vxtwitter、twitsor 等多个视频解析源，自动尝试直到成功
- **并发下载** - 最多同时下载 3 个视频，队列式管理
- **自动重试** - 下载失败自动重试最多 3 次
- **实时预览** - 显示视频缩略图和下载进度
- **下载日志** - 实时显示详细下载状态和日志信息

## 技术栈

| 分类 | 技术 |
|------|------|
| 语言 | Kotlin |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 15 (API 35) |
| UI 框架 | Material Design 3 |
| 异步处理 | Kotlin Coroutines |
| 架构 | MVVM (ViewModel + StateFlow) |
| 后台服务 | Foreground Service |

## 依赖库

- AndroidX Core KTX
- AndroidX AppCompat
- Material Components
- AndroidX Lifecycle (ViewModel + Coroutines)
- AndroidX Activity/Fragment KTX
- Kotlinx Coroutines Android

## 构建

### 环境要求

- Android Studio Hedgehog (2024.1.1) 或更高版本
- JDK 17
- Android SDK 35

### 构建步骤

1. 克隆项目到本地
2. 用 Android Studio 打开项目目录
3. 等待 Gradle 同步完成
4. 连接 Android 设备或启动模拟器
5. 点击 Run (Shift + F10) 构建并运行

### 构建 APK

```bash
./gradlew assembleDebug

# APK 输出位置
app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

### 基本使用

1. 首次启动会请求必要权限（存储、通知）
2. 在输入框粘贴推特链接，点击"下载"按钮
3. 或直接复制链接，应用会自动监测并开始下载

### 后台监控功能

- 点击"启动监控"启动后台前台服务
- 服务启动后会显示一个永久通知
- 即使应用在后台或屏幕关闭，剪贴板监控依然生效
- 检测到推特链接会自动打开应用并开始下载
- 点击通知中的"停止监控"按钮可停止服务

### 队列管理

- 下载队列自动管理，最多 3 个并行下载
- 失败的任务会保留在队列中
- 点击"重试失败"重新尝试下载失败的任务

### 查看下载

- 点击"打开文件夹"可打开最新下载的视频（Android 8/9 仅显示文件路径）
- 视频保存到系统公共 Downloads 目录，Android 10+ 无需存储权限（Android 8/9 使用传统公共目录）

## 文件结构

```
TwitterVideoDownloader/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/twitterdownloader/app/
│       │   │   ├── App.kt                     # Application 与 AppContainer
│       │   │   ├── DownloadManager.kt         # 下载管线（队列/并发/重试）
│       │   │   ├── MainViewModel.kt           # UI 状态
│       │   │   ├── MainActivity.kt            # 主界面逻辑
│       │   │   ├── ClipboardMonitorService.kt # 后台监控服务
│       │   │   ├── DownloadTask.kt            # 任务模型
│       │   │   ├── FailedTaskStore.kt         # 失败任务落盘
│       │   │   ├── network/VideoInfoParser.kt # 多 API 解析
│       │   │   └── storage/                   # 存储接口与 MediaStore 实现
│       │   └── res/
│       │       ├── layout/
│       │       │   └── activity_main.xml        # 界面布局
│       │       ├── values/
│       │       │   ├── strings.xml              # 字符串资源
│       │       │   └── colors.xml               # 颜色定义
│       │       └── drawable/                    # 图标资源
│       └── test/
│           └── java/com/twitterdownloader/app/
│               ├── DownloadManagerTest.kt
│               └── network/VideoInfoParserTest.kt
├── build.gradle.kts                         # 项目构建配置
├── settings.gradle.kts                      # Gradle 设置
└── gradle.properties                        # Gradle 属性
```

## 工作原理

1. **后台服务** - 使用 Foreground Service 在后台持续运行，绑定通知确保不被系统杀死
2. **链接检测** - 前台使用系统剪贴板监听器，后台使用前台服务 + 剪贴板监听器（Android 10+ 对后台剪贴板访问有限制）
3. **视频解析** - 通过第三方 API (vxtwitter/twitsor) 获取视频直链
4. **文件下载** - 使用 HttpURLConnection 流式下载，带内容类型校验与 HTML 嗅探，避免错误页落盘
5. **存储管理** - Android 10+ 通过 MediaStore 保存到公共 Downloads，无需存储权限；Android 8/9 使用传统公共目录

## 注意事项

- 本应用仅供个人学习交流使用
- 请尊重版权，下载内容仅供个人使用
- 推特视频解析依赖第三方 API，服务可能不稳定
- 后台监控会显示一个常驻通知，这是 Android 系统的要求

## License

MIT License
