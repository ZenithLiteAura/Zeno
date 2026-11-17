# ClipNest 1.1 下载模块 · 开发契约（全员必读）

> 目标：把 `I:\DeepseekHarness\01_projects\web_demos\hellotik-douyin-clone` 的后端能力**原生移植进 App**
> （不跑 Python 服务、不嵌 WebView），并在 dock 增加「下载」页；同时把 UI 统一到 MiUiX/Nexio 风格。

工程根：`I:\DeepseekHarness\01_projects\ClipNestAndroid`（Kotlin + Compose + MiUiX 0.9.4，AGP 9.2.1）

## 0. 构建命令（必须这样，别的写法会失败）

```bash
cd /i/DeepseekHarness/01_projects/ClipNestAndroid
export JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
export ANDROID_HOME='I:\DeepseekHarness\05_toolchain\nexio-build\android-sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME='I:\DeepseekHarness\99_other_file\android-user-home'
export GRADLE_USER_HOME='I:\DeepseekHarness\05_toolchain\nexio-build\gradle-home'
export TMP='I:\DeepseekHarness\99_other_file\tmp\gtmp'; export TEMP="$TMP"
powershell.exe -NoProfile -Command "Set-Location 'I:\DeepseekHarness\01_projects\ClipNestAndroid'; & .\gradlew.bat :core:test :app:assembleDebug --console=plain 2>&1 | Select-Object -Last 20"
```
（用 `win_bash` 工具；`pwsh` 工具可能报 schema 错。依赖只走阿里云镜像，别联网找 Google/GitHub。
`cmd //c` 在本机会掉进交互 shell，**不要用**。）

## 1. Lead 已经做完的部分（不要重复改）

- `core/.../Model.kt`：`AppSettings` 新增 `autoDownload("off"|"images"|"all")` / `zipNamingRule("1".."4")` / `downloadWifiOnly(true)`
- `core/.../SettingsStore.kt`：上述字段读写 + 夹取
- `AndroidManifest.xml`：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE_DATA_SYNC`、`WRITE_EXTERNAL_STORAGE(maxSdk28)`、`DownloadService(foregroundServiceType=dataSync)`
- `app/data/DownloadContracts.kt`（冻结）：`DownloadWorkUi` / `DownloadTaskUi` / `interface DownloadController` / `StubDownloadController`
- `ui/dock/ClipNestDock.kt`：胶囊 tab 改为「收件箱 / 下载」
- `ui/ClipHomeScreen.kt`：内部路由 0 收件箱 / 1 下载 / 2 设置 / 3 识别规则；`ClipHomeScreen(..., downloads: DownloadController)`
- `ui/SettingsPage.kt`：新增 `onOpenRules` 参数 + 「识别规则」入口行
- `ui/download/DownloadPage.kt`：占位（**签名冻结**：`DownloadPage(controller, settings, actions, modifier, onToast)`）

## 2. 解析器契约（core-svc 实现，host-shell 直接调用）

放 `core/src/main/kotlin/com/clipnest/core/douyin/`：

```kotlin
data class DouyinWork(
    val ok: Boolean,
    val awemeId: String = "",
    val type: String = "",        // image | video
    val title: String = "",
    val authorName: String = "",
    val pics: List<String> = emptyList(),
    val videoUrl: String = "",
    val videoQuality: String = "", // 例如 1080p；拿不到留空
    val videoSize: Long = 0,
    val coverUrl: String = "",
    val source: String = "",       // iesdouyin | seo
    val code: String = "",         // not_found|parse_failed|timeout|network|no_media
    val message: String = "",
)

object DouyinLinks {
    fun extractUrl(text: String): String?
    fun extractAwemeId(text: String): String?
    fun isShortLink(url: String): Boolean
    fun kindHint(text: String): String?     // video | note
}

class DouyinResolver {
    fun resolve(textOrUrl: String, budgetMs: Long = 60_000): DouyinWork
}
```

照 `src/resolver/resolve.py`（1134 行）逐段移植，**关键坑一个都不能丢**：

| 事项 | 值 |
|---|---|
| 移动 UA（分享页必须） | `Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1` |
| 桌面 UA（媒体兜底） | Chrome/124 Windows |
| 分享页 | `https://www.iesdouyin.com/share/{video|note}/{awemeId}/` |
| 必须同 Session 连发 | 首次只种 `ttwid`，要 2~5 次才出完整 SSR；**最多 6 次，间隔 2s**，Cookie 用 `java.net.CookieManager` 复用 |
| 数据位置 | `window._ROUTER_DATA` → `loaderData[*][*].item_list[0]`；失败再递归找「有 aweme_id 且含 video/images」的对象 |
| JSON 提取 | **花括号配对扫描**（字符串/转义要跳过），不要用正则 |
| 图片 | 只取 `images[].url_list[0]`，按 `uri` 去重；**不要** `download_url_list`（带水印） |
| 视频无水印 | `video.play_addr.uri` → `https://aweme.snssdk.com/aweme/v1/play/?video_id={uri}`；`playwm` 是带水印 |
| 类型判定 | `pics` 非空 → image，否则 video；video 拿不到地址 → `code=no_media` |
| 兜底 | SEO 页 `ld+json` 的 `@type=article` 的 `image` 列表 |
| 超时 | 总预算 60s、单请求 15s；短链跟随 ≤20s；`code=timeout` 区分「超时」与「作品有问题」 |
| 媒体下载头 | 图片/视频：iPhone UA + `Referer: https://www.iesdouyin.com/`；失败换桌面 UA + `Referer: https://www.douyin.com/` |

命名 / ZIP 契约也放 core：`core/src/main/kotlin/com/clipnest/core/download/`

```kotlin
enum class ZipNamingRule { SEQ_TITLE, TITLE_RES, TITLE_ONLY, SEQ_ONLY }   // 1/2/3/4
object DownloadNaming {
    fun sanitizeTitle(raw: String): String     // 去掉 \/:*?"<>| 与控制符，压空白，截断 150
    fun fileName(rule: ZipNamingRule, seq: Int, title: String, res: String, ext: String): String
    fun dedupe(name: String, used: MutableSet<String>): String   // 撞名 _1/_2
}
object MediaSniffer { fun sniff(head: ByteArray): String }        // jpg/png/gif/webp/avif/heic/mp4/unknown
object ZipPlan {
    fun zipFileName(type: String, title: String): String          // image_batch_<标题>.zip / video_batch_<标题>.zip
    fun folderName(type: String): String                          // 所有图片 / 所有视频
    fun entries(pics: List<String>, videoUrl: String, title: String, rule: ZipNamingRule): List<Pair<String,String>>
}
```
命名规则（与线上一致）：1=`%02d_标题`、2=`标题_HD`（图片固定 HD，视频用清晰度）、3=`标题`、4=`%02d`。

**单测**（`:core:test` 全绿）：链接识别、`_ROUTER_DATA` 合成夹具（video/note/缺 item_list/空 url_list）、命名 4 规则+清洗+去重、魔术字节嗅探、ZIP 条目规划。

## 3. 下载引擎契约（host-shell 实现）

放 `app/src/main/kotlin/com/clipnest/app/platform/download/`：

| 文件 | 职责 |
|---|---|
| `DownloadControllerImpl.kt` | **实现 `data/DownloadContracts.kt` 的 `DownloadController`**，MainActivity 用它替换 Stub |
| `DownloadQueue.kt` | 队列 + `StateFlow<List<DownloadTaskUi>>` + 持久化 `files/clipnest/downloads.json` + 并发（图片 3 路、任务串行）+ 取消 |
| `MediaSaver.kt` | API29+：`MediaStore.Images` `RELATIVE_PATH=Pictures/ClipNest/<标题>`；视频 `Movies/ClipNest`；ZIP `Download/ClipNest`；`IS_PENDING` 用法；26–28 走公有目录 + `WRITE_EXTERNAL_STORAGE`，拒绝则 App 私有目录 |
| `Downloader.kt` | 单文件流式下载：双套请求头、失败重试 1 次、进度回调、`MediaSniffer` 定扩展名 |
| `ZipWriter.kt` | `ZipOutputStream` 流式写 MediaStore（STORED、`<folder>/<name>`、`_1` 去重），**不许把整个文件读进内存** |
| `DownloadService.kt` | 前台服务 `dataSync`：进度通知（`X/Y · 速度`）+ 取消动作 + 完成/失败通知 |
| `AutoDownloadHook.kt` | 在 `CaptureEngine.capture` 成功后：平台=douyin 且 `settings.autoDownload != off` + Wi-Fi 条件 → 异步解析入队；按 awemeId 去重（`files/clipnest/auto_downloaded.json` 最多 200 条） |
| `DownloadNotifier.kt` | 渠道「下载」、完成可点开、失败可重试 |

解析调用：`DouyinResolver().resolve(text)` 要在 IO 线程；UI 的 `resolving` StateFlow 反映状态。

## 4. UI 契约（ui-web 实现）

**下载页** `ui/download/DownloadPage.kt`（签名冻结）：
- 顶栏标题「下载」+ 右侧清空已完成
- 手动/自动分段：自动 = `settings.autoDownload`（关/仅图片/图片+视频）
- 输入卡：`TextField` + 解析按钮 + 「读剪贴板」；解析中 `LinearProgressIndicator`
- 结果卡：封面图（16:9 圆角）、标题两行、作者、类型标签（`图文 · N 张` / `视频 · 1080p · 4.8 MB`）、命名规则 chips、
  两个主按钮 **「下载所有图片 ZIP」/「下载所有视频 ZIP」** + 「逐个下载」次按钮
- 任务列表：`SmallTitle("下载任务")` + 行（标题/进度/状态/取消/打开）
- 空状态 + 失败 `Snackbar`（中文文案按 `DouyinWork.message`）

**风格统一（硬要求，与 Nexio 不割裂）**：
| 改动 | 做法 |
|---|---|
| 背景 | 删 `NestBackground()` 四色斑，改 `MiuixTheme.colorScheme.background`（浅色近 #F2F3F5 / 深色 #14161A） |
| 顶栏 | 换 MiUiX `ProgressiveBlurTopBar`（照 `NexioSchedule/ui-shared/.../AboutScreen.kt`），居中标题 + 右侧 `IconButton`，删副标题行（计数移到列表头部） |
| 卡片 | 全部换 MiUiX `Card` + `insideMargin`；删自定义 `glass()` 面（只保留 dock 玻璃） |
| 行 | 用 `SwitchPreference` / `ArrowPreference` / `BasicComponent` |
| 搜索 | 换 MiUiX `SearchBar` |
| 弹层 | `Sheets.kt` 换 `OverlayBottomSheet`（详情/手动收录）+ `OverlayDialog`（确认） |
| 字体/颜色 | 一律 `MiuixTheme.textStyles.*` / `MiuixTheme.colorScheme.*`；平台标签用 `primaryContainer/secondaryContainer/tertiaryContainer` 轮转 |
| 滚动 | 三个页面加 `overScrollVertical()` + `scrollEndHaptic()` |

**收件箱**：卡片加一个「下载」图标按钮（`MiuixIcons.Download`）→ 有抖音链接时 `onDownload(item.url)`；`ClipHomeScreen` 里切到下载页并调 `controller.resolve(url)`。

## 5. 验收（真机 PHK110 / Android 16 已连 adb）

- 三条样例 `I:\Download\类似.txt` 解析：类型/图片数/视频地址正确
- 图片 ZIP：`Download/ClipNest/image_batch_*.zip`，条目数=图片数、扩展名正确
- 视频：单文件 + ZIP；可播放
- 自动下载三档生效、同作品不重复
- dock：收件箱/下载切换、圆点进设置、设置进规则可返回
- 截图对比 Nexio：背景/卡片/字号/圆角/颜色不割裂
