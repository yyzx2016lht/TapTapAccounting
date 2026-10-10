# AI 图片压缩预览（GUI 版，WinForms）
#
# 用法：
#   pwsh -File tools/ai_image_compress_gui.ps1                        # 打开窗口后点「选择图片...」
#   pwsh -File tools/ai_image_compress_gui.ps1 -Path 图片.jpg          # 打开窗口并直接加载
#   也可以把图片拖拽到窗口里
#
# 功能：
#   - 左侧原图、右侧压缩后（同一套 App 端参数：总像素 ≤4MP、JPEG 88，见 AiImageCompress.ps1）
#   - 勾选「100% 显示」按原始像素放大查看，两侧滚动条同步，方便对比文字清晰度
#   - 「重新压缩」用当前参数重来；「保存压缩图...」把结果另存到指定位置

param(
    [Parameter(Position = 0)][string]$Path = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
. (Join-Path $PSScriptRoot 'AiImageCompress.ps1')

[System.Windows.Forms.Application]::EnableVisualStyles()

# ---------- 窗口骨架 ----------

$form = New-Object System.Windows.Forms.Form
$form.Text = 'AI 图片压缩预览（≤4MP / JPEG 88）'
$form.ClientSize = New-Object System.Drawing.Size(1280, 840)
$form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
$form.MinimumSize = New-Object System.Drawing.Size(900, 600)
$form.AllowDrop = $true

$root = New-Object System.Windows.Forms.TableLayoutPanel
$root.Dock = [System.Windows.Forms.DockStyle]::Fill
$root.ColumnCount = 1
$root.RowCount = 3
$root.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Absolute, 46))) | Out-Null
$root.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, 100))) | Out-Null
$root.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Absolute, 26))) | Out-Null

# ---------- 工具条 ----------

function New-ToolLabel([string]$text) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $text
    $l.AutoSize = $true
    $l.Margin = New-Object System.Windows.Forms.Padding(14, 8, 0, 0)
    return $l
}

$btnPick = New-Object System.Windows.Forms.Button
$btnPick.Text = '选择图片...'
$btnPick.AutoSize = $true
$btnPick.Margin = New-Object System.Windows.Forms.Padding(0, 4, 0, 0)

$txtMax = New-Object System.Windows.Forms.TextBox
$txtMax.Text = '4000000'
$txtMax.Width = 82
$txtMax.Margin = New-Object System.Windows.Forms.Padding(6, 6, 0, 0)

$txtQuality = New-Object System.Windows.Forms.TextBox
$txtQuality.Text = '88'
$txtQuality.Width = 42
$txtQuality.Margin = New-Object System.Windows.Forms.Padding(6, 6, 0, 0)

$btnRe = New-Object System.Windows.Forms.Button
$btnRe.Text = '重新压缩'
$btnRe.AutoSize = $true
$btnRe.Margin = New-Object System.Windows.Forms.Padding(16, 4, 0, 0)

$btnSave = New-Object System.Windows.Forms.Button
$btnSave.Text = '保存压缩图...'
$btnSave.AutoSize = $true
$btnSave.Enabled = $false
$btnSave.Margin = New-Object System.Windows.Forms.Padding(8, 4, 0, 0)

$chk100 = New-Object System.Windows.Forms.CheckBox
$chk100.Text = '100% 显示（放大对比文字）'
$chk100.AutoSize = $true
$chk100.Margin = New-Object System.Windows.Forms.Padding(20, 8, 0, 0)

$toolbar = New-Object System.Windows.Forms.FlowLayoutPanel
$toolbar.Dock = [System.Windows.Forms.DockStyle]::Fill
$toolbar.WrapContents = $false
$toolbar.Padding = New-Object System.Windows.Forms.Padding(8, 0, 8, 0)
$toolbar.Controls.AddRange(@(
    $btnPick,
    (New-ToolLabel '像素上限：'), $txtMax,
    (New-ToolLabel 'JPEG 质量：'), $txtQuality,
    $btnRe, $btnSave, $chk100
))

# ---------- 左右两侧（上图片、下信息条） ----------

function New-Side([string]$title) {
    $tbl = New-Object System.Windows.Forms.TableLayoutPanel
    $tbl.Dock = [System.Windows.Forms.DockStyle]::Fill
    $tbl.ColumnCount = 1
    $tbl.RowCount = 2
    $tbl.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, 100))) | Out-Null
    $tbl.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Absolute, 24))) | Out-Null

    $pnl = New-Object System.Windows.Forms.Panel
    $pnl.Dock = [System.Windows.Forms.DockStyle]::Fill
    $pnl.BackColor = [System.Drawing.Color]::FromArgb(32, 32, 32)

    $pic = New-Object System.Windows.Forms.PictureBox
    $pic.Dock = [System.Windows.Forms.DockStyle]::Fill
    $pic.SizeMode = [System.Windows.Forms.PictureBoxSizeMode]::Zoom
    $pic.BackColor = [System.Drawing.Color]::FromArgb(32, 32, 32)
    $pnl.Controls.Add($pic)

    $lbl = New-Object System.Windows.Forms.Label
    $lbl.Dock = [System.Windows.Forms.DockStyle]::Fill
    $lbl.TextAlign = [System.Drawing.ContentAlignment]::MiddleLeft
    $lbl.Padding = New-Object System.Windows.Forms.Padding(8, 0, 0, 0)
    $lbl.Text = $title

    $tbl.Controls.Add($pnl, 0, 0)
    $tbl.Controls.Add($lbl, 0, 1)
    return @($tbl, $pnl, $pic, $lbl)
}

$tblL, $pnlL, $picL, $lblL = New-Side '原图'
$tblR, $pnlR, $picR, $lblR = New-Side '压缩后'

$split = New-Object System.Windows.Forms.SplitContainer
$split.Dock = [System.Windows.Forms.DockStyle]::Fill
$split.Orientation = [System.Windows.Forms.Orientation]::Vertical
$split.SplitterWidth = 6
$split.Panel1.Controls.Add($tblL)
$split.Panel2.Controls.Add($tblR)

# ---------- 底部状态条 ----------

$status = New-Object System.Windows.Forms.Label
$status.Dock = [System.Windows.Forms.DockStyle]::Fill
$status.TextAlign = [System.Drawing.ContentAlignment]::MiddleLeft
$status.Padding = New-Object System.Windows.Forms.Padding(10, 0, 0, 0)
$status.Text = '选择或拖拽一张图片开始；压缩参数与 App 一致：总像素 ≤4MP、JPEG 88'

# ---------- 状态与逻辑 ----------

$script:lastSourcePath = ''
$script:lastOutPath = ''
$script:syncing = $false

function Get-Bitmap([string]$file) {
    $bytes = [System.IO.File]::ReadAllBytes($file)
    $ms = New-Object System.IO.MemoryStream(, $bytes)
    try {
        $img = [System.Drawing.Image]::FromStream($ms)
        try { return New-Object System.Drawing.Bitmap($img) }
        finally { $img.Dispose() }
    } finally { $ms.Dispose() }
}

function Update-ZoomMode {
    $use100 = $chk100.Checked
    foreach ($pair in @(@($pnlL, $picL), @($pnlR, $picR))) {
        $pnl = $pair[0]; $pic = $pair[1]
        if ($use100) {
            $pic.Dock = [System.Windows.Forms.DockStyle]::None
            $pic.SizeMode = [System.Windows.Forms.PictureBoxSizeMode]::AutoSize
            $pic.Location = New-Object System.Drawing.Point(0, 0)
            $pnl.AutoScroll = $true
        } else {
            $pnl.AutoScroll = $false
            $pic.Dock = [System.Windows.Forms.DockStyle]::Fill
            $pic.SizeMode = [System.Windows.Forms.PictureBoxSizeMode]::Zoom
        }
    }
}

function Show-Compressed([string]$file) {
    if (-not (Test-Path -LiteralPath $file)) {
        [System.Windows.Forms.MessageBox]::Show("找不到文件：`n$file", 'AI 图片压缩预览', 'OK', 'Warning') | Out-Null
        return
    }
    $max = 0
    if (-not [int]::TryParse($txtMax.Text.Trim(), [ref]$max) -or $max -lt 100000) {
        [System.Windows.Forms.MessageBox]::Show('像素上限请填 ≥100000 的整数', 'AI 图片压缩预览', 'OK', 'Warning') | Out-Null
        return
    }
    $q = 0
    if (-not [int]::TryParse($txtQuality.Text.Trim(), [ref]$q) -or $q -lt 1 -or $q -gt 100) {
        [System.Windows.Forms.MessageBox]::Show('JPEG 质量请填 1-100 的整数', 'AI 图片压缩预览', 'OK', 'Warning') | Out-Null
        return
    }

    $form.Cursor = [System.Windows.Forms.Cursors]::WaitCursor
    try {
        $outDir = Join-Path ([System.IO.Path]::GetTempPath()) 'ai_image_compress_preview'
        $info = Compress-AiImage -Path $file -OutDir $outDir -MaxPixels $max -Quality $q

        $newOrig = Get-Bitmap $info.SourcePath
        $newComp = Get-Bitmap $info.OutPath
        if ($picL.Image) { $picL.Image.Dispose() }
        if ($picR.Image) { $picR.Image.Dispose() }
        $picL.Image = $newOrig
        $picR.Image = $newComp

        $script:lastSourcePath = $info.SourcePath
        $script:lastOutPath = $info.OutPath
        $btnSave.Enabled = $true

        $ratio = $info.OutBytes / [Math]::Max(1, $info.SrcBytes)
        $lblL.Text = '原图   {0}×{1}   {2:N0} KB' -f $info.SrcW, $info.SrcH, ($info.SrcBytes / 1KB)
        $lblR.Text = '压缩后 {0}×{1}   {2:N0} KB（{3:P0}）' -f $info.OutW, $info.OutH, ($info.OutBytes / 1KB), $ratio

        $dims = if ($info.OutW -eq $info.SrcW -and $info.OutH -eq $info.SrcH) {
            '尺寸保持原分辨率'
        } else {
            '尺寸等比缩到 {0}×{1}' -f $info.TargetW, $info.TargetH
        }
        $status.Text = '上限 {0:N0}px · 质量 {1} · sample={2} · {3} · 输出：{4}' -f `
            $info.MaxPixels, $info.Quality, $info.Sample, $dims, $info.OutPath

        Update-ZoomMode
    } catch {
        [System.Windows.Forms.MessageBox]::Show("压缩失败：`n$($_.Exception.Message)", 'AI 图片压缩预览', 'OK', 'Warning') | Out-Null
    } finally {
        $form.Cursor = [System.Windows.Forms.Cursors]::Default
    }
}

# 100% 模式下左右滚动同步
$syncScroll = {
    param($from, $to)
    if ($script:syncing -or -not $chk100.Checked) { return }
    $script:syncing = $true
    try {
        $to.AutoScrollPosition = New-Object System.Drawing.Point(
            $from.HorizontalScroll.Value, $from.VerticalScroll.Value)
    } catch {
        # 滚动位置越界时忽略
    } finally {
        $script:syncing = $false
    }
}

# ---------- 事件 ----------

$btnPick.Add_Click({
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Title = '选择要压缩的图片'
    $dlg.Filter = '图片文件|*.jpg;*.jpeg;*.png;*.webp;*.bmp;*.gif;*.heic;*.heif|所有文件|*.*'
    if ($dlg.ShowDialog($form) -eq [System.Windows.Forms.DialogResult]::OK) {
        Show-Compressed $dlg.FileName
    }
    $dlg.Dispose()
})

$btnRe.Add_Click({
    if ($script:lastSourcePath) { Show-Compressed $script:lastSourcePath }
})

$btnSave.Add_Click({
    if (-not $script:lastOutPath) { return }
    $dlg = New-Object System.Windows.Forms.SaveFileDialog
    $dlg.Title = '保存压缩后的图片'
    $dlg.Filter = 'JPEG 图片|*.jpg'
    $dlg.FileName = ([System.IO.Path]::GetFileNameWithoutExtension($script:lastSourcePath)) + '.ai-compressed.jpg'
    if ($dlg.ShowDialog($form) -eq [System.Windows.Forms.DialogResult]::OK) {
        [System.IO.File]::Copy($script:lastOutPath, $dlg.FileName, $true)
        $status.Text = "已保存：$($dlg.FileName)"
    }
    $dlg.Dispose()
})

$chk100.Add_CheckedChanged({ Update-ZoomMode })

$pnlL.Add_Scroll({ & $syncScroll $pnlL $pnlR })
$pnlR.Add_Scroll({ & $syncScroll $pnlR $pnlL })

$form.Add_DragEnter({
    if ($_.Data.GetDataPresent([System.Windows.Forms.DataFormats]::FileDrop)) {
        $_.Effect = [System.Windows.Forms.DragDropEffects]::Copy
    }
})

$form.Add_DragDrop({
    $files = $_.Data.GetData([System.Windows.Forms.DataFormats]::FileDrop)
    if ($files -and $files.Count -gt 0) { Show-Compressed $files[0] }
})

$form.Add_Shown({
    $split.SplitterDistance = [int]($split.Width / 2)
    if ($Path) { Show-Compressed $Path }
})

$form.Add_FormClosed({
    if ($picL.Image) { $picL.Image.Dispose() }
    if ($picR.Image) { $picR.Image.Dispose() }
})

# ---------- 组装并运行 ----------

$root.Controls.Add($toolbar, 0, 0)
$root.Controls.Add($split, 0, 1)
$root.Controls.Add($status, 0, 2)
$form.Controls.Add($root)

[void][System.Windows.Forms.Application]::Run($form)
