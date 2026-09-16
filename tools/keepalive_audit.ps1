# 后台存活核查脚本（HyperOS / Android 16）
#
# 用途：修复原生崩溃后，量化"进程还是不是在被系统干掉"。
# 用法：pwsh -File tools/keepalive_audit.ps1
#
# 判读：
#   - "原生崩溃(tombstone)" 数量不再增长  -> TFLite 竞态修复生效
#   - "系统重启次数/天" 明显下降          -> 崩溃就是主因
#   - 仍然约 15~20 次/天                  -> 主因是 ROM 层 force-stop，需要自启动白名单

$ErrorActionPreference = 'Continue'
$pkg = 'com.taostudio.tapaccounting'
$short = 'tapaccounting'

function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }

Section '设备'
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
Write-Host ("MIUI/HyperOS: " + (adb shell getprop ro.miui.ui.version.name))

Section '进程与前台服务状态'
$pid0 = (adb shell pidof $pkg) -join ' ' -replace '\s+$',''
Write-Host "pid: $pid0"
adb shell dumpsys activity services $pkg |
    Select-String -Pattern 'isForeground=|foregroundServiceType|startRequested'

Section '后台限制相关设置'
adb shell cmd appops get $pkg |
    Select-String -Pattern 'RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|WAKE_LOCK|START_FOREGROUND|HIGH_SAMPLING|access_background_sensor'
Write-Host ("standby-bucket: " + (adb shell am get-standby-bucket $pkg))
Write-Host ("在 Doze 白名单: " + [bool](adb shell dumpsys deviceidle whitelist | Select-String $pkg))
Write-Host ("后台受限(backgroundRestricted): " + (adb shell dumpsys package $pkg | Select-String -Pattern 'stopped=|backgroundRestricted' | Select-Object -First 2))

Section '原生崩溃（tombstone）统计'
$tombs = adb shell "grep -l $short /data/tombstones/tombstone_* 2>/dev/null" |
    Where-Object { $_ -notmatch '\.pb' }
Write-Host ("本应用 tombstone 总数: " + ($tombs | Measure-Object).Count)
if ($tombs) {
    Write-Host "最近 5 个："
    $tombs | Select-Object -Last 5 | ForEach-Object {
        $ts = adb shell "grep -m1 'Timestamp:' $_"
        $tid = adb shell "grep -m1 'name: ' $_"
        Write-Host ("  $_  $ts")
    }
}

Section 'Java 崩溃日志'
adb shell "tail -c 4000 /sdcard/Android/data/$pkg/files/crash_logs.txt 2>/dev/null" |
    Select-String -Pattern '^\[CRASH\] Thread=' | Select-Object -Last 5

Section 'Shizuku 复活守护进程'
adb shell "ls -la /data/local/tmp/ 2>/dev/null | grep -i tapaccounting"
Write-Host '--- 每日重启次数（shizuku recovery log）---'
adb shell "grep 'restart succeeded' /data/local/tmp/tapaccounting-shizuku-recovery.log 2>/dev/null | tail -400" |
    ForEach-Object { ($_ -split ' ')[0] } |
    Group-Object | Sort-Object Name |
    ForEach-Object { Write-Host ("  {0}  {1}" -f $_.Name, $_.Count) }

Section '应用自身日志中的心跳中断'
adb shell "tail -c 6000 /sdcard/Android/data/$pkg/files/app_logs.txt 2>/dev/null" |
    Select-String -Pattern 'ProcessExitLogger|NATIVE CRASH|KeepAliveDiag' | Select-Object -Last 10

Write-Host ''
