@echo off
rem Launch the backend with SMTP settings loaded from server\smtp.env.
rem Chinese-language output lives in scripts\start_server_smtp.ps1 to avoid
rem cmd.exe codepage issues, so this file stays ASCII-only on purpose.
chcp 65001 >nul 2>&1
title PixelGame Server (SMTP)
cd /d "%~dp0"

where pwsh >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_server_smtp.ps1"
) else (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_server_smtp.ps1"
)

echo.
echo Server stopped. Press any key to exit.
pause >nul
