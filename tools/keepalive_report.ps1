# 存活流水分析器
#
# 读取 App 自记的 keepalive_heartbeat.txt，输出：
#   - 每次进程死亡的时刻、之前存活了多久
#   - 每日死亡次数
#   - 平均存活时长 / 存活率
#
# 用法：
#   pwsh -File tools/keepalive_report.ps1                      # 自动 adb pull 后分析
#   pwsh -File tools/keepalive_report.ps1 -Path <本地文件>
#
# 流水格式：每行 "yyyy-MM-dd HH:mm:ss START gap=Ns deaths=N" 或 "yyyy-MM-dd HH:mm:ss ALIVE"

param(
    [string]$Path = '',
    [string]$Package = 'com.taostudio.tapaccounting',
    [int]$GapThresholdSeconds = 180
)

$ErrorActionPreference = 'Continue'
$devicePath = "/sdcard/Android/data/$Package/files/keepalive_heartbeat.txt"

if (-not $Path) {
    $dir = Join-Path $PSScriptRoot '..\.tmp\keepalive_monitor'
    $dir = [System.IO.Path]::GetFullPath($dir)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $Path = Join-Path $dir 'keepalive_heartbeat.txt'
    Write-Host "从设备拉取 $devicePath" -ForegroundColor Cyan
    adb pull $devicePath $Path 2>&1 | Select-Object -Last 1
}

if (-not (Test-Path $Path)) { Write-Host "找不到流水文件：$Path" -ForegroundColor Red; exit 1 }

$lines = Get-Content $Path
$entries = @()
foreach ($l in $lines) {
    if ($l -match '^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (START|ALIVE)\b\s*(.*)$') {
        $entries += [pscustomobject]@{
            T    = [datetime]::ParseExact($Matches[1], 'yyyy-MM-dd HH:mm:ss', $null)
            Kind = $Matches[2]
            Rest = $Matches[3].Trim()
        }
    }
}

if ($entries.Count -eq 0) { Write-Host "流水里没有可解析的记录" -ForegroundColor Red; exit 1 }

Write-Host "`n===== 记录范围 =====" -ForegroundColor Cyan
Write-Host ("第一行 : " + $entries[0].T)
Write-Host ("最后行 : " + $entries[-1].T)
Write-Host ("总行数 : " + $entries.Count)
$spanHours = ($entries[-1].T - $entries[0].T).TotalHours
Write-Host ("覆盖时长: " + [math]::Round($spanHours, 1) + " 小时")

# 相邻两条 ALIVE 之间超过阈值 => 中间断过 => 进程死过一次
$deaths = @()
for ($i = 1; $i -lt $entries.Count; $i++) {
    if ($entries[$i].Kind -ne 'ALIVE') { continue }
    if ($entries[$i - 1].Kind -ne 'ALIVE') { continue }
    $gap = ($entries[$i].T - $entries[$i - 1].T).TotalSeconds
    if ($gap -gt $GapThresholdSeconds) {
        $deaths += [pscustomobject]@{
            DiedAt      = $entries[$i - 1].T
            BackAt      = $entries[$i].T
            DowntimeMin = [math]::Round($gap / 60.0, 1)
        }
    }
}

# START 行也可以定位死亡：START 说明进程刚起来
$starts = $entries | Where-Object { $_.Kind -eq 'START' }
$nativeCrashes = $entries | Where-Object { $_.Rest -like '*NATIVE-CRASH*' }

Write-Host "`n===== 汇总 =====" -ForegroundColor Cyan
Write-Host ("进程启动次数(START) : " + $starts.Count)
Write-Host ("心跳断档判定死亡     : " + $deaths.Count)
Write-Host ("原生崩溃标记         : " + $nativeCrashes.Count)
if ($spanHours -gt 0) {
    Write-Host ("折算日均死亡         : " + [math]::Round($deaths.Count * 24.0 / $spanHours, 1) + " 次/天")
}

if ($deaths.Count -gt 0) {
    $avgDown = ($deaths | Measure-Object DowntimeMin -Average).Average
    Write-Host ("平均停机时长         : " + [math]::Round($avgDown, 1) + " 分钟")
    Write-Host "`n----- 每次死亡明细 -----" -ForegroundColor Cyan
    $deaths | Format-Table -AutoSize

    Write-Host "----- 每日死亡次数 -----" -ForegroundColor Cyan
    $deaths | ForEach-Object { $_.DiedAt.ToString('yyyy-MM-dd') } |
        Group-Object | Sort-Object Name |
        ForEach-Object { "{0}  {1} 次" -f $_.Name, $_.Count }
}

if ($starts.Count -gt 0) {
    Write-Host "`n----- 进程启动明细 -----" -ForegroundColor Cyan
    $starts | Select-Object -First 40 | ForEach-Object { "  {0}   {1}" -f $_.T, $_.Rest }
}

if ($nativeCrashes.Count -gt 0) {
    Write-Host "`n----- 原生崩溃 -----" -ForegroundColor Red
    $nativeCrashes | ForEach-Object { "  {0}   {1}" -f $_.T, $_.Rest }
}
Write-Host ''
