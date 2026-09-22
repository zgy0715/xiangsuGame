# 把作品说明书 HTML 转成 Word(.docx)
#
# 为什么用 Word COM:Office 自己的排版引擎转出来的效果最准
# (字体、行距、表格边框、图注居中都能保留),比第三方库可靠。
#
# 处理细节:
#   1) 先把不存在的插图从 HTML 里摘掉 —— 否则 Word 会画出破图占位框,
#      交上去很难看;真实存在的图(如 docs/images/image1.jpeg)正常嵌入。
#      转完后原 HTML 会被还原,不受影响。
#   2) 存成 ASCII 文件名,再用 .NET 改名成中文名(避开 COM 的编码坑)。
#   3) 全程 try/finally,保证 Word 进程一定被关掉。

param(
    [string]$Root = 'C:\Users\zgy07\Desktop\26xiaoxueqi\xiangsuGame',
    [string]$HtmlName = '作品说明书-最终版.html',
    [string]$OutName = '作品说明书-最终版.docx'
)

$ErrorActionPreference = 'Stop'

$htmlPath = Join-Path $Root $HtmlName
if (-not (Test-Path $htmlPath)) { throw "找不到 HTML:$htmlPath" }

$tmpAscii = Join-Path $Root '_conv_input.html'
$docxAscii = Join-Path $Root '_conv_output.docx'
$docxFinal = Join-Path $Root $OutName

# ---- 1) 生成"只保留真实存在的图"的临时 HTML ----
$text = [System.IO.File]::ReadAllText($htmlPath, [System.Text.Encoding]::UTF8)
$kept = 0
$dropped = @()
$pattern = '<div class="fig"><img src="([^"]+)"[^>]*></div>\s*<p class="fcap">([^<]*)</p>'
$evaluator = [System.Text.RegularExpressions.MatchEvaluator]{
    param($m)
    $rel = $m.Groups[1].Value
    $cap = $m.Groups[2].Value
    $abs = Join-Path $Root ($rel -replace '/', '\')
    if (Test-Path $abs) {
        $script:kept++
        return $m.Value
    }
    $script:dropped += $cap
    # 图片不存在:整块删掉(连图注一起),避免破图占位
    return ''
}
$text = [regex]::Replace($text, $pattern, $evaluator)
[System.IO.File]::WriteAllText($tmpAscii, $text, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[1/3] 插图检查:嵌入 $kept 张;跳过 $(($dropped | Measure-Object).Count) 张(文件不存在)"
foreach ($d in $dropped) { Write-Host "        跳过:$d" }

# ---- 2) Word COM 转换 ----
Write-Host "[2/3] 调用 Word 转换 ..."
$word = $null
$doc = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0        # wdAlertsNone
    $word.Options.PrintBackgrounds = $true

    $doc = $word.Documents.Open($tmpAscii, [ref]$false, [ref]$false)
    # 16 = wdFormatDocumentDefault(.docx)
    if (Test-Path $docxAscii) { Remove-Item $docxAscii -Force }
    try {
        $doc.SaveAs2($docxAscii, 16)
    } catch {
        $doc.SaveAs($docxAscii, 16)
    }
    $pages = $doc.ComputeStatistics(2)   # 2 = wdStatisticPages
    $words = $doc.ComputeStatistics(0)   # 0 = wdStatisticWords
    Write-Host "        转换完成:约 $pages 页 / $words 字"
    $doc.Close([ref]$false)
    $doc = $null
} finally {
    if ($doc -ne $null) { try { $doc.Close([ref]$false) } catch { } }
    if ($word -ne $null) {
        try { $word.Quit() } catch { }
        try { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null } catch { }
    }
    [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}

# ---- 3) 改成中文名并清理 ----
Write-Host "[3/3] 整理产物 ..."
if (-not (Test-Path $docxAscii)) { throw '转换未生成 docx' }
if (Test-Path $docxFinal) { Remove-Item $docxFinal -Force }
Move-Item $docxAscii $docxFinal
Remove-Item $tmpAscii -Force -ErrorAction SilentlyContinue

$info = Get-Item $docxFinal
Write-Host ""
Write-Host ("[ok] 已生成:{0}" -f $info.FullName)
Write-Host ("     大小 {0:N0} KB,时间 {1}" -f ($info.Length / 1KB), $info.LastWriteTime)
