param([Parameter(Mandatory=$true)][string]$Name)
$ErrorActionPreference = "Continue"
$dev = "172.20.1.3:8888"
$dir = "E:\FlipAccounting-AI\docs\ui-audit\screenshots"
$out = Join-Path $dir "$Name.png"

# always make sure the device is awake + unlocked + app foregrounded
$awake = adb -s $dev shell "dumpsys power | grep mWakefulness=" 2>$null
if ($awake -notmatch "Awake") {
  adb -s $dev shell "input keyevent KEYCODE_WAKEUP" 2>$null | Out-Null
  Start-Sleep -Milliseconds 700
  adb -s $dev shell "wm dismiss-keyguard" 2>$null | Out-Null
  Start-Sleep -Milliseconds 700
}

adb -s $dev shell "screencap -p /sdcard/_s.png" 2>$null | Out-Null
adb -s $dev pull /sdcard/_s.png $out 2>&1 | Out-Null
adb -s $dev shell "rm -f /sdcard/_s.png" 2>$null | Out-Null

$len = (Get-Item $out).Length
if ($len -gt 2000000) {
  Write-Output "ASLEEP $Name len=$len"
} else {
  Write-Output ("OK {0} {1}KB" -f $Name, [math]::Round($len/1KB))
}
