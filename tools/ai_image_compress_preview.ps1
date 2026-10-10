# AI 图片压缩预览工具（命令行版）
#
# 用法：
#   pwsh -File tools/ai_image_compress_preview.ps1 图片1 [图片2 ...]     # 位置参数可传多张
#   pwsh -File tools/ai_image_compress_preview.ps1 -Path 图片1 -OutDir .\.tmp -NoOpen
#
# 与 App 端保持一致的参数：
#   AiImageCompressor.MAX_PIXELS   = 4_000_000
#   AiImageCompressor.JPEG_QUALITY = 88
#
# 压缩算法见同目录 AiImageCompress.ps1（GUI 版 ai_image_compress_gui.ps1 共用同一份核心）。
#
# 输出：源图同目录（或 -OutDir）下的 <名字>.ai-compressed.jpg，并打印前后尺寸/体积对比。

param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)][string[]]$Path,
    [int]$MaxPixels = 4000000,
    [int]$Quality = 88,
    [string]$OutDir = '',
    [switch]$NoOpen
)

$ErrorActionPreference = 'Stop'
# 在中文 Windows 控制台按 UTF-8 输出，避免中文提示乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

. (Join-Path $PSScriptRoot 'AiImageCompress.ps1')

foreach ($p in $Path) {
    try {
        $info = Compress-AiImage -Path $p -OutDir $OutDir -MaxPixels $MaxPixels -Quality $Quality
        Write-Host ('原图  {0}  {1}x{2}  {3:N0} KB' -f `
            (Split-Path $info.SourcePath -Leaf), $info.SrcW, $info.SrcH, ($info.SrcBytes / 1KB)) -ForegroundColor DarkGray
        Write-Host ('压缩  {0}  {1}x{2}  {3:N0} KB  ({4:P0}, sample={5})' -f `
            (Split-Path $info.OutPath -Leaf), $info.OutW, $info.OutH, ($info.OutBytes / 1KB),
            ($info.OutBytes / [Math]::Max(1, $info.SrcBytes)), $info.Sample) -ForegroundColor Green
        if (-not $NoOpen) { Start-Process -FilePath $info.OutPath }
    } catch {
        Write-Host $_ -ForegroundColor Red
    }
}
