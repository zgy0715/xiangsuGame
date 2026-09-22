@echo off
rem ============================================================================
rem  PixelGame Server launcher  (double-click to run)
rem
rem  Loads SMTP settings from server\smtp.env, then starts the FastAPI backend
rem  listening on 0.0.0.0 so that BOTH the Android device/emulator and the
rem  HarmonyOS device/emulator can reach it over the LAN.
rem
rem  Usage:  start-server-smtp.bat            -> port 8000 (default)
rem          start-server-smtp.bat 8123       -> custom port
rem
rem  Chinese-language messages live in scripts\start_server_smtp.ps1, so this
rem  file is intentionally ASCII-only (avoids cmd.exe codepage issues).
rem ============================================================================
chcp 65001 >nul 2>&1
title PixelGame Server
cd /d "%~dp0"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8000"
set "PS1=%~dp0start_server_smtp.ps1"

if not exist "%PS1%" (
    echo [error] start_server_smtp.ps1 not found next to this .bat
    echo [error] expected: %PS1%
    pause
    exit /b 1
)

rem ---- prefer pwsh (PowerShell 7), fall back to Windows PowerShell 5 ----
set "PS_EXE="
where pwsh >nul 2>&1 && set "PS_EXE=pwsh"
if not defined PS_EXE (
    where powershell >nul 2>&1 && set "PS_EXE=powershell"
)
if not defined PS_EXE (
    echo [error] neither pwsh nor powershell found in PATH
    pause
    exit /b 1
)

"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %PORT%
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" echo [exit code %RC%]
call :hold
exit /b %RC%

rem ---------------------------------------------------------------------------
rem  :hold - keep the window readable, but never block on stdin.
rem  "pause" waits for stdin; when this .bat is launched from a script or any
rem  non-interactive host, no key ever arrives and the window hangs forever.
rem  Set KEEP_OPEN=1 to wait for a key anyway.
rem ---------------------------------------------------------------------------
:hold
if /i "%KEEP_OPEN%"=="1" (
    echo Server stopped. Press any key to close this window.
    pause >nul
) else (
    echo Server stopped. This window closes in 8 seconds ...
    timeout /t 8 >nul 2>&1
)
exit /b 0
