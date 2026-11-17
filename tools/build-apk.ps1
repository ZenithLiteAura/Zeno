# ClipNest 剪巢 · Android release APK 一键构建（1.1 下载版就绪）
#
# 流程：
#   1. 按契约 §2 设置 JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT / ANDROID_USER_HOME /
#      GRADLE_USER_HOME / TMP（本机沙箱限制，不设会 permission denied）
#   2. 从 app/build.gradle.kts 读 versionName / versionCode，产物名 = ClipNest-<version>.apk
#   3. 检查启动图标，缺了就用 tools/make-icons.py 重新生成
#   4. gradlew.bat :app:assembleRelease --console=plain（原生调用，不用 cmd /c；
#      失败时打印关键错误行；lint 分析器自身崩溃会自动 -x lintVitalAnalyzeRelease 重试）
#   5. 校验 app/build/outputs/apk/release/app-release.apk
#   6. 复制到 03_releases\ClipNest\ClipNest-<version>.apk + 带时间戳备份
#   7. aapt2 dump badging（package/minSdk/targetSdk/label/launcher/权限）+ 版本一致性校验
#   8. 1.1 下载模块静态体检：INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE_DATA_SYNC、
#      DownloadService(dataSync) 是否在清单里、APK 里有没有混进 Python 项目/测试夹具
#   9. apksigner verify 签名校验
#  10. 打印 APK 大小与 SHA256；可选 -WithMagisk 顺带重打 Magisk 模块 zip
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -WithMagisk      # 同时出 root 模块 zip
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -Variant debug
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -Clean
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -SkipDownloadChecks  # 下载模块未就绪时
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -CheckOnly <apk>     # 只静态体检现有 APK
#   powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1 -Help
#
# 说明：
#   * .ps1 必须保存为 UTF-8 带 BOM，否则 PowerShell 5.1 会把中文注释当 GBK 解析报错。
#   * 首次构建要下载/解压 Gradle 与依赖（走阿里云镜像），可能 1~3 分钟；之后几十秒。
#   * 本机没有真机/模拟器，验收 = 构建通过 + 静态体检（badging / 清单 / 签名 / 内容）。
#   * 脚本里 aapt2 / apksigner 用到 cmd.exe /c 只是为了把输出重定向到文件（避免管道继承
#     句柄卡住），gradle 本身是 PowerShell 原生调用，符合契约 §0。

[CmdletBinding()]
param(
    [ValidateSet('release', 'debug')]
    [string]$Variant = 'release',
    [string]$VersionName = '',
    [string]$ReleaseDir = 'I:\DeepseekHarness\03_releases\ClipNest',
    [switch]$Clean,
    [switch]$SkipIconCheck,
    [switch]$SkipLint,
    [switch]$SkipDownloadChecks,
    [switch]$WithMagisk,
    [string]$CheckOnly = '',
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 0
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
function Write-Step([string]$text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "   [OK] $text" -ForegroundColor Green }
function Write-Warn2([string]$text) { Write-Host "   [提示] $text" -ForegroundColor Yellow }
function Fail([string]$text) {
    Write-Host "`n[构建失败] $text" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------ 路径
if ($PSScriptRoot) { $Root = Split-Path -Parent $PSScriptRoot } else { $Root = (Get-Location).Path }
$Root = (Resolve-Path $Root).Path
$GradleBat = Join-Path $Root 'gradlew.bat'
$AppGradle = Join-Path $Root 'app\build.gradle.kts'
$MakeIcons = Join-Path $Root 'tools\make-icons.py'
$MakeMagisk = Join-Path $Root 'tools\make-magisk-module.py'

$Sdk = 'I:\DeepseekHarness\05_toolchain\nexio-build\android-sdk'
$BuildTools = Join-Path $Sdk 'build-tools\36.0.0'
$Aapt2 = Join-Path $BuildTools 'aapt2.exe'
$ApkSigner = Join-Path $BuildTools 'apksigner.bat'
$JavaHome = 'C:\Program Files\Java\latest\jdk-21'

$TmpDir = 'I:\DeepseekHarness\99_other_file\tmp\gtmp'
$LogDir = Join-Path $Root 'build\pack-logs'
$GradleLog = Join-Path $LogDir ("gradle-$Variant.log")

if (-not (Test-Path $GradleBat)) { Fail "找不到 gradlew.bat：$GradleBat" }
if (-not (Test-Path $JavaHome)) { Fail "找不到 JDK 21：$JavaHome" }
if (-not (Test-Path $Sdk)) { Fail "找不到 Android SDK：$Sdk" }
if (-not (Test-Path $Aapt2)) { Fail "找不到 aapt2：$Aapt2" }
if (-not (Test-Path $ApkSigner)) { Write-Warn2 "找不到 apksigner：$ApkSigner（签名校验会跳过）" }

New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# ------------------------------------------------------------------ 版本号
# 版本以 app/build.gradle.kts 为唯一事实来源（Lead 维护），产物名跟它走。
$VersionCode = 0
$gradleVersionName = ''
try {
    $gradleText = [System.IO.File]::ReadAllText($AppGradle, [System.Text.Encoding]::UTF8)
    $mName = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
    if ($mName.Success) { $gradleVersionName = $mName.Groups[1].Value }
    $mCode = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
    if ($mCode.Success) { $VersionCode = [int]$mCode.Groups[1].Value }
}
catch { }
if (-not $VersionName) { $VersionName = $gradleVersionName }
if (-not $VersionName) { $VersionName = '1.1.0' }

# ------------------------------------------------------------------ 环境
Write-Step "环境（契约 §2）"
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_USER_HOME = 'I:\DeepseekHarness\99_other_file\android-user-home'
$env:GRADLE_USER_HOME = 'I:\DeepseekHarness\05_toolchain\nexio-build\gradle-home'
$env:TMP = $TmpDir
$env:TEMP = $TmpDir
$env:PATH = "$JavaHome\bin;$BuildTools;$env:PATH"
# 配合上面的 Console.OutputEncoding=UTF-8：让 python 也按 UTF-8 输出，中文不乱码
$env:PYTHONIOENCODING = 'utf-8'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
Write-Ok "JAVA_HOME        = $env:JAVA_HOME"
Write-Ok "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Ok "ANDROID_USER_HOME= $env:ANDROID_USER_HOME"
Write-Ok "GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Ok "TMP              = $env:TMP"
Write-Ok "版本             = $VersionName (versionCode $VersionCode)  <- app/build.gradle.kts"
if ($gradleVersionName -and $VersionName -ne $gradleVersionName) {
    Write-Warn2 "命令行 -VersionName '$VersionName' 与 build.gradle.kts '$gradleVersionName' 不一致，产物名按参数走"
}

try {
    $javaVer = (& "$JavaHome\bin\java.exe" -version 2>&1 | Select-Object -First 1)
    Write-Ok "java             = $javaVer"
}
catch { }

# ------------------------------------------------------------------ 图标自检
if (-not $SkipIconCheck) {
    Write-Step "启动图标自检"
    $needIcons = $false
    foreach ($dpi in @('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')) {
        foreach ($name in @('ic_launcher.png', 'ic_launcher_foreground.png')) {
            $p = Join-Path $Root "app\src\main\res\mipmap-$dpi\$name"
            if (-not (Test-Path $p)) { $needIcons = $true; Write-Warn2 "缺少 $dpi\$name" }
        }
    }
    if (-not (Test-Path (Join-Path $Root 'app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml'))) {
        $needIcons = $true
        Write-Warn2 '缺少 mipmap-anydpi-v26\ic_launcher.xml'
    }
    if ($needIcons) {
        if (Test-Path $MakeIcons) {
            Write-Warn2 '图标不全，运行 tools\make-icons.py 重新生成...'
            & python $MakeIcons
            if ($LASTEXITCODE -ne 0) { Fail '图标生成失败（python tools\make-icons.py）' }
            Write-Ok '图标已重新生成'
        }
        else { Fail "图标不全且找不到 $MakeIcons" }
    }
    else { Write-Ok '图标齐全（legacy / round / adaptive 前景 + anydpi-v26 XML）' }
}

# ------------------------------------------------------------------ 构建
$task = "assemble" + ($Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1))
if ($CheckOnly) {
    Write-Step "仅体检模式（-CheckOnly）：跳过构建，直接体检 $CheckOnly"
}
else {
Write-Step "gradlew :app:$task（日志：$GradleLog）"
Write-Host "   首次构建可能 1~3 分钟，请稍候..." -ForegroundColor DarkGray

function Invoke-GradleBuild {
    param([string]$ExtraArgs, [string]$LogPath)
    $buildArgs = @(":app:$task", '--console=plain')
    if ($ExtraArgs) { $buildArgs += ($ExtraArgs -split '\s+' | Where-Object { $_ }) }
    if ($Clean) { $buildArgs = @('clean') + $buildArgs }

    $collected = New-Object System.Collections.Generic.List[string]
    Push-Location $Root
    $ErrorActionPreference = 'Continue'
    try {
        # 契约 §0：直接调 gradlew.bat（不用 cmd /c），一边回显一边收集日志
        & $GradleBat @buildArgs 2>&1 | ForEach-Object {
            # stderr 合并进来的是 ErrorRecord，直接 "$_" 会变成类型名，这里取真正的消息
            $line = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
            $collected.Add($line)
            Write-Host "   $line" -ForegroundColor DarkGray
        }
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    try {
        [System.IO.File]::WriteAllLines($LogPath, $collected, (New-Object System.Text.UTF8Encoding $false))
    }
    catch { }
    return [pscustomobject]@{ ExitCode = $code; Lines = $collected }
}

$lintNote = ''
$baseArgs = if ($SkipLint) { '-x lintVitalAnalyzeRelease' } else { '' }
if ($SkipLint) { Write-Warn2 '按 -SkipLint：本次构建跳过 lint' }

$build = Invoke-GradleBuild -ExtraArgs $baseArgs -LogPath $GradleLog
$lintCrashed = @($build.Lines | Where-Object { $_ -match 'Unexpected failure during lint analysis' -or $_ -match 'AsyncExecutionService' }).Count -gt 0

if ($build.ExitCode -ne 0 -and $lintCrashed -and -not $SkipLint) {
    Write-Warn2 'Lint 分析器自身崩溃（AGP/Kotlin 工具链 bug，不是项目代码问题），跳过 lint 重试一次...'
    $lintLog = $GradleLog -replace '\.log$', '-nolint.log'
    $build = Invoke-GradleBuild -ExtraArgs '-x lintVitalAnalyzeRelease' -LogPath $lintLog
    if ($build.ExitCode -eq 0) {
        $GradleLog = $lintLog
        $lintNote = '本次构建跳过了 lint（-x lintVitalAnalyzeRelease）'
        Write-Ok '跳过 lint 后构建成功'
    }
}

$gradleLines = $build.Lines
if ($build.ExitCode -ne 0) {
    Write-Host "`nGradle 最后 40 行：" -ForegroundColor Red
    ($gradleLines | Select-Object -Last 40) | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    $errLines = @($gradleLines | Where-Object { $_ -match '^e:|^error:|FAILURE:|Execution failed|> Task .* FAILED' })
    if ($errLines.Count -gt 0) {
        Write-Host "`n关键错误行：" -ForegroundColor Red
        $errLines | Select-Object -First 25 | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    }
    Fail "gradlew :app:$task 退出码 $($build.ExitCode)（完整日志：$GradleLog）"
}
Write-Ok "Gradle 构建成功（$([math]::Round($sw.Elapsed.TotalSeconds,1))s）"
}

# ------------------------------------------------------------------ 产物
Write-Step '定位 APK 产物'
if ($CheckOnly) {
    if (-not (Test-Path $CheckOnly)) { Fail "找不到要体检的 APK：$CheckOnly" }
    $builtApk = (Resolve-Path $CheckOnly).Path
}
else {
    $builtApk = Join-Path $Root "app\build\outputs\apk\$Variant\app-$Variant.apk"
    if (-not (Test-Path $builtApk)) { Fail "构建成功但找不到 APK：$builtApk" }
    $builtApk = (Resolve-Path $builtApk).Path
}
$builtSize = (Get-Item $builtApk).Length
Write-Ok ("产物 {0}（{1:N1} MB）" -f $builtApk, ($builtSize / 1MB))

if ($Variant -ne 'release') {
    Write-Warn2 '当前是 debug 变体，只做静态体检，不复制到 03_releases'
}

# ------------------------------------------------------------------ 复制发布
$releaseApk = ''
if ($Variant -eq 'release' -and -not $CheckOnly) {
    Write-Step "复制到发布目录 $ReleaseDir"
    New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
    $releaseApk = Join-Path $ReleaseDir "ClipNest-$VersionName.apk"
    Copy-Item -LiteralPath $builtApk -Destination $releaseApk -Force
    $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
    $backupApk = Join-Path $ReleaseDir "ClipNest-$VersionName-$stamp.apk"
    Copy-Item -LiteralPath $builtApk -Destination $backupApk -Force
    Write-Ok "正式名：$releaseApk"
    Write-Ok "备份名：$backupApk"
}

$checkApk = if ($releaseApk) { $releaseApk } else { $builtApk }

# ------------------------------------------------------------------ aapt2 dump badging
Write-Step 'aapt2 dump badging（静态体检）'
$badgeLog = Join-Path $LogDir "badging-$Variant.txt"
$badgingLines = @()
$allPerms = @()
$ErrorActionPreference = 'Continue'
try {
    & cmd.exe /c "`"$Aapt2`" dump badging `"$checkApk`" > `"$badgeLog`" 2>&1"
    $badgeExit = $LASTEXITCODE
}
finally { $ErrorActionPreference = 'Stop' }

if ($badgeExit -eq 0 -and (Test-Path $badgeLog)) {
    $badgingLines = @([System.IO.File]::ReadAllLines($badgeLog, [System.Text.Encoding]::UTF8))
    $pick = @(
        @{ Pattern = '^package:'; Label = 'package  ' },
        @{ Pattern = "^(minSdkVersion|sdkVersion):"; Label = 'minSdk   ' },
        @{ Pattern = "^targetSdkVersion:"; Label = 'targetSdk' },
        @{ Pattern = "^application-label:"; Label = 'label    ' },
        @{ Pattern = "^launchable-activity:"; Label = 'launcher ' }
    )
    foreach ($item in $pick) {
        $hit = @($badgingLines | Where-Object { $_ -match $item.Pattern } | Select-Object -First 1)
        if ($hit.Count -gt 0) { Write-Host ("   {0} {1}" -f $item.Label, $hit[0]) -ForegroundColor Gray }
    }
    $allPerms = @($badgingLines | Where-Object { $_ -match '^uses-permission' } | ForEach-Object {
            if ($_ -match "name='([^']+)'") { $Matches[1] } else { $_ }
        })
    Write-Host ("   权限     {0} 条" -f $allPerms.Count) -ForegroundColor Gray
    $allPerms | ForEach-Object { Write-Host "            $_" -ForegroundColor DarkGray }

    # 版本一致性：badging 与 build.gradle.kts 必须一致，否则发布包名对不上
    $pkgLine = @($badgingLines | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    if ($pkgLine.Count -gt 0) {
        $bName = ''
        $bCode = 0
        if ($pkgLine[0] -match "versionName='([^']+)'") { $bName = $Matches[1] }
        if ($pkgLine[0] -match "versionCode='(\d+)'") { $bCode = [int]$Matches[1] }
        if ($bName -ne $VersionName -or ($VersionCode -gt 0 -and $bCode -ne $VersionCode)) {
            if ($CheckOnly) {
                Write-Warn2 "版本不一致（仅体检模式不判失败）：build.gradle.kts = $VersionName (code $VersionCode)，APK = $bName (code $bCode)"
            }
            else {
                Fail "版本不一致：build.gradle.kts = $VersionName (code $VersionCode)，APK = $bName (code $bCode)。请确认 Lead 已改版本号并重新构建"
            }
        }
        else {
            Write-Ok "版本一致性：APK = $bName (code $bCode) 与 build.gradle.kts 一致"
        }
    }

    if ($Variant -eq 'release') {
        $badgeArchive = Join-Path $ReleaseDir "ClipNest-$VersionName-badging.txt"
        Copy-Item -LiteralPath $badgeLog -Destination $badgeArchive -Force
        Write-Ok "badging 结果已保存：$badgeArchive"
    }
}
else {
    Write-Warn2 "aapt2 dump badging 失败（退出码 $badgeExit），详见 $badgeLog"
}

# ------------------------------------------------------------------ 1.1 下载模块体检
Write-Step '1.1 下载模块体检'
$checkReport = Join-Path $LogDir "download-checks-$Variant.txt"
$checkLines = New-Object System.Collections.Generic.List[string]
$checkFail = New-Object System.Collections.Generic.List[string]

function Add-Check([string]$status, [string]$name, [string]$detail) {
    $color = switch ($status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'Yellow' } }
    Write-Host ("   [{0}] {1}" -f $status, $name) -ForegroundColor $color
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
    $line = "[{0}] {1}" -f $status, $name
    if ($detail) { $line += " | $detail" }
    $script:checkLines.Add($line)
    if ($status -eq 'FAIL') { $script:checkFail.Add($name) }
}

if ($SkipDownloadChecks) {
    Add-Check 'SKIP' '下载模块体检' 'disabled by -SkipDownloadChecks'
}
else {
    # 1) 权限
    $requiredPerms = [ordered]@{
        'android.permission.INTERNET'                     = '解析 / 下载抖音作品需要联网'
        'android.permission.ACCESS_NETWORK_STATE'         = '「仅 Wi-Fi 自动下载」判断网络'
        'android.permission.FOREGROUND_SERVICE_DATA_SYNC' = '下载前台服务 dataSync 类型'
    }
    foreach ($perm in $requiredPerms.Keys) {
        if (@($allPerms) -contains $perm) { Add-Check 'PASS' "权限 $perm" $requiredPerms[$perm] }
        else { Add-Check 'FAIL' "权限 $perm" '缺失（1.1 下载功能必需）' }
    }
    $optionalPerms = [ordered]@{
        'android.permission.READ_CLIPBOARD_IN_BACKGROUND' = 'root/Magisk 模块授予真后台监听时用'
        'android.permission.WRITE_EXTERNAL_STORAGE'       = 'Android 8/9 存公共目录'
    }
    foreach ($perm in $optionalPerms.Keys) {
        if (@($allPerms) -contains $perm) { Add-Check 'PASS' "权限 $perm" $optionalPerms[$perm] }
        else { Add-Check 'INFO' "权限 $perm" '未声明（可选）' }
    }

    # 2) 清单：DownloadService + foregroundServiceType=dataSync(0x00000001)
    $xmlLog = Join-Path $LogDir "xmltree-$Variant.txt"
    $ErrorActionPreference = 'Continue'
    try {
        & cmd.exe /c "`"$Aapt2`" dump xmltree --file AndroidManifest.xml `"$checkApk`" > `"$xmlLog`" 2>&1"
        $xmlExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = 'Stop' }

    if ($xmlExit -eq 0 -and (Test-Path $xmlLog)) {
        $tree = @([System.IO.File]::ReadAllLines($xmlLog, [System.Text.Encoding]::UTF8))
        $svcIdx = -1
        for ($i = 0; $i -lt $tree.Count; $i++) {
            if ($tree[$i] -match 'download\.DownloadService') { $svcIdx = $i; break }
        }
        if ($svcIdx -ge 0) {
            Add-Check 'PASS' 'DownloadService 已声明' ("AndroidManifest.xml 第 {0} 行附近" -f ($svcIdx + 1))
            $endIdx = [Math]::Min($svcIdx + 9, $tree.Count - 1)
            $window = @($tree[$svcIdx..$endIdx])

            # foregroundServiceType：可能是多个类型的按位或，这里按位判断是否含 dataSync(0x1)
            $fgsLine = @($window | Where-Object { $_ -match ':foregroundServiceType\(' } | Select-Object -First 1)
            if ($fgsLine.Count -gt 0) {
                $raw = ''
                if ($fgsLine[0] -match '=(0x[0-9a-fA-F]+|\d+)') { $raw = $Matches[1] }
                $val = [int64]-1
                if ($raw -match '^0x') { try { $val = [Convert]::ToInt64($raw.Substring(2), 16) } catch { $val = -1 } }
                elseif ($raw -match '^\d+$') { $val = [int64]$raw }
                if ($val -ge 0 -and (($val -band 0x1) -eq 0x1)) {
                    Add-Check 'PASS' 'DownloadService foregroundServiceType 含 dataSync' $fgsLine[0].Trim()
                }
                else {
                    Add-Check 'FAIL' 'DownloadService foregroundServiceType 含 dataSync' "实际值 $raw 不含 dataSync(0x00000001)"
                }
            }
            else {
                Add-Check 'FAIL' 'DownloadService foregroundServiceType 含 dataSync' '服务没有声明 foregroundServiceType'
            }

            # 下载服务必须不对外导出（只能由 App 自己/通知 action 拉起）
            $expLine = @($window | Where-Object { $_ -match ':exported\(' } | Select-Object -First 1)
            if ($expLine.Count -gt 0) {
                if ($expLine[0] -match '=(false|0x0+)\s*$') {
                    Add-Check 'PASS' 'DownloadService exported=false' $expLine[0].Trim()
                }
                else {
                    Add-Check 'FAIL' 'DownloadService exported=false' ("实际：{0}（服务不能对外导出）" -f $expLine[0].Trim())
                }
            }
            else {
                Add-Check 'FAIL' 'DownloadService exported=false' '清单里没有 android:exported 属性，请显式写 android:exported="false"'
            }
        }
        else {
            Add-Check 'FAIL' 'DownloadService 已声明' '清单里没有 .platform.download.DownloadService'
        }
    }
    else {
        Add-Check 'SKIP' 'DownloadService 清单检查' "aapt2 dump xmltree 失败（exit $xmlExit）"
    }

    # 3) 权限白名单：除已知清单外的「奇怪权限」只给 WARN；
    #    READ_CLIPBOARD_IN_BACKGROUND 是 root 模块专用权限，普通安装拿不到属正常，不判失败。
    $knownPerms = @(
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.WRITE_EXTERNAL_STORAGE',
        'android.permission.READ_EXTERNAL_STORAGE',
        'android.permission.READ_CLIPBOARD_IN_BACKGROUND'
    )
    $unexpected = @($allPerms | Where-Object {
            $permName = $_
            if ($knownPerms -contains $permName) { return $false }
            if ($permName -match '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$') { return $false }
            return $true
        })
    if ($unexpected.Count -eq 0) {
        Add-Check 'PASS' '权限清单无异常' "共 $($allPerms.Count) 条，全部在已知清单内"
    }
    else {
        Add-Check 'WARN' '权限清单有未知项' ($unexpected -join ', ')
    }
    Add-Check 'INFO' 'READ_CLIPBOARD_IN_BACKGROUND' 'root/Magisk 模块专用权限，普通安装拿不到属正常，不判失败'
}

# ------------------------------------------------------------------ 内容体检
Write-Step 'APK 内容体检（unzip 清单 + 无 Python 泄漏）'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$contentReport = Join-Path $LogDir "contents-$Variant.txt"
$pyLeakCount = 0
try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($checkApk)
    $entries = @($zip.Entries)
    $zip.Dispose()

    $clines = New-Object System.Collections.Generic.List[string]
    $clines.Add("APK      : $checkApk")
    $clines.Add("entries  : $($entries.Count)")
    $dex = @($entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })
    $groups = $entries | Where-Object { $_.FullName -notmatch '^classes\d*\.dex$' } |
        Group-Object { ($_.FullName -split '/')[0] } | Sort-Object Name
    foreach ($g in $groups) { $clines.Add(("group    : {0,-24} {1}" -f $g.Name, $g.Count)) }
    $resCount = @($entries | Where-Object { $_.FullName -like 'res/*' }).Count
    $libCount = @($entries | Where-Object { $_.FullName -like 'lib/*' }).Count
    $clines.Add("dex      : $($dex.Count) 个（$((@($dex | ForEach-Object { $_.FullName })) -join ', ')）")
    $clines.Add("res      : $resCount 个资源文件")
    $clines.Add("lib      : $libCount 个原生库")
    $clines.Add("size     : $((Get-Item $checkApk).Length) bytes")

    $junk = @($entries | Where-Object {
            $_.FullName -match '\.(md|map|log|iml|bak|orig)$' -or
            $_.FullName -match '(^|/)(\.gitignore|\.DS_Store|Thumbs\.db)$' -or
            $_.FullName -match '^src/|^build\.gradle|^gradlew'
        })
    if ($junk.Count -eq 0) { $clines.Add('junk     : 无 .md/.map/.log/.gitignore 等多余文件') }
    else {
        $clines.Add("junk     : 发现 $($junk.Count) 个可疑文件")
        $junk | ForEach-Object { $clines.Add("           $($_.FullName)") }
    }

    # 1.1：确认没把 Python 参考项目 / 测试夹具 / 文档一起打进包
    $pyLeak = @($entries | Where-Object {
            $_.FullName -match '\.py$' -or
            $_.FullName -match '(^|/)__pycache__/' -or
            $_.FullName -match '(^|/)web_demos/' -or
            $_.FullName -match 'hellotik' -or
            $_.FullName -match '\.ipynb$' -or
            $_.FullName -match '^assets/(demos|fixtures|tests?)/'
        })
    $pyLeakCount = $pyLeak.Count
    if ($pyLeak.Count -eq 0) {
        $clines.Add('python   : 未发现 Python 参考项目 / 测试夹具 / .ipynb（干净）')
    }
    else {
        $clines.Add("python   : 发现 $($pyLeak.Count) 个 Python/夹具文件（应移除）")
        $pyLeak | Select-Object -First 20 | ForEach-Object { $clines.Add("           $($_.FullName)") }
    }

    $sigFiles = @($entries | Where-Object { $_.FullName -match '^META-INF/.*\.(SF|RSA|DSA|EC)$' })
    if ($sigFiles.Count -eq 0) { $clines.Add('v1 签名  : 无 META-INF/*.SF（纯 v2 签名，符合预期）') }
    else { $clines.Add("v1 签名  : $($sigFiles.Count) 个 META-INF 签名文件") }

    [System.IO.File]::WriteAllLines($contentReport, $clines, (New-Object System.Text.UTF8Encoding $false))
    foreach ($l in $clines) { Write-Host "   $l" -ForegroundColor Gray }
    Write-Ok "APK 清单已保存：$contentReport"

    if ($Variant -eq 'release') {
        $contentArchive = Join-Path $ReleaseDir "ClipNest-$VersionName-contents.txt"
        Copy-Item -LiteralPath $contentReport -Destination $contentArchive -Force
        Write-Ok "内容清单已保存：$contentArchive"
    }
}
catch {
    Write-Warn2 "APK 内容体检失败：$($_.Exception.Message)"
}
if (-not $SkipDownloadChecks) {
    if ($pyLeakCount -gt 0) { Add-Check 'FAIL' '无 Python 项目泄漏' "$pyLeakCount 个 .py/web_demos/夹具 文件被打进 APK" }
    else { Add-Check 'PASS' '无 Python 项目泄漏' 'APK 里没有 .py / web_demos / __pycache__ / .ipynb' }
}

# ------------------------------------------------------------------ 签名
Write-Step 'apksigner 签名校验'
if (Test-Path $ApkSigner) {
    $signLog = Join-Path $LogDir "apksigner-$Variant.txt"
    $ErrorActionPreference = 'Continue'
    try {
        & cmd.exe /c "`"$ApkSigner`" verify --verbose --print-certs `"$checkApk`" > `"$signLog`" 2>&1"
        $signExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = 'Stop' }

    if ($signExit -eq 0) {
        $signLines = @()
        if (Test-Path $signLog) { $signLines = @([System.IO.File]::ReadAllLines($signLog)) }
        Write-Ok '签名校验通过（apksigner verify exit 0）'
        @($signLines | Where-Object { $_ -match 'Verified using v\d scheme' }) | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        @($signLines | Where-Object { $_ -match 'Signer #1 certificate (DN|SHA-256)' }) | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
    }
    else {
        Write-Host "`napksigner 输出：" -ForegroundColor Red
        if (Test-Path $signLog) { @([System.IO.File]::ReadAllLines($signLog)) | Select-Object -Last 15 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray } }
        Fail "APK 签名校验失败（退出码 $signExit）"
    }
}
else {
    Write-Warn2 '跳过签名校验（未找到 apksigner）'
}

# ------------------------------------------------------------------ 保存体检报告 + 体检闸门
if ($checkLines.Count -gt 0) {
    [System.IO.File]::WriteAllLines($checkReport, $checkLines, (New-Object System.Text.UTF8Encoding $false))
    if ($Variant -eq 'release') {
        $checksArchive = Join-Path $ReleaseDir "ClipNest-$VersionName-checks.txt"
        Copy-Item -LiteralPath $checkReport -Destination $checksArchive -Force
        Write-Ok "下载模块体检报告：$checksArchive"
    }
}
if ($checkFail.Count -gt 0) {
    Write-Host "`n静态体检未通过项：" -ForegroundColor Red
    $checkFail | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Fail "1.1 下载模块静态体检失败（$($checkFail.Count) 项）"
}

# ------------------------------------------------------------------ Magisk 模块（可选）
$magiskZip = ''
if ($WithMagisk -and $Variant -eq 'release') {
    Write-Step '重打 Magisk 模块（root 真后台监听）'
    if (-not (Test-Path $MakeMagisk)) { Fail "找不到 $MakeMagisk" }
    $magiskZip = Join-Path $ReleaseDir 'ClipNest-root-magisk.zip'
    & python $MakeMagisk --apk $checkApk --out $magiskZip
    if ($LASTEXITCODE -ne 0) { Fail "Magisk 模块打包失败（exit $LASTEXITCODE）" }
    Write-Ok "模块：$magiskZip"
}

# ------------------------------------------------------------------ 汇总
Write-Step '结果'
$hash = Get-FileHash -LiteralPath $checkApk -Algorithm SHA256
$finalSize = (Get-Item $checkApk).Length
$sizeText = if ($finalSize -ge 1MB) { "{0:N2} MB" -f ($finalSize / 1MB) } else { "{0:N0} KB" -f ($finalSize / 1KB) }
Write-Host "   APK      $checkApk" -ForegroundColor White
Write-Host "   版本     $VersionName (code $VersionCode, $Variant)" -ForegroundColor White
Write-Host "   大小     $sizeText（$finalSize 字节）" -ForegroundColor White
Write-Host "   SHA256   $($hash.Hash)" -ForegroundColor White
Write-Host "   用时     $([math]::Round($sw.Elapsed.TotalSeconds,1))s" -ForegroundColor DarkGray
Write-Host "   日志     $GradleLog" -ForegroundColor DarkGray
Write-Host "   体检     $contentReport" -ForegroundColor DarkGray
if ($checkLines.Count -gt 0) { Write-Host "   下载体检 $checkReport" -ForegroundColor DarkGray }
if ($lintNote) { Write-Host "   注意     $lintNote" -ForegroundColor Yellow }
if ($magiskZip) { Write-Host "   Magisk   $magiskZip" -ForegroundColor White }

Write-Host "`n安装到手机：" -ForegroundColor DarkGray
Write-Host "   powershell -ExecutionPolicy Bypass -File tools\install-apk.ps1" -ForegroundColor DarkGray
exit 0