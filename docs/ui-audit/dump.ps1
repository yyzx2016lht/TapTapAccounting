$dev = "172.20.1.3:8888"
$tmp = "E:\FlipAccounting-AI\.tmp\ui_dump.xml"
New-Item -ItemType Directory -Force -Path "E:\FlipAccounting-AI\.tmp" | Out-Null
adb -s $dev shell "uiautomator dump /sdcard/_u.xml > /dev/null 2>&1" 2>$null
adb -s $dev pull /sdcard/_u.xml $tmp 2>&1 | Out-Null
$xml = Get-Content $tmp -Raw -Encoding UTF8

$nodes = [regex]::Matches($xml, '<node[^>]*>')
$out = @()
foreach ($n in $nodes) {
  $s = $n.Value
  $text = if ($s -match 'text="([^"]*)"') { $matches[1] } else { "" }
  $desc = if ($s -match 'content-desc="([^"]*)"') { $matches[1] } else { "" }
  $res  = if ($s -match 'resource-id="([^"]*)"') { $matches[1] } else { "" }
  $b    = if ($s -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            "{0},{1}-{2},{3}" -f $matches[1],$matches[2],$matches[3],$matches[4] } else { "" }
  $click= if ($s -match 'clickable="([^"]*)"') { $matches[1] } else { "" }
  $label = if ($text) { $text } elseif ($desc) { "[$desc]" } else { "" }
  if ($label) { $out += ("{0,-30} {1,-44} {2} clk={3}" -f $label, $res, $b, $click) }
}
$out | Sort-Object -Unique
