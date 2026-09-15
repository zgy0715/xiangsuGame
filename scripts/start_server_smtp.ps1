# 启动带邮件发信配置的服务端(由 scripts/start-server-smtp.bat 调用)
#
# 做三件事:
#   1. 读取 server/smtp.env 里的环境变量(缺失或填了占位符都只是警告,不阻断启动);
#   2. 打印本次生效的配置(授权码一律脱敏),便于确认"到底有没有配上";
#   3. 在本机所有网卡上监听 8000 端口启动 FastAPI。
#
# 密钥去向:只存在于 smtp.env(已在 .gitignore 中)与本进程/子进程的环境变量里,
# 不会写入数据库、日志或代码。要换邮箱,改 smtp.env 即可。

$ErrorActionPreference = 'Stop'
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
Write-Host '  Port: 8000' -ForegroundColor Cyan
Write-Host '========================================' -ForegroundColor Cyan
Write-Host ''

# ---- 1) 载入 smtp.env ----
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
        Set-Item -Path "Env:$key" -Value $value
    }
    Write-Host "[config] loaded $envFile" -ForegroundColor Green
} else {
    Write-Host "[config] smtp.env not found - verification codes will be printed to this console" -ForegroundColor Yellow
    Write-Host "[config] copy server\smtp.env.example to server\smtp.env and fill in your SMTP account" -ForegroundColor Yellow
}

# ---- 2) 校验并显示生效配置(授权码脱敏) ----
$host_ = $env:XIANGSU_SMTP_HOST
$user = $env:XIANGSU_SMTP_USER
$pass = $env:XIANGSU_SMTP_PASS
$port = if ($env:XIANGSU_SMTP_PORT) { $env:XIANGSU_SMTP_PORT } else { '465' }

$masked = '<empty>'
if ($pass) { $masked = $pass.Substring(0,1) + ('*' * ($pass.Length - 1)) }

Write-Host "[config] SMTP_HOST = $(if ($host_) { $host_ } else { '<empty>' })"
Write-Host "[config] SMTP_PORT = $port  (SSL = $(if ($env:XIANGSU_SMTP_SSL) { $env:XIANGSU_SMTP_SSL } else { '1' }))"
Write-Host "[config] SMTP_USER = $(if ($user) { $user } else { '<empty>' })"
Write-Host "[config] SMTP_PASS = $masked"
Write-Host "[config] ECHO_CODE = $(if ($env:XIANGSU_ECHO_CODE) { $env:XIANGSU_ECHO_CODE } else { '0' })"

$placeholders = @('你的16位授权码', '你的邮箱@qq.com', 'your-code', 'changeme')
if (-not $host_ -or -not $user -or -not $pass -or
    ($placeholders | Where-Object { $pass -eq $_ -or $user -eq $_ })) {
    Write-Host '[warn] SMTP is not fully configured - codes will fall back to this console' -ForegroundColor Yellow
} else {
    Write-Host '[ok] SMTP looks configured - verification emails will be sent for real' -ForegroundColor Green
}
Write-Host ''

# ---- 3) 启动服务端 ----
Set-Location $serverDir
python -m uvicorn main:app --host 0.0.0.0 --port 8000
