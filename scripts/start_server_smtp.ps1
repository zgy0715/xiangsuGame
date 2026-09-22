# 启动带邮件发信配置的服务端(由 scripts\start-server-smtp.bat 调用)
#
# 做四件事:
#   1. 检查端口是否已被占用(并在你确认后清掉占用的进程,避免"改了代码却没生效");
#   2. 读取 server/smtp.env 里的环境变量(缺失或填了占位符都只是警告,不阻断启动);
#   3. 打印本次生效的配置(授权码一律脱敏)+ 本机所有可用的局域网地址(照抄进 App 即可);
#   4. 在 0.0.0.0 上监听端口启动 FastAPI —— 安卓端与鸿蒙端都能连。
#
# 用法:scripts\start-server-smtp.bat [端口]   端口默认 8000
#
# 密钥去向:只存在于 smtp.env(已在 .gitignore 中)与本进程/子进程的环境变量里,
# 不会写入数据库、日志或代码。要换邮箱,改 smtp.env 即可。

param(
    [int]$Port = 8000
)

$ErrorActionPreference = 'Stop'
# 控制台按 UTF-8 输出,中文提示才不会变成乱码(bat 里已 chcp 65001)。
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
} catch {
    # 某些宿主不允许改编码:不影响功能,忽略
}
# 本脚本位于 <项目根>/scripts/ :取脚本自身路径的目录,其父目录即项目根。
# 注意:$PSScriptRoot 在部分调用方式(如 -File + 相对路径)下可能为空,
# 故优先用 $MyInvocation.MyCommand.Path,并对其取目录。
$selfPath = $MyInvocation.MyCommand.Path
if (-not $selfPath) { $selfPath = $PSCommandPath }
if (-not $selfPath -and $PSScriptRoot) { $selfPath = Join-Path $PSScriptRoot 'start_server_smtp.ps1' }
if (-not $selfPath) {
    Write-Host '[error] cannot resolve script path; run it as: scripts\start-server-smtp.bat' -ForegroundColor Red
    exit 1
}
$scriptDir = (Resolve-Path (Split-Path $selfPath -Parent)).Path
$root = Split-Path $scriptDir -Parent
$serverDir = Join-Path $root 'server'
$envFile = Join-Path $serverDir 'smtp.env'

if (-not (Test-Path $serverDir)) {
    Write-Host "[error] server directory not found: $serverDir" -ForegroundColor Red
    Write-Host '[error] place this script in <project>/scripts/ and run it from there' -ForegroundColor Red
    exit 1
}

Write-Host '========================================' -ForegroundColor Cyan
Write-Host '  PixelGame Server (SMTP enabled)' -ForegroundColor Cyan
Write-Host "  Port: $Port" -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''

# ---- 0) 端口占用检查(占用了就问你要不要清掉) ----
$owners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($owners.Count -gt 0) {
    $pids = $owners | Select-Object -ExpandProperty OwningProcess -Unique
    Write-Host "[warn] port $Port is already in use by process id: $($pids -join ', ')" -ForegroundColor Yellow
    $answer = Read-Host "       kill it/them and continue? (y/N)"
    if ($answer -match '^(y|Y)') {
        foreach ($procId in $pids) {
            try {
                Stop-Process -Id $procId -Force -ErrorAction Stop
                Write-Host "[ok] killed process $procId" -ForegroundColor Green
            } catch {
                Write-Host "[warn] could not kill ${procId}: $($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
        Start-Sleep -Milliseconds 600
    } else {
        Write-Host '[error] port busy - aborting. Close the other server, or pass another port:' -ForegroundColor Red
        Write-Host "[error]   scripts\start-server-smtp.bat 8123" -ForegroundColor Red
        exit 1
    }
}

# ---- 1) 载入 smtp.env ----
# 说明:
#   * 必须用 UTF-8 显式读取 —— smtp.env 是 UTF-8 无 BOM,而 Windows PowerShell 5.1 的
#     Get-Content 默认按系统 ANSI(中文机器上是 GBK)解码,会把中文注释读成乱码,
#     行尾残留的 \r 还会让 StartsWith('#') 判断失真,导致配置行被整行吞掉。
#   * smtp.env 里显式写了的键(例如演示模式 XIANGSU_ECHO_CODE=1)**覆盖**当前进程环境;
#     没写的键保持不动 —— "文件里怎么写就怎么生效"。
#   * 空值不写入环境变量:PowerShell 把 `$env:X=""` 当作"删除该变量"。
$envKeys = @()
if (Test-Path $envFile) {
    $lines = [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8)
    foreach ($rawLine in $lines) {
        $line = $rawLine.Trim([char]0xFEFF).Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
        if ($value -eq '') {
            Write-Host "[config] skip empty value: $key" -ForegroundColor Yellow
            continue
        }
        if ($envKeys -notcontains $key) { $envKeys += $key }
        Set-Item -Path "Env:$key" -Value $value
    }
    Write-Host "[config] loaded $envFile ($($envKeys.Count) keys)" -ForegroundColor Green
} else {
    Write-Host "[config] smtp.env not found - verification codes will be printed to this console" -ForegroundColor Yellow
    Write-Host "[config] copy server\smtp.env.example to server\smtp.env and fill in your SMTP account" -ForegroundColor Yellow
}

# ---- 2) 校验并显示生效配置(授权码脱敏) ----
# 注意:变量名不能叫 $port —— 那是本脚本的参数(HTTP 监听端口),会被悄悄改掉。
$host_ = $env:XIANGSU_SMTP_HOST
$user = $env:XIANGSU_SMTP_USER
$pass = $env:XIANGSU_SMTP_PASS
$smtpPort = if ($env:XIANGSU_SMTP_PORT) { $env:XIANGSU_SMTP_PORT } else { '465' }

$masked = '<empty>'
if ($pass) { $masked = $pass.Substring(0,1) + ('*' * ($pass.Length - 1)) }

Write-Host "[config] SMTP_HOST = $(if ($host_) { $host_ } else { '<empty>' })"
Write-Host "[config] SMTP_PORT = $smtpPort  (SSL = $(if ($env:XIANGSU_SMTP_SSL) { $env:XIANGSU_SMTP_SSL } else { '1' }))"
Write-Host "[config] SMTP_USER = $(if ($user) { $user } else { '<empty>' })"
Write-Host "[config] SMTP_PASS = $masked"
if ($env:XIANGSU_ECHO_CODE) {
    if ($env:XIANGSU_ECHO_CODE -eq '1') {
        Write-Host '[config] ECHO_CODE = 1  (demo mode: the app shows the code itself)' -ForegroundColor Green
    } else {
        Write-Host "[config] ECHO_CODE = $($env:XIANGSU_ECHO_CODE)"
    }
} else {
    Write-Host '[config] ECHO_CODE = <unset>  (codes go to email / this console)'
}

$placeholders = @('你的16位授权码', '你的邮箱@qq.com', 'your-code', 'changeme')
$smtpReady = $true
if (-not $host_ -or -not $user -or -not $pass -or
    ($placeholders | Where-Object { $pass -eq $_ -or $user -eq $_ })) {
    $smtpReady = $false
    Write-Host '[warn] SMTP is not fully configured - codes will fall back to this console' -ForegroundColor Yellow
} else {
    Write-Host '[ok] SMTP looks configured - verification emails will be sent for real' -ForegroundColor Green
}

# ---- 3) 打印客户端该填的服务器地址 ----
$lanIps = @()
try {
    $lanIps = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
        Select-Object -ExpandProperty IPAddress -Unique)
} catch {
    $lanIps = @()
}
if ($lanIps.Count -gt 0) {
    Write-Host ''
    Write-Host '[client] fill one of these into the app (Settings / Server):' -ForegroundColor Cyan
    foreach ($ip in $lanIps) {
        Write-Host "         http://${ip}:$Port/" -ForegroundColor Cyan
    }
    if (-not $smtpReady) {
        Write-Host '[client] (SMTP not configured: codes are printed below and, when' -ForegroundColor Yellow
        Write-Host '[client]  XIANGSU_ECHO_CODE=1, also shown inside the app)' -ForegroundColor Yellow
    }
} else {
    Write-Host '[warn] no LAN IPv4 found - check your network adapter' -ForegroundColor Yellow
}
Write-Host ''

# ---- 4) 启动服务端 ----
Set-Location $serverDir
python -m uvicorn main:app --host 0.0.0.0 --port $Port
