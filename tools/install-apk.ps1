<#
.SYNOPSIS
  ClipNest 剪巢 · 把 APK 安装到已连接的 Android 手机（adb install -r）。

.DESCRIPTION
  * 默认自动挑 APK：优先 03_releases\ClipNest 下最新的一份，其次 app\build\outputs 里的 release / debug 产物。
  * 自动识别手机状态（未连接 / 未授权 / 离线 / 多设备），给出中文提示，不会静默失败。
  * 可选 -Uninstall 先卸载旧版、-Launch 装完直接启动主界面。
  * 本机 adb 来自 I:\Download\Old_Bachkup\APPS\AAA\platform-tools\adb.exe。
  * 所有 adb 调用都通过 cmd 重定向到临时文件再读回：adb server 会继承句柄，
    直接管道捕获输出时容易卡住（无控制台环境尤甚），落文件最稳。

.PARAMETER Apk
  指定要安装的 APK 路径；不填则自动挑选。

.PARAMETER Serial
  多设备时指定设备序列号（adb devices 第一列）。

.PARAMETER Uninstall
  安装前先 adb uninstall com.clipnest.app（想清空数据时用）。

.PARAMETER Launch
  安装成功后自动启动 com.clipnest.app/.MainActivity。

.PARAMETER Help
  打印本帮助并退出。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-apk.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-apk.ps1 -Apk I:\DeepseekHarness\03_releases\ClipNest\ClipNest-1.0.0.apk -Launch

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\install-apk.ps1 -Serial 1234567890abcdef -Uninstall
#>
[CmdletBinding()]
param(
    [string]$Apk = '',
    [string]$Serial = '',
    [switch]$Uninstall,
    [switch]$Launch,
    [int]$AdbTimeoutSec = 25,
    [switch]$KeepAdbServer,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 0
}

function Write-Step([string]$text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "   [OK] $text" -ForegroundColor Green }
function Write-Warn2([string]$text) { Write-Host "   [提示] $text" -ForegroundColor Yellow }
function Fail([string]$text) {
    Write-Host "`n[安装失败] $text" -ForegroundColor Red
    if (Get-Command Stop-AdbServer -ErrorAction SilentlyContinue) { Stop-AdbServer }
    exit 1
}

$Root = if ($PSScriptRoot) { Split-Path -Parent $PSScriptRoot } else { (Get-Location).Path }
$PackageId = 'com.clipnest.app'
$ActivityId = "$PackageId/.MainActivity"

# ------------------------------------------------------------------ adb
Write-Step '定位 adb'
$Adb = 'I:\Download\Old_Bachkup\APPS\AAA\platform-tools\adb.exe'
if (-not (Test-Path $Adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $Adb = $cmd.Source }
}
if (-not $Adb -or -not (Test-Path $Adb)) {
    Fail "找不到 adb.exe。期望位置：I:\Download\Old_Bachkup\APPS\AAA\platform-tools\adb.exe；也可以把 platform-tools 加进 PATH。"
}
$script:Adb = $Adb
$script:KeepAdbServer = [bool]$KeepAdbServer
Write-Ok "adb = $Adb"

$AdbTmpDir = Join-Path $Root 'build\pack-logs'
New-Item -ItemType Directory -Force -Path $AdbTmpDir | Out-Null

function Invoke-Adb {
    <#
      执行 adb 并把输出落到临时文件再读回。
      * 用 .cmd 批处理 + cmd 重定向：adb server 会继承句柄，直接管道捕获会卡住。
      * 带超时：某些受限环境（如本机 DSH 沙箱）会拦截 127.0.0.1:5037，adb 会一直挂着。
    #>
    param(
        [Parameter(Mandatory = $true)][string[]]$AdbArgs
    )
    $id = [guid]::NewGuid().ToString('N')
    $bat = Join-Path $AdbTmpDir "adb-$id.cmd"
    $tmp = Join-Path $AdbTmpDir "adb-$id.log"
    $quoted = ($AdbArgs | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
        }) -join ' '
    $batText = "@echo off`r`n`"$Adb`" $quoted > `"$tmp`" 2>&1`r`n"
    [System.IO.File]::WriteAllText($bat, $batText, (New-Object System.Text.ASCIIEncoding))

    $timedOut = $false
    $code = -1
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = "/c `"$bat`""
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = $null
    try {
        $proc = [System.Diagnostics.Process]::Start($psi)
        if (-not $proc.WaitForExit([int]($AdbTimeoutSec * 1000))) {
            $timedOut = $true
            try { & cmd.exe /c "taskkill /T /F /PID $($proc.Id) > nul 2>&1" | Out-Null } catch { }
            try { $proc.Kill() } catch { }
        }
        else { $code = $proc.ExitCode }
    }
    finally {
        if (Test-Path $bat) { Remove-Item -LiteralPath $bat -Force -ErrorAction SilentlyContinue }
    }

    $lines = @()
    if (Test-Path $tmp) {
        $lines = @([System.IO.File]::ReadAllLines($tmp, [System.Text.Encoding]::UTF8) |
                ForEach-Object { "$_".Trim() } | Where-Object { $_ })
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
    return [pscustomobject]@{
        ExitCode = $code
        TimedOut = $timedOut
        Lines    = $lines
        Text     = ($lines -join "`n")
    }
}

function Stop-AdbServer {
    <#
      结束 adb server。
      注意：这里刻意不用 `adb kill-server` —— 在某些受限/无控制台环境里 kill-server 自己会卡住；
      直接按进程名结束更可靠，也保证不会留下持有句柄的 daemon（否则调用方 shell 退不干净）。
    #>
    if ($script:KeepAdbServer) { Write-Warn2 '按 -KeepAdbServer 保留 adb server 后台进程'; return }
    $procs = @(Get-Process -Name adb -ErrorAction SilentlyContinue)
    if ($procs.Count -eq 0) { return }
    foreach ($proc in $procs) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 250
    $left = @(Get-Process -Name adb -ErrorAction SilentlyContinue)
    if ($left.Count -gt 0) {
        Write-Warn2 ("仍有 adb 进程未结束：{0}（可能影响终端退出，可手动 Stop-Process）" -f (($left | ForEach-Object { $_.Id }) -join ', '))
    }
}
# ------------------------------------------------------------------ APK
Write-Step '选择 APK'
if (-not $Apk) {
    $candidates = New-Object System.Collections.Generic.List[string]
    $releaseRoot = 'I:\DeepseekHarness\03_releases\ClipNest'
    if (Test-Path $releaseRoot) {
        Get-ChildItem -LiteralPath $releaseRoot -Filter 'ClipNest-*.apk' -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
    foreach ($rel in @(
            "app\build\outputs\apk\release\app-release.apk",
            "app\build\outputs\apk\debug\app-debug.apk")) {
        $p = Join-Path $Root $rel
        if (Test-Path $p) { $candidates.Add($p) }
    }
    foreach ($c in $candidates) {
        if ((Get-Item -LiteralPath $c).Length -gt 1MB) { $Apk = $c; break }
    }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Warn2 '没有找到可安装的 APK。'
    Write-Warn2 '先构建：powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1'
    exit 2
}
$Apk = (Resolve-Path $Apk).Path
$apkItem = Get-Item -LiteralPath $Apk
$apkHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
Write-Ok ("APK    = {0}" -f $Apk)
Write-Ok ("大小   = {0:N1} MB" -f ($apkItem.Length / 1MB))
Write-Ok ("SHA256 = {0}" -f $apkHash)

# ------------------------------------------------------------------ 设备
Write-Step '检查设备'
$dev = Invoke-Adb -AdbArgs @('devices')
if ($dev.TimedOut) {
    Write-Host "`nadb devices 超过 $AdbTimeoutSec 秒没有响应。" -ForegroundColor Red
    Write-Host "常见原因：" -ForegroundColor Yellow
    Write-Host "  1. adb server 起不来：本机安全软件/受限环境拦截了 127.0.0.1:5037" -ForegroundColor Gray
    Write-Host "     → 试试 adb kill-server 后重跑；或换一台机器/终端执行" -ForegroundColor Gray
    Write-Host "  2. USB 驱动异常：设备管理器里看有没有「Android ADB Interface」黄色感叹号" -ForegroundColor Gray
    Write-Host "  3. 本机 DSH 沙箱会拦截 localhost 监听，此时 adb 不可用" -ForegroundColor Gray
    Fail "adb 无响应（超时 ${AdbTimeoutSec}s）"
}
if ($dev.ExitCode -ne 0) {
    Write-Host "`nadb devices 调用失败：" -ForegroundColor Red
    $dev.Lines | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    Fail "无法读取设备列表（退出码 $($dev.ExitCode)）"
}
$deviceLines = @($dev.Lines | Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline|bootloader|recovery|sideload|host|no permissions)' })

if ($deviceLines.Count -eq 0) {
    Write-Host "`n未检测到已连接手机。" -ForegroundColor Yellow
    Write-Host "请按下面步骤操作后重试：" -ForegroundColor Yellow
    Write-Host "  1. 用 USB 数据线连接手机（不是纯充电线）" -ForegroundColor Gray
    Write-Host "  2. 手机：设置 → 关于手机 → 连续点「版本号」7 次，开启开发者选项" -ForegroundColor Gray
    Write-Host "  3. 手机：设置 → 开发者选项 → 打开「USB 调试」" -ForegroundColor Gray
    Write-Host "  4. 手机上弹出「允许 USB 调试吗？」→ 勾选「始终允许」→ 确定" -ForegroundColor Gray
    Write-Host "  5. 重新运行本脚本（仍不行就 adb kill-server 后再试）" -ForegroundColor Gray
    Write-Host "`n也可以直接把 APK 拷进手机点击安装：" -ForegroundColor Yellow
    Write-Host "  $Apk" -ForegroundColor Gray
    Stop-AdbServer
    exit 1
}

$ready = @($deviceLines | Where-Object { $_ -match '\sdevice$' })
$unauthorized = @($deviceLines | Where-Object { $_ -match '\sunauthorized$' })
$offline = @($deviceLines | Where-Object { $_ -match '\soffline$' })
foreach ($line in $deviceLines) { Write-Host "   $line" -ForegroundColor Gray }

if ($ready.Count -eq 0) {
    if ($unauthorized.Count -gt 0) { Fail '设备未授权：请在手机上点「允许 USB 调试」，勾选「始终允许」后重试。' }
    if ($offline.Count -gt 0) { Fail '设备处于 offline：重新插拔 USB，或执行 adb kill-server 后再试。' }
    Fail '没有处于 device 状态（可安装）的设备。'
}

function Get-SerialOf([string]$line) {
    return ($line -split '\s+')[0]
}

$target = Get-SerialOf $ready[0]
if ($Serial) {
    $match = @($ready | Where-Object { (Get-SerialOf $_) -eq $Serial })
    if ($match.Count -eq 0) {
        $avail = (@($ready | ForEach-Object { Get-SerialOf $_ })) -join ', '
        Fail "指定的设备 $Serial 不在线（可用设备：$avail）"
    }
    $target = $Serial
}
elseif ($ready.Count -gt 1) {
    Write-Host "`n检测到多台设备，请用 -Serial 指定其中一台：" -ForegroundColor Yellow
    $ready | ForEach-Object { Write-Host "  -Serial $(Get-SerialOf $_)" -ForegroundColor Gray }
    Fail '存在多台设备，无法确定安装目标。'
}
Write-Ok "目标设备 = $target"

# ------------------------------------------------------------------ 卸载（可选）
if ($Uninstall) {
    Write-Step '卸载旧版本'
    $res = Invoke-Adb -AdbArgs @('-s', $target, 'uninstall', $PackageId)
    if ($res.TimedOut) { Write-Warn2 "adb uninstall 超时（${AdbTimeoutSec}s），跳过卸载继续安装" }
    $res.Lines | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }
    if ($res.Text -match 'Success') { Write-Ok '旧版本已卸载（应用数据一并清除）' }
    else { Write-Warn2 '卸载没有返回 Success，可能是本来就没装过（继续安装）' }
}

# ------------------------------------------------------------------ 安装
Write-Step 'adb install -r'
$res = Invoke-Adb -AdbArgs @('-s', $target, 'install', '-r', $Apk)
$res.Lines | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }

if ($res.TimedOut) {
    Fail "adb install 超时（${AdbTimeoutSec}s）：设备可能断线，或安装包过大时 USB 太慢。可加大 -AdbTimeoutSec 后重试。"
}
if ($res.ExitCode -eq 0 -and $res.Text -match 'Success') {
    Write-Ok '安装成功'
}
else {
    $reason = '未知原因'
    if ($res.Text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') { $reason = '设备上已装版本的签名不一致：先加 -Uninstall 卸载再装' }
    elseif ($res.Text -match 'INSTALL_FAILED_ALREADY_EXISTS') { $reason = '已存在同包名应用：加 -Uninstall 覆盖安装' }
    elseif ($res.Text -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') { $reason = '手机存储空间不足' }
    elseif ($res.Text -match 'INSTALL_FAILED_OLDER_SDK') { $reason = '手机系统低于 minSdk 26（需要 Android 8.0 及以上）' }
    elseif ($res.Text -match 'INSTALL_FAILED_VERSION_DOWNGRADE') { $reason = '设备上版本更高：加 -Uninstall 卸载后再装' }
    elseif ($res.Text -match 'INSTALL_PARSE_FAILED') { $reason = 'APK 解析失败，可能文件损坏（重新构建）' }
    elseif ($res.Text -match 'device offline') { $reason = '设备断开连接，重插 USB 后重试' }
    elseif ($res.Text -match 'more than one device') { $reason = '多台设备，请用 -Serial 指定' }
    elseif ($res.Text -match 'no devices') { $reason = '设备已断开，重新连接后重试' }
    Fail ("adb install 失败：$reason（退出码 $($res.ExitCode)）")
}

# ------------------------------------------------------------------ 启动（可选）
if ($Launch) {
    Write-Step '启动应用'
    $res = Invoke-Adb -AdbArgs @('-s', $target, 'shell', 'am', 'start', '-n', $ActivityId)
    $res.Lines | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }
    if ($res.Text -match 'Error|Exception') { Write-Warn2 '启动指令返回错误，请在手机上手动点开「剪巢」' }
    else { Write-Ok '已发送启动指令' }
}

# ------------------------------------------------------------------ 汇总
Write-Step '完成'
Write-Host "   设备   $target" -ForegroundColor White
Write-Host "   应用   $PackageId" -ForegroundColor White
Write-Host "   APK    $Apk" -ForegroundColor White
Write-Host "`n首次打开请到「设置」页授予通知权限（Android 13+）与悬浮窗权限，" -ForegroundColor DarkGray
Write-Host "否则后台常驻通知 / 悬浮球收录无法工作。" -ForegroundColor DarkGray
Stop-AdbServer
exit 0