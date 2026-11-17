# ClipNest 剪巢 · 安卓版开发契约（全员必读）

> **产品**：常驻 Android 的剪贴板收藏器。识别抖音/小红书等 8 个平台的「复制打开…」分享文本，自动收录成卡片收件箱。
> **技术栈与 NexioSchedule 完全一致**：Kotlin 2.4.10 + Jetpack Compose（Compose BOM 2026.05.01 + material3 1.5.0-alpha19）+ **MiUiX 0.9.4**（`top.yukonga.miuix.kmp`）+ AGP 9.2.1 + Gradle 9.5.1，compileSdk/targetSdk 37，minSdk 26。**不许换成 XML/WebView/其他 UI 方案**。
> **视觉基线**：`01_projects/NexioSchedule`（HyperOS 4 液态玻璃，见其 docs/picture/*.png），以及 Windows 原型阶段产出的设计稿 `01_projects/ClipNest/web/`（HTML/CSS 版的同一套设计，token 可直接换算成 Compose）。

---

## 1. 目录与写作用域（严格不越界）

```
01_projects/ClipNestAndroid/
├── CONTRACT_ANDROID.md          Lead（冻结）
├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties   Lead（冻结）
├── gradle/libs.versions.toml    Lead（冻结；要加依赖先找 Lead）
├── gradlew / gradlew.bat / gradle/wrapper/*   Lead
├── clipnest-release.jks         Lead（签名，密码 clipnest123）
├── tools/**                     ★ pack-e2e 独占
├── README.md                    ★ pack-e2e 独占
├── core/src/**                  ★ core-svc 独占（Model.kt 冻结）
├── app/src/main/kotlin/com/clipnest/app/ui/**      ★ ui-web 独占（UiActions.kt 冻结）
├── app/src/main/kotlin/com/clipnest/app/platform/** ★ host-shell 独占
├── app/src/main/AndroidManifest.xml                 ★ host-shell 独占
├── app/src/main/res/xml/**, res/drawable/**         ★ host-shell 独占
├── app/src/main/kotlin/com/clipnest/app/MainActivity.kt    Lead
├── app/src/main/kotlin/com/clipnest/app/data/ClipRepo.kt   Lead（冻结契约）
└── app/src/main/res/values/**、mipmap/**                   Lead + pack-e2e（图标）
```

只改自己名下文件；需要动别人的先发消息。不要 git，不要删别人文件。

---

## 2. 构建（本机沙箱限制，必须按这个来）

```powershell
cd I:\DeepseekHarness\01_projects\ClipNestAndroid
$env:JAVA_HOME        = 'C:\Program Files\Java\latest\jdk-21'
$env:ANDROID_HOME     = 'I:\DeepseekHarness\05_toolchain\nexio-build\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME= 'I:\DeepseekHarness\99_other_file\android-user-home'
$env:GRADLE_USER_HOME = 'I:\DeepseekHarness\05_toolchain\nexio-build\gradle-home'
$env:TMP = 'I:\DeepseekHarness\99_other_file\tmp\gtmp'; $env:TEMP = $env:TMP
cmd /c "gradlew.bat :app:assembleDebug --console=plain"
```
- 压缩包/依赖全部走阿里云镜像（settings.gradle.kts 已配），**不要联网找 Google/GitHub**（不可达）。
- 只编译自己模块可省时间：core `:core:test` / `:core:compileKotlin`，UI+服务 `:app:assembleDebug`。
- 编译报错先看 `e:` 行；`AccessDeniedException ...\.android|kotlin\daemon` 是环境噪声，忽略。
- APK 产物：`app/build/outputs/apk/debug/app-debug.apk`（本机没有真机/模拟器，验收=编译通过+单测+静态检查）。

---

## 3. 数据层契约（Lead 已实现，谁都不许改签名）

`com.clipnest.core`（core 模块，纯 Kotlin JVM）：
- `Model.kt`（冻结）：`ClipItem` / `AppSettings` / `CaptureRule` / `CaptureResult` / `ItemsChangeKind`
- `RuleEngine(rules)`：`tryCapture(text, source): CaptureResult`、`previewAll(text)`、`rules()`、`setRuleEnabled(id, enabled)`
- `RuleCatalog.defaults()`：9 条内置规则（douyin-share / douyin-legacy / douyin-plain / xiaohongshu / kuaishou / bilibili / weibo / youtube / generic-link[默认关]）
- `ItemStore(file, maxItems, dedupeByUrl)`：`items()` / `add` / `update` / `setStar` / `remove` / `clear` / `reload` / `addListener(kind,item)`
- `SettingsStore(file)`：`current()` / `update { }` / `addListener`
- `Exporter`：`toMarkdown` / `toText` / `toCsv` / `toJson`
- `TextUtil`：`normalize` / `normalizeUrl` / `compact`

`com.clipnest.app.data.ClipRepo`（单例，冻结）：
```kotlin
ClipRepo.init(context)
ClipRepo.items: StateFlow<List<ClipItem>>          // 新的在前
ClipRepo.settings: StateFlow<AppSettings>
ClipRepo.rules: StateFlow<List<CaptureRule>>
ClipRepo.lastCaptured: StateFlow<ClipItem?>
ClipRepo.captureText(text, source="clipboard"): CaptureResult?   // 命中即落库并更新 StateFlow
ClipRepo.preview(text): List<CaptureResult>
ClipRepo.setStar(id, starred) / delete(id) / clearAll() / updateItem(item)
ClipRepo.updateSettings { it.copy(monitor = false) }
ClipRepo.setRuleEnabled(id, enabled)
ClipRepo.export(format, starredOnly=false): String   // 返回内容，由调用方决定落地
```

UI ↔ 平台层只通过 `com.clipnest.app.ui.UiActions`（冻结）回调，见文件。

---

## 4. 产品行为（Android 现实约束，必须照此实现并如实标注）

Android 10+ 后台读剪贴板被系统禁止（只有前台或默认输入法可读）。所以收录有 3 条真实通路：

| 通路 | 触发 | 说明 |
|---|---|---|
| **A. 应用内监听** | 剪巢在前台时轮询剪贴板（400ms） | 自动、无额外权限；离开前台即停，符合系统限制 |
| **B. 分享到剪巢** | 抖音/小红书等 → 分享 → 剪巢 | `ACTION_SEND text/plain`，**最可靠的后台收录方式**，零权限 |
| **C. 悬浮球一键收录** | 打开悬浮窗权限后，桌面上的小球点一下 | 点击时拉起透明 Activity 抢焦点读剪贴板，读完立即关闭，约 200ms |

配套：
- **前台服务**：常驻通知（可暂停监听/一键收录/打开应用），保活悬浮球；`FOREGROUND_SERVICE_SPECIAL_USE` + `POST_NOTIFICATIONS`。
- **开机自启**：`BOOT_COMPLETED` → 若设置里 `autostartService=true` 则启动服务（国产 ROM 需引导用户加自启动白名单，设置页写明）。
- **通知权限 / 悬浮窗权限**：设置页给出申请入口与状态显示，不允许静默失败。
- 所有权限都必须在设置页显示当前状态 + 一键申请，并向用户解释「为什么需要」。

---

## 5. 视觉规范（Compose 版，等价于 Windows 原型 web/css/tokens.css）

- 画布：`#EEF0F3` 浅色 / `#14161A` 深色；背景 4 个高斯色斑（`Brush.radialGradient`，色：`#C9DDFB` `#FBD9E6` `#D8F0D8` `#FCEBC8`，透明度 ~0.55），可叠极轻噪点。
- 文字：主 `#17181A`、次 `#5F636C`、弱 `#9AA0AA`；分隔线 `rgba(0,0,0,.055)`。
- 玻璃卡片：`Color.White.copy(alpha = 0.72f)`（深色 `Color(0xFF202228).copy(0.72f)`）+ 圆角 22dp + `Modifier.shadow(14.dp, RoundedCornerShape(22.dp))` + 顶部 1dp 内高光（`border(1.dp, Brush.verticalGradient(...))`）；如需模糊用 MiUiX `top.yukonga.miuix.kmp.blur` 的 haze 组件（参考 NexioSchedule 用法），API<33 降级为纯半透明。
- 平台卡片色（底/字）：绿 `#E3F2DC/#2F7D46`、蓝 `#DCEEF9/#1B6E93`、琥珀 `#FDEAD0/#AE6A13`、粉 `#FBE0E7/#BE4A6B`、紫 `#E8E5F8/#5A4EA6`、青 `#DCF3F6/#15707C`；按 platform 映射。
- 主色 `#3B7BF6`；圆角：卡片 22、面板 28、行 16、chip 999、dock 36。
- **底部 dock（1:1 复刻参考图）**：距底 20dp 的悬浮胶囊，高 68dp，圆角 36dp，玻璃底 + 阴影；4 个 tab（图标 22dp + 12sp 文字，选中 = `Color.Black.copy(0.06f)` 圆胶囊 + 文字加粗 + 图标深色，未选中 `#9AA0AA`）；右侧独立 52dp 圆形玻璃按钮（手动收录）。切换动画 240ms `FastOutSlowInEasing`。
- 顶部：56dp 标题栏，标题居中（26sp Semibold）+ 左侧留白/返回，右侧图标按钮；内容滚动区。
- 字体：`FontFamily.Default`（系统中文），数字用 `FontFeatureSetting("tnum")`。
- **禁止**：直角、粗边框、纯黑投影、渐变大按钮、Material 默认紫色、把 Material3 组件裸用（能用 MiUiX 就用 MiUiX：`MiuixTheme` / `Card` / `Scaffold` / `TextButton` / `SwitchPreference` 等）。

MiUiX 用法参考（本机有源码级样例，直接搜）：
```
I:\DeepseekHarness\01_projects\NexioSchedule\ui-shared\src\commonMain\kotlin\com\haooz\chedule\ui\
```
搜索 `MiuixTheme(`、`Card(`、`SwitchPreference`、`Scaffold(`、`SmallTitle`、`MiuixIcons`。

---

## 6. 验收

- `:core:test` 全绿（core-svc 负责 3 条抖音样例逐字段断言 + 噪声不命中 + 存储/导出）。
- `:app:assembleDebug` 0 error；UI 至少 4 个页面可切换，dock 正确。
- 平台层：分享 Intent 能被接住、服务能起停、权限状态能读；用日志/单测证明逻辑（无真机）。
- pack-e2e：`tools/build-apk.ps1` 一键出 release APK 到 `03_releases/ClipNest/`，README 写清安装与已知限制。
- 交接：完成后给 Lead 发消息：做了什么 / 怎么验证 / 已知问题 / 遗留。
