param(
  [Parameter(Mandatory=$true)][string]$Image,
  [Parameter(Mandatory=$true)][string]$Points   # "x,y;x,y;x,y"
)
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($Image)
Write-Output ("image: {0}x{1}" -f $bmp.Width, $bmp.Height)

function Get-Hex([int]$x, [int]$y) {
  $c = $bmp.GetPixel($x, $y)
  return "#{0:X2}{1:X2}{2:X2}" -f $c.R, $c.G, $c.B
}
function Get-Lum([string]$hex) {
  $h = $hex.TrimStart('#')
  $r = [Convert]::ToInt32($h.Substring(0,2),16)/255.0
  $g = [Convert]::ToInt32($h.Substring(2,2),16)/255.0
  $b = [Convert]::ToInt32($h.Substring(4,2),16)/255.0
  if ($r -le 0.03928) { $rl = $r/12.92 } else { $rl = [Math]::Pow(($r+0.055)/1.055, 2.4) }
  if ($g -le 0.03928) { $gl = $g/12.92 } else { $gl = [Math]::Pow(($g+0.055)/1.055, 2.4) }
  if ($b -le 0.03928) { $bl = $b/12.92 } else { $bl = [Math]::Pow(($b+0.055)/1.055, 2.4) }
  return 0.2126*$rl + 0.7152*$gl + 0.0722*$bl
}
function Get-Contrast([string]$h1, [string]$h2) {
  $l1 = Get-Lum $h1
  $l2 = Get-Lum $h2
  if ($l1 -lt $l2) { $tmp=$l1; $l1=$l2; $l2=$tmp }
  return [Math]::Round(($l1+0.05)/($l2+0.05), 2)
}

foreach ($p in $Points.Split(';')) {
  if (-not $p.Trim()) { continue }
  $xy = $p.Split(',')
  $x = [int]$xy[0]; $y = [int]$xy[1]
  $hex = Get-Hex $x $y
  $lum = Get-Lum $hex
  $vsW = Get-Contrast $hex "FFFFFF"
  $vsB = Get-Contrast $hex "000000"
  Write-Output ("({0,4},{1,4}) {2}  relLum={3,-8:F4}  vsWhite={4,-6}  vsBlack={5,-6}" -f $x,$y,$hex,$lum,$vsW,$vsB)
}
$bmp.Dispose()
