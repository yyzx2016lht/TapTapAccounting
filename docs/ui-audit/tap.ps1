param(
  [int]$x, [int]$y, [string]$Name, [int]$Wait = 2
)
$dev = "172.20.1.3:8888"
adb -s $dev shell "input tap $x $y" 2>$null
if ($Name) {
  Start-Sleep -Seconds $Wait
  & "E:\FlipAccounting-AI\docs\ui-audit\shot.ps1" -Name $Name 2>$null
} else {
  Start-Sleep -Seconds $Wait
}
