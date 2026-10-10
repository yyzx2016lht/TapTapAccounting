# AI 图片压缩核心（与 App 端 AiImageCompressor 同参数），供 CLI / GUI 共用。
# 由 ai_image_compress_preview.ps1 与 ai_image_compress_gui.ps1 点源加载。
#
# 算法（对应 app/src/main/java/com/taostudio/tapaccounting/AiImageCompressor.kt）：
#   1. 目标尺寸：按面积等比缩到总像素 ≤ MaxPixels，不放大；
#   2. 解码采样：inSampleSize 取 2 的幂次，使解码尺寸 ≥ 目标尺寸的 75%
#      （Android 内存兜底；采样用最近邻，对应 BitmapFactory 的 inSampleSize）；
#   3. 缩放：解码尺寸 ≥ 目标尺寸时用双线性缩到目标尺寸，否则保持解码尺寸（不放大）；
#   4. JPEG 质量 88 落盘。
#
# 注意：System.Drawing 的缩放与 Android Skia 不完全逐像素一致（同量级近似），
#       最终以 App 实测为准。

Add-Type -AssemblyName System.Drawing

$script:AiImageJpegCodec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
    Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
if (-not $script:AiImageJpegCodec) { throw '找不到系统 JPEG 编码器' }

function Compress-AiImage {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$OutDir = '',
        [int]$MaxPixels = 4000000,
        [int]$Quality = 88
    )

    $full = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $full)) { throw "找不到文件：$full" }

    $dir = if ($OutDir) { [System.IO.Path]::GetFullPath($OutDir) } else { Split-Path $full -Parent }
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $base = [System.IO.Path]::GetFileNameWithoutExtension($full)
    $out = Join-Path $dir "$base.ai-compressed.jpg"

    # 用内存流读入，避免占住原文件句柄
    $bytes = [System.IO.File]::ReadAllBytes($full)
    $ms = New-Object System.IO.MemoryStream(, $bytes)
    $src = [System.Drawing.Bitmap]::FromStream($ms)

    $decoded = $null
    $final = $null
    try {
        $srcW = $src.Width
        $srcH = $src.Height

        # 1. 目标尺寸（与 Kotlin targetSize() 一致）
        $scale = [Math]::Min(1.0, [Math]::Sqrt([double]$MaxPixels / ([double]$srcW * $srcH)))
        $targetW = [Math]::Max(1, [Math]::Round($srcW * $scale, [MidpointRounding]::AwayFromZero))
        $targetH = [Math]::Max(1, [Math]::Round($srcH * $scale, [MidpointRounding]::AwayFromZero))

        # 2. 解码采样（与 Kotlin 的 sample 循环一致：解码尺寸 ≥ 目标 75%）
        $sample = 1
        while (([Math]::Floor($srcW / ($sample * 2)) -ge [Math]::Floor($targetW * 3 / 4)) -and
               ([Math]::Floor($srcH / ($sample * 2)) -ge [Math]::Floor($targetH * 3 / 4))) {
            $sample *= 2
        }
        if ($sample -gt 1) {
            $decodedW = [Math]::Max(1, [int][Math]::Ceiling($srcW / $sample))
            $decodedH = [Math]::Max(1, [int][Math]::Ceiling($srcH / $sample))
            $decoded = New-Object System.Drawing.Bitmap($decodedW, $decodedH)
            $g = [System.Drawing.Graphics]::FromImage($decoded)
            try {
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
                $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
                $g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $decodedW, $decodedH)))
            } finally { $g.Dispose() }
        } else {
            $decoded = $src
        }

        # 3. 缩到目标尺寸（不放大）
        $finalW = [Math]::Min($targetW, $decoded.Width)
        $finalH = [Math]::Min($targetH, $decoded.Height)
        if ($finalW -eq $decoded.Width -and $finalH -eq $decoded.Height) {
            $final = $decoded
        } else {
            $final = New-Object System.Drawing.Bitmap($finalW, $finalH)
            $g = [System.Drawing.Graphics]::FromImage($final)
            try {
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBilinear
                $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
                $g.DrawImage($decoded, (New-Object System.Drawing.Rectangle(0, 0, $finalW, $finalH)))
            } finally { $g.Dispose() }
        }

        # 4. JPEG 落盘
        $encParams = New-Object System.Drawing.Imaging.EncoderParameters(1)
        $encParams.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter(
            [System.Drawing.Imaging.Encoder]::Quality, [long]$Quality)
        $final.Save($out, $script:AiImageJpegCodec, $encParams)

        [pscustomobject]@{
            SourcePath = $full
            OutPath    = $out
            SrcW       = $srcW
            SrcH       = $srcH
            SrcBytes   = $bytes.Length
            TargetW    = $targetW
            TargetH    = $targetH
            OutW       = $finalW
            OutH       = $finalH
            OutBytes   = (Get-Item -LiteralPath $out).Length
            Sample     = $sample
            MaxPixels  = $MaxPixels
            Quality    = $Quality
        }
    } finally {
        if ($final -and -not [object]::ReferenceEquals($final, $decoded)) { $final.Dispose() }
        if ($decoded -and -not [object]::ReferenceEquals($decoded, $src)) { $decoded.Dispose() }
        $src.Dispose()
        $ms.Dispose()
    }
}
