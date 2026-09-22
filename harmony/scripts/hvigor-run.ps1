# 鸿蒙工程统一执行脚本(由 DEVxiangs\scripts\*.bat 调用)
#
#   check       环境自检(DevEco / SDK / node / hvigorw / hdc / 设备 / 本机 IP)
#   build       清理 + 编译出 HAP
#   build-fast  增量编译(不清理)
#   run         安装到设备并启动
#   launch      只启动(不安装)
#   log         只看本应用的设备日志(类似 Android 的 logcat)
#   log-all     看全部设备日志
#
# 说明:本文件必须保存为 **UTF-8 with BOM** —— Windows PowerShell 5.1 读无 BOM 的
# UTF-8 脚本会按系统 ANSI(GBK)解析,中文与破折号会造成语法错误。
# 修改后用 [System.Management.Automation.Language.Parser]::ParseFile() 验语法。

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('check', 'build', 'build-fast', 'run', 'launch', 'log', 'log-all')]
    [string]$Action,
    [string]$SdkHome = '',
    [string]$DevEcoHome = ''
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch {
    # 某些宿主不允许改编码:不影响功能
}

# ---------------- 小工具(必须定义在下面的常量之前:PowerShell 按顺序执行顶层语句)----------------
function Join-Safe {
    # Join-Path 在路径指向不存在的盘符时会抛 DriveNotFoundException,这里统一兜住
    param([string]$Base, [string]$Child)
    if (-not $Base) { return '' }
    return ($Base.TrimEnd('\', '/') + '\' + $Child.TrimStart('\', '/'))
}

# ---------------- 常量 ----------------
$ProjectRoot = (Resolve-Path (Join-Safe $PSScriptRoot '..')).Path
$BundleName = 'com.example.devxiangsu'
$AbilityName = 'EntryAbility'
$HapPath = Join-Safe $ProjectRoot 'entry\build\default\outputs\default\entry-default-unsigned.hap'
# 临时输出(构建日志 / hdc 输出)统一放工程根的 .cache 下:不依赖 %TEMP%(某些受限环境会把
# TEMP 指到别处),也方便你事后翻日志。
$CacheDir = Join-Safe $ProjectRoot '.cache'

function Write-Line { param([string]$Text = '') Write-Host $Text }
function Write-Ok { param([string]$Text) Write-Host $Text -ForegroundColor Green }
function Write-Warn2 { param([string]$Text) Write-Host $Text -ForegroundColor Yellow }
function Write-Err { param([string]$Text) Write-Host $Text -ForegroundColor Red }

function Get-DevEcoCandidates {
    $list = @()
    foreach ($p in @('D:\DevEco Studio', 'C:\DevEco Studio', 'E:\DevEco Studio')) { $list += $p }
    foreach ($envName in @('DEVECO_HOME', 'DEVECO_STUDIO_HOME', 'DEVECO_SDK_HOME')) {
        $v = [Environment]::GetEnvironmentVariable($envName)
        if ($v) {
            $list += $v
            # DEVECO_SDK_HOME 可能直接指向 <DevEco>\sdk
            if ((Split-Path $v -Leaf) -eq 'sdk') { $list += (Split-Path $v -Parent) }
        }
    }
    if ($env:LOCALAPPDATA) { $list += (Join-Safe $env:LOCALAPPDATA 'Huawei\DevEco Studio') }
    if ($env:ProgramFiles) { $list += (Join-Safe $env:ProgramFiles 'Huawei\DevEco Studio') }
    return $list
}

function Resolve-Env {
    # ---- 1) DevEco 根目录(找 hvigorw 与 node) ----
    $deveco = ''
    $search = @()
    if ($DevEcoHome) { $search += $DevEcoHome }
    if ($SdkHome -and ((Split-Path $SdkHome -Leaf) -eq 'sdk')) { $search += (Split-Path $SdkHome -Parent) }
    $search += (Get-DevEcoCandidates)
    foreach ($cand in $search) {
        if (-not $cand) { continue }
        $hvigorw = Join-Safe $cand 'tools\hvigor\bin\hvigorw.bat'
        if ($hvigorw -and (Test-Path $hvigorw) -and -not $deveco) { $deveco = (Resolve-Path $cand).Path }
    }
    return $deveco
}

function Get-Hvigorw { param([string]$DevEco) return (Join-Safe $DevEco 'tools\hvigor\bin\hvigorw.bat') }
function Get-NodeDir { param([string]$DevEco) return (Join-Safe $DevEco 'tools\node') }

function Resolve-Sdk {
    param([string]$DevEco, [string]$Explicit)
    if ($Explicit -and (Test-Path $Explicit)) { return (Resolve-Path $Explicit).Path }
    $cands = @()
    if ($DevEco) { $cands += (Join-Safe $DevEco 'sdk') }
    $envSdk = [Environment]::GetEnvironmentVariable('DEVECO_SDK_HOME')
    if ($envSdk) { $cands += $envSdk }
    foreach ($c in $cands) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    return ''
}

function Find-Hdc {
    param([string]$Sdk)
    if (-not $Sdk) { return '' }
    $cands = @()
    try {
        $cands = @(Get-ChildItem -Path $Sdk -Filter 'hdc.exe' -Recurse -File -ErrorAction SilentlyContinue)
    } catch {
        return ''
    }
    if ($cands.Count -eq 0) { return '' }
    # 优先 toolchains 目录下的
    $preferred = $cands | Where-Object { $_.DirectoryName -like '*toolchains*' } | Select-Object -First 1
    if ($preferred) { return $preferred.FullName }
    return $cands[0].FullName
}

function Get-Devices {
    param([string]$Hdc)
    if (-not $Hdc) { return @() }
    if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Path $CacheDir -Force | Out-Null }
    $out = Join-Safe $CacheDir 'hdc-targets.txt'
    $r = Invoke-CmdLine -Exe $Hdc -CmdArgs @('list', 'targets') -LogPath $out
    if (-not (Test-Path $out)) { return @() }
    $devices = @()
    foreach ($line in [System.IO.File]::ReadAllLines($out)) {
        $t = "$line".Trim()
        if ($t -eq '' -or $t -like '*[Empty]*' -or $t -like '*Empty*' -or $t -like '*Waiting*') { continue }
        $devices += $t
    }
    return $devices
}

function Get-LanIps {
    $ips = @()
    try {
        $ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
            Select-Object -ExpandProperty IPAddress -Unique)
    } catch {
        $ips = @()
    }
    return $ips
}

function Show-Env {
    param([string]$DevEco, [string]$Sdk, [string]$Hdc)
    Write-Line '========================================'
    Write-Line '  PixelGame HarmonyOS - environment'
    Write-Line '========================================'
    Write-Line ("  工程        : {0}" -f $ProjectRoot)
    if ($DevEco) { Write-Ok ("  DevEco      : {0}" -f $DevEco) } else { Write-Err '  DevEco      : 未找到(需要 DevEco Studio 的 tools\hvigor)' }
    if ($Sdk) { Write-Ok ("  SDK         : {0}" -f $Sdk) } else { Write-Err '  SDK         : 未找到(DEVECO_SDK_HOME)' }
    if ($DevEco) {
        $node = Join-Safe (Get-NodeDir -DevEco $DevEco) 'node.exe'
        if (Test-Path $node) { Write-Ok ("  node        : {0}" -f $node) } else { Write-Err ("  node        : 缺失 {0}" -f $node) }
        $hvigorw = Get-Hvigorw -DevEco $DevEco
        if (Test-Path $hvigorw) { Write-Ok '  hvigorw     : ok' } else { Write-Err ("  hvigorw     : 缺失 {0}" -f $hvigorw) }
    }
    if ($Hdc) { Write-Ok ("  hdc         : {0}" -f $Hdc) } else { Write-Err '  hdc         : 未找到(SDK 里的 toolchains\hdc.exe)' }

    Write-Line ''
    Write-Line '  --- 设备(hdc list targets) ---'
    $devices = Get-Devices -Hdc $Hdc
    if ($devices.Count -eq 0) {
        Write-Warn2 '  未检测到设备/模拟器。'
        Write-Warn2 '  · 模拟器:先在 DevEco Studio 的 Device Manager 里启动,再跑本脚本'
        Write-Warn2 '  · 真机  :打开「开发者选项 → USB 调试」并插上线'
    } else {
        foreach ($d in $devices) { Write-Ok ("  {0}" -f $d) }
    }

    Write-Line ''
    Write-Line '  --- 客户端该填的服务器地址 ---'
    $ips = Get-LanIps
    if ($ips.Count -eq 0) {
        Write-Warn2 '  没有找到局域网 IPv4,检查网卡'
    } else {
        foreach ($ip in $ips) { Write-Line ("  http://{0}:8000/" -f $ip) }
        Write-Warn2 '  鸿蒙端不能用 10.0.2.2 / localhost,必须用上面这种电脑局域网 IP'
    }

    Write-Line ''
    Write-Line '  --- 产物 ---'
    if (Test-Path $HapPath) {
        $info = Get-Item $HapPath
        Write-Ok ("  HAP: {0}  ({1:N2} MB, {2})" -f $info.Name, ($info.Length / 1MB), $info.LastWriteTime)
    } else {
        Write-Warn2 '  还没有 HAP,先跑 build-hap.bat'
    }
    Write-Line ''
}

# ---------------- 各动作 ----------------

function Invoke-Build {
    param([string]$DevEco, [string]$Sdk, [bool]$Clean)
    if (-not $DevEco) { Write-Err '[error] 找不到 DevEco Studio,无法编译。先跑 check-env.bat 看提示。'; return 1 }
    if (-not $Sdk) { Write-Err '[error] 找不到 SDK(DEVECO_SDK_HOME),无法编译。'; return 1 }

    $env:DEVECO_SDK_HOME = $Sdk
    $env:NODE_HOME = Get-NodeDir -DevEco $DevEco
    $hvigorw = Get-Hvigorw -DevEco $DevEco

    if ($Clean) {
        Write-Line '[1/2] 清理旧的编译产物 ...'
        $paths = @(
            (Join-Safe $ProjectRoot 'entry\build'),
            (Join-Safe $ProjectRoot 'entry\.test'),
            (Join-Safe $ProjectRoot 'build')
        )
        foreach ($p in $paths) {
            if (Test-Path $p) {
                try { Remove-Item $p -Recurse -Force -ErrorAction Stop; Write-Line ("      已删除 {0}" -f (Split-Path $p -Leaf)) }
                catch { Write-Warn2 ("      删不掉(可能被 DevEco 占用):{0}" -f $p) }
            }
        }
    }

    Write-Line ''
    Write-Line '[2/2] hvigorw assembleHap ...'
    Write-Line ''

    # ⚠ 两个坑都踩过了,这里按"最稳"的写法来:
    #   1) 不要套 cmd.exe 调 hvigorw —— cmd 会把带空格路径的引号吃掉
    #      (`D:\DevEco Studio\...` 会被拆成 `""D:\DevEco` → 命令找不到);
    #   2) 不要用 PowerShell 管道(2>&1 | ...)接子进程输出 —— 受限环境下子进程
    #      打不开命名管道,node 直接失败,表现为"输出全空 + 退出码拿不到"。
    #   直接调用 + 重定向到文件,两种环境都稳。
    #   3) 默认加 --no-daemon:hvigor 守护进程一旦崩(常见报错 Specification Limit
    #      Violation / nodeOptions.maxOldSpaceSize),后续每次构建都会莫名失败;
    #      --no-daemon 每次多花几秒,但不会再被守护进程拖累。
    if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Path $CacheDir -Force | Out-Null }
    $log = Join-Safe $CacheDir ('hvigor-build-{0}.log' -f (Get-Date -Format 'HHmmss'))
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    # 让 hvigorw 自己把 stdout/stderr 写进日志文件。
    #
    # 为什么用 Start-Process 的 -Redirect* 而不是 PowerShell 的 `*>`:
    #   · `*>` 在 Windows PowerShell 5.1 下把输出写成 UTF-16 并夹带 BOM,回读极易踩坑;
    #   · 直接 `& $hvigorw ...` 又会让 hvigor 的 WARN(stderr)在
    #     $ErrorActionPreference='Stop' 下被升级成致命错误,编译成功也报失败。
    # Start-Process -Wait 两头都躲开了:进程自己去写文件,我们只拿退出码和文件。
    if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = Start-Process -FilePath $hvigorw `
        -ArgumentList @('--mode', 'module', '-p', 'product=default', 'assembleHap', '--no-daemon') `
        -WorkingDirectory $ProjectRoot -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
    $rc = $proc.ExitCode
    $sw.Stop()
    # stderr 并进同一个日志文件,方便一起看
    if (Test-Path ($log + '.err')) {
        $errText = ([System.IO.File]::ReadAllText($log + '.err', [System.Text.Encoding]::UTF8))
        [System.IO.File]::AppendAllText($log, "`r`n" + $errText, [System.Text.Encoding]::UTF8)
        Remove-Item ($log + '.err') -Force -ErrorAction SilentlyContinue
    }
    if ($null -eq $rc) { $rc = 1 }

    # 就地解码日志(hvigorw 写出来是 UTF-8;万一被包成 UTF-16 也认得)。
    # ⚠ 这段刻意不抽成函数,而且用**强类型泛型列表**,不用 ArrayList / 管道:
    #   PowerShell 在"元素只有一个"时会把集合折成标量,后面 .Add() 直接报
    #   "Method invocation failed because [System.Boolean] does not contain a method
    #    named 'Add'" —— 之前那串"日志只剩一行 True"就是这个坑的另一个表现。
    [System.Collections.Generic.List[string]]$clean = New-Object 'System.Collections.Generic.List[string]'
    if (Test-Path $log) {
        $bytes = [System.IO.File]::ReadAllBytes($log)
        if ($bytes.Length -gt 0) {
            if ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
                $text = [System.Text.Encoding]::Unicode.GetString($bytes, 2, $bytes.Length - 2)
            } else {
                $text = [System.Text.Encoding]::UTF8.GetString($bytes)
            }
            foreach ($ln in ($text -split "`r?`n")) {
                $clean.Add(($ln -replace "\x1B\[[0-9;]*[A-Za-z]", ''))
            }
        }
    }
    if ($clean.Count -gt 0) {
        $from = [Math]::Max(0, $clean.Count - 22)
        $tail = @()
        for ($i = $from; $i -lt $clean.Count; $i++) { $tail += $clean[$i] }
        Write-Line ($tail -join "`n")
        Write-Line ''
    } else {
        Write-Warn2 ("没有拿到构建日志(路径:{0})" -f $log)
    }
    $errCount = 0
    foreach ($ln in $clean) { if ($ln -match 'ERROR|error:|BUILD FAILED|FAILURE') { $errCount++ } }

    Write-Line ''
    if ($rc -eq 0 -and (Test-Path $HapPath)) {
        $info = Get-Item $HapPath
        Write-Ok ("[ok] 编译成功({0}s):{1}" -f [int]$sw.Elapsed.TotalSeconds, $info.Name)
        Write-Ok ("     {0:N2} MB  ·  {1}" -f ($info.Length / 1MB), $info.FullName)
        Write-Line ("     完整日志:{0}" -f $log)
        Write-Line '     下一步:双击 run-hap.bat 装到设备上运行'
    } elseif ($rc -eq 0) {
        Write-Warn2 '[warn] hvigor 返回 0,但没找到 HAP 产物,检查上面的输出'
    } else {
        Write-Err ("[fail] 编译失败,退出码 {0}" -f $rc)
        if ($errCount -gt 0) { Write-Err ("       日志里有 {0} 处 ERROR,见:{1}" -f $errCount, $log) }
        Write-Line ''
        Write-Warn2 '常见原因:'
        Write-Warn2 '  1) ArkTS 语法错误:看日志里 ERROR 那一行的文件名与行号'
        Write-Warn2 '  2) DevEco Studio 正在编译同一个工程(构建锁冲突):等它跑完再试,或者关掉 IDE 构建'
        Write-Warn2 '  3) 首次跑需要联网下载 hvigor 依赖;若 ~/.hvigor/project_caches 里有指向旧安装'
        Write-Warn2 '     目录的 junction,删掉该目录让它重新引导'
    }
    return $rc
}

function Invoke-CmdLine {
    # 跑一条 cmd 命令,把 stdout+stderr 收进日志文件后读回来。
    #
    # 为什么不直接 `& $exe args *> $log`:
    #   1) hvigorw 会往 stderr 打警告,而本脚本设了 $ErrorActionPreference='Stop',
    #      PowerShell 会把"原生命令写了 stderr"升级成致命错误 → 明明编译成功却报失败;
    #   2) PowerShell 接子进程输出要走管道,受限环境下可能被拒。
    # 让 cmd 自己把两条流都重定向进文件,以上两点都不存在。
    param([string]$Exe, [string[]]$CmdArgs, [string]$LogPath)
    $inner = ('"{0}"' -f $Exe)
    foreach ($a in $CmdArgs) { $inner += (' "{0}"' -f $a) }
    $line = ('cmd /c ""{0} > ""{1}"" 2>&1"' -f $inner, $LogPath)
    cmd.exe /c $line
    $rc = $LASTEXITCODE
    if ($null -eq $rc) { $rc = 1 }
    $lines = @()
    if (Test-Path $LogPath) {
        $lines = @([System.IO.File]::ReadAllLines($LogPath, [System.Text.Encoding]::UTF8) |
            ForEach-Object { $_ -replace "\x1B\[[0-9;]*[A-Za-z]", '' })
    }
    return @{ rc = $rc; lines = $lines }
}

function Invoke-Hdc {
    # 跑一次 hdc 命令并把输出读回来
    param([string]$Hdc, [string[]]$HdcArgs)
    $out = Join-Safe $CacheDir 'hdc-out.txt'
    $r = Invoke-CmdLine -Exe $Hdc -CmdArgs $HdcArgs -LogPath $out
    return @{ rc = $r.rc; text = ($r.lines -join "`n") }
}

function Invoke-Install {
    param([string]$Hdc)
    if (-not (Test-Path $HapPath)) {
        Write-Err '[error] 没有 HAP 产物,先跑 build-hap.bat'
        return 1
    }
    Write-Line ("[install] {0}  ({1:N2} MB)" -f (Split-Path $HapPath -Leaf), ((Get-Item $HapPath).Length / 1MB))
    $r = Invoke-Hdc -Hdc $Hdc -HdcArgs @('install', '-r', $HapPath)
    $text = $r.text
    Write-Line $text.Trim()
    if ($text -match 'successfully') {
        Write-Ok '[ok] 安装成功'
        return 0
    }
    Write-Err '[fail] 安装失败'
    if ($text -match 'signature|sign|verify|code:9568320|9568289|9568256') {
        Write-Line ''
        Write-Warn2 '看错误码像是「签名」问题 —— 当前产物是未签名的 entry-default-unsigned.hap。'
        Write-Warn2 '解决:DevEco Studio → File → Project Structure → Signing Configs'
        Write-Warn2 '     → 勾选 Automatically generate signature(需登录华为账号)→ 再重新 build。'
        Write-Warn2 '之后本脚本就能直接装了(DevEco 会自动把签名配置写进 build-profile.json5)。'
    } elseif ($text -match 'device|target|connect') {
        Write-Line ''
        Write-Warn2 '像是设备侧问题:确认模拟器/真机还连着(hdc list targets 能看到)。'
    }
    return 1
}

function Invoke-Launch {
    param([string]$Hdc)
    Write-Line ("[launch] {0}/{1}" -f $BundleName, $AbilityName)
    $r = Invoke-Hdc -Hdc $Hdc -HdcArgs @('shell', 'aa', 'start', '-a', $AbilityName, '-b', $BundleName)
    Write-Line $r.text.Trim()
    if ($r.text -match 'start ability successfully|successfully') {
        Write-Ok '[ok] 已启动,抬头看设备屏幕'
        return 0
    }
    Write-Err '[fail] 启动失败(应用可能没装上)'
    return 1
}

function Invoke-Run {
    param([string]$DevEco, [string]$Sdk, [string]$Hdc)
    if (-not $Hdc) { Write-Err '[error] 找不到 hdc.exe,先跑 check-env.bat'; return 1 }

    $devices = Get-Devices -Hdc $Hdc
    if ($devices.Count -eq 0) {
        Write-Warn2 '没有检测到设备/模拟器。'
        Write-Line '  请先在 DevEco Studio 的 Device Manager 里启动一个模拟器(或插真机开 USB 调试),'
        Write-Line '  看到 hdc list targets 有输出后,再双击 run-hap.bat。'
        return 1
    }
    if ($devices.Count -gt 1) {
        Write-Warn2 ("检测到 {0} 台设备,将安装到第一台:{1}" -f $devices.Count, $devices[0])
    }

    $rc = Invoke-Install -Hdc $Hdc
    if ($rc -ne 0) { return $rc }
    Start-Sleep -Milliseconds 800
    return (Invoke-Launch -Hdc $Hdc)
}

function Invoke-Log {
    param([string]$Hdc, [bool]$All)
    if (-not $Hdc) { Write-Err '[error] 找不到 hdc.exe'; return 1 }
    $devices = Get-Devices -Hdc $Hdc
    if ($devices.Count -eq 0) { Write-Err '[error] 没有设备,先启动模拟器/连真机'; return 1 }

    # hilog 是持续输出:让它写进文件,我们再"跟读"文件并过滤。
    # 不直接用管道,是为了在被限制的环境里也能稳定工作。
    if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Path $CacheDir -Force | Out-Null }
    $log = Join-Safe $CacheDir 'hilog.txt'
    if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }
    Write-Line '正在读取设备日志(按 Ctrl+C 结束)...'
    Write-Line ''
    $proc = Start-Process -FilePath $Hdc -ArgumentList @('hilog') -NoNewWindow -PassThru `
        -RedirectStandardOutput $log
    if ($All) {
        Write-Warn2 '(全部日志模式:内容会很多,建议改用 log-hap.bat 只看本应用)'
    }
    $pos = 0
    try {
        while (-not $proc.HasExited) {
            Start-Sleep -Milliseconds 400
            if (-not (Test-Path $log)) { continue }
            try {
                $fs = [System.IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
                [void]$fs.Seek($pos, 'Begin')
                $sr = New-Object System.IO.StreamReader($fs)
                $chunk = $sr.ReadToEnd()
                $pos = $fs.Position
                $sr.Close()
                $fs.Close()
            } catch {
                continue
            }
            if (-not $chunk) { continue }
            foreach ($line in ($chunk -split "`n")) {
                if ($All -or $line -match 'devxiangsu|xiangsu|com\.example') {
                    Write-Host $line.TrimEnd()
                }
            }
        }
    } finally {
        if (-not $proc.HasExited) { try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { } }
    }
    return 0
}

# ---------------- 主流程 ----------------

Push-Location $ProjectRoot
try {
    $deveco = Resolve-Env
    $sdk = Resolve-Sdk -DevEco $deveco -Explicit $SdkHome
    $hdc = Find-Hdc -Sdk $sdk

    switch ($Action) {
        'check' { Show-Env -DevEco $deveco -Sdk $sdk -Hdc $hdc; exit 0 }
        'build' { exit (Invoke-Build -DevEco $deveco -Sdk $sdk -Clean $true) }
        'build-fast' { exit (Invoke-Build -DevEco $deveco -Sdk $sdk -Clean $false) }
        'run' { exit (Invoke-Run -DevEco $deveco -Sdk $sdk -Hdc $hdc) }
        'launch' { exit (Invoke-Launch -Hdc $hdc) }
        'log' { exit (Invoke-Log -Hdc $hdc -All $false) }
        'log-all' { exit (Invoke-Log -Hdc $hdc -All $true) }
    }
} finally {
    Pop-Location
}
