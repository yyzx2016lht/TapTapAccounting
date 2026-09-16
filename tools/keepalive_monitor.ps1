# 进程存活监测器（A/B 验证用）
#
# 用电脑侧 adb 每 20 秒采样一次，独立记录 App 进程的存活/死亡时间线，
# 并顺带抓取系统 kill 日志，区分"原生崩溃"与"系统 force-stop"。
#
# 用法：
#   pwsh -File tools/keepalive_monitor.ps1                 # 默认监测 48 小时
#   pwsh -File tools/keepalive_monitor.ps1 -Hours 24
#   pwsh -File tools/keepalive_monitor.ps1 -Hours 48 -IntervalSeconds 30
#
# 产出（.tmp/keepalive_monitor/ 下）：
#   samples.csv   每次采样的原始记录
#   events.log    只在状态变化时追加：DEATH / ALIVE / USB-LOST
#   summary.txt   随时可看的汇总（存活率、死亡次数、平均存活时长）
#
# 停止：Ctrl+C，或删掉 .tmp/keepalive_monitor/STOP 文件
#
# 注意：监测期间手机需保持 adb 连接（USB 或无线调试），否则会记为 USB-LOST 空档。

param(
    [double]$Hours = 48,
    [int]$IntervalSeconds = 20,
    [string]$Package = 'com.taostudio.tapaccounting'
)

$ErrorActionPreference = 'Continue'
$outDir = Join-Path $PSScriptRoot '..\.tmp\keepalive_monitor'
$outDir = [System.IO.Path]::GetFullPath($outDir)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$csvPath     = Join-Path $outDir 'samples.csv'
$eventPath   = Join-Path $outDir 'events.log'
$summaryPath = Join-Path $outDir 'summary.txt'
$snapshotDir = Join-Path $outDir 'snapshots'
$stopPath    = Join-Path $outDir 'STOP'

New-Item -ItemType Directory -Force -Path $snapshotDir | Out-Null
Remove-Item $stopPath -ErrorAction SilentlyContinue

# 每隔这么久把系统侧状态原样落盘一份（logcat 会被系统清掉，必须自己留底）
$SnapshotEverySamples = 30

function Now { (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') }

function Log-Event([string]$line) {
    $text = "$(Now)`t$line"
    Add-Content -Path $eventPath -Value $text -Encoding UTF8
    Write-Host $text -ForegroundColor Yellow
}

function Write-Summary($rows, $deaths, $aliveSamples, $totalSamples, $usbLost) {
    $pct = if ($totalSamples -gt 0) { [math]::Round(100.0 * $aliveSamples / $totalSamples, 2) } else { 0 }
    $lines = @(
        "生成时间      : $(Now)",
        "包名          : $Package",
        "采样间隔      : ${IntervalSeconds}s",
        "样本总数      : $totalSamples",
        "进程存活样本  : $aliveSamples",
        "进程死亡样本  : $($totalSamples - $aliveSamples)",
        "观测存活率    : $pct %",
        "死亡事件次数  : $deaths",
        "USB 断连样本  : $usbLost"
    )
    if ($deaths -gt 0) {
        $lines += "折算日均死亡  : $([math]::Round($deaths * 24.0 * 3600 / ($totalSamples * $IntervalSeconds), 2)) 次/天"
    }
    $lines | Set-Content -Path $summaryPath -Encoding UTF8
    $lines | ForEach-Object { Write-Host $_ }
}

if (-not (Test-Path $csvPath)) {
    'timestamp,alive,pid,stopped,screen,fsType,fsTypes,standbyBucket' |
        Set-Content -Path $csvPath -Encoding UTF8
}

Write-Host "监测开始 $(Now)，时长 ${Hours}h，间隔 ${IntervalSeconds}s" -ForegroundColor Cyan
Write-Host "输出目录：$outDir" -ForegroundColor Cyan
Log-Event "MONITOR-START hours=$Hours interval=${IntervalSeconds}s"

$deadline = (Get-Date).AddHours($Hours)
$deaths = 0
$aliveSamples = 0
$totalSamples = 0
$usbLost = 0
$wasAlive = $null
$lastScreen = ''

while ((Get-Date) -lt $deadline) {
    if (Test-Path $stopPath) { Log-Event "MONITOR-STOP (STOP 文件)"; break }

    $alive = $false
    $pid0 = ''
    $stopped = ''
    $screen = ''
    $fsType = ''
    $fsTypes = ''
    $bucket = ''
    $adbOk = $true

    try {
        # 一次性把需要的 dumpsys 抓下来，减少 adb 往返
        $pid0 = (adb shell pidof $Package 2>$null | Out-String).Trim()
        $pkgDump = (adb shell "dumpsys package $Package" 2>$null | Out-String)
        $svcDump = (adb shell "dumpsys activity services $Package" 2>$null | Out-String)
        $powerDump = (adb shell "dumpsys power" 2>$null | Out-String)
        $bucket = (adb shell "am get-standby-bucket $Package" 2>$null | Out-String).Trim()

        if ([string]::IsNullOrWhiteSpace($pid0) -and [string]::IsNullOrWhiteSpace($pkgDump)) { $adbOk = $false }
    } catch {
        $adbOk = $false
    }

    if (-not $adbOk) {
        $usbLost++
        if ($wasAlive -ne 'usb') { Log-Event "USB-LOST"; $wasAlive = 'usb' }
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }

    $alive = -not [string]::IsNullOrWhiteSpace($pid0)

    if ($pkgDump -match 'User 0:.*?stopped=(\w+)') { $stopped = $Matches[1] }
    if ($powerDump -match 'mWakefulness=(\w+)')       { $screen  = $Matches[1] }
    if ($svcDump -match 'isForeground=(\w+)')         { $fsType  = $Matches[1] }
    if ($svcDump -match 'foregroundServiceType=([\w|]+)') { $fsTypes = $Matches[1] }

    $totalSamples++
    if ($alive) { $aliveSamples++ }

    $ts = Now
    "$ts,$alive,$pid0,$stopped,$screen,$fsType,$fsTypes,$bucket" |
        Add-Content -Path $csvPath -Encoding UTF8

    if ($alive) {
        if ($wasAlive -ne $true) { Log-Event "ALIVE  pid=$pid0 stopped=$stopped screen=$screen"; $wasAlive = $true }
    } else {
        $deaths++
        Log-Event "DEATH  pid=none stopped=$stopped screen=$screen bucket=$bucket"
        $wasAlive = $false
        # 死亡现场：抓一段 logcat 供事后判因（崩溃 vs force-stop）
        try {
            $snap = Join-Path $outDir "death_$(Get-Date -Format 'MMdd_HHmmss').logcat"
            adb logcat -d -v threadtime -t 400 2>$null |
                Select-String -Pattern "$Package|Osense|Force stop|force-stop|am_kill|ANR|Fatal signal" |
                ForEach-Object { $_.Line } | Set-Content -Path $snap -Encoding UTF8
        } catch { }
    }

    if ($lastScreen -ne $screen -and $screen -ne '') {
        $lastScreen = $screen
    }

    # 每 30 个样本刷一次汇总，方便随时查看进度
    if ($totalSamples % 30 -eq 0) {
        Write-Summary -rows $null -deaths $deaths -aliveSamples $aliveSamples `
            -totalSamples $totalSamples -usbLost $usbLost
    }

    # 定期把系统侧原始状态落盘：kill 日志、appops、FGS 状态、心跳流水
    if ($totalSamples % $SnapshotEverySamples -eq 0) {
        try {
            $tag = Get-Date -Format 'MMdd_HHmmss'
            $sb = [System.Text.StringBuilder]::new()
            [void]$sb.AppendLine("### snapshot $ts  alive=$alive pid=$pid0 stopped=$stopped screen=$screen")
            [void]$sb.AppendLine("--- appops ---")
            [void]$sb.AppendLine((adb shell cmd appops get $Package 2>$null | Out-String))
            [void]$sb.AppendLine("--- services ---")
            [void]$sb.AppendLine($svcDump)
            [void]$sb.AppendLine("--- kill-related logcat (last 300) ---")
            [void]$sb.AppendLine((adb logcat -d -v time -t 300 2>$null |
                Select-String -Pattern 'Osense|KillAction|Force stop|force-stop|am_kill|ANR|Fatal signal|lowmemory|lmkd' |
                ForEach-Object { $_.Line } | Out-String))
            [void]$sb.AppendLine("--- heartbeat tail ---")
            [void]$sb.AppendLine((adb shell "tail -40 /sdcard/Android/data/$Package/files/keepalive_heartbeat.txt" 2>$null | Out-String))
            $sb.ToString() | Set-Content -Path (Join-Path $snapshotDir "snapshot_$tag.txt") -Encoding UTF8
        } catch { }
    }

    Start-Sleep -Seconds $IntervalSeconds
}

Log-Event "MONITOR-END deaths=$deaths samples=$totalSamples alive=$aliveSamples"
Write-Host "`n===== 最终汇总 =====" -ForegroundColor Cyan
Write-Summary -rows $null -deaths $deaths -aliveSamples $aliveSamples `
    -totalSamples $totalSamples -usbLost $usbLost
