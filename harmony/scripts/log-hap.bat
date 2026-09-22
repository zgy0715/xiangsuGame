@echo off
rem ============================================================================
rem  PixelGame HarmonyOS - tail the device log (like Android's logcat)
rem
rem  Prints the app's own log lines from the device. Keep this window open while
rem  you play; errors from the app show up here.
rem
rem  Usage:  log-hap.bat          show this app's lines only
rem          log-hap.bat --all    show everything (very noisy)
rem ============================================================================
setlocal
chcp 65001 >nul 2>&1
title PixelGame HarmonyOS - Device log
cd /d "%~dp0.."

set "ACTION=log"
if /i "%~1"=="--all" set "ACTION=log-all"

set "PS1=%~dp0hvigor-run.ps1"
if not exist "%PS1%" (
    echo [error] hvigor-run.ps1 not found next to this .bat
    call :hold
    exit /b 1
)

set "PS_EXE="
where pwsh >nul 2>&1 && set "PS_EXE=pwsh"
if not defined PS_EXE (
    where powershell >nul 2>&1 && set "PS_EXE=powershell"
)
if not defined PS_EXE (
    echo [error] neither pwsh nor powershell found in PATH
    call :hold
    exit /b 1
)

"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -Action %ACTION%
set "RC=%ERRORLEVEL%"

echo.
call :hold
exit /b %RC%

rem ---------------------------------------------------------------------------
rem  :hold - keep the window readable, but never block on stdin (see build-hap.bat)
rem ---------------------------------------------------------------------------
:hold
if /i "%KEEP_OPEN%"=="1" (
    echo Press any key to close this window.
    pause >nul
) else (
    echo This window closes in 8 seconds ...
    timeout /t 8 >nul 2>&1
)
exit /b 0
