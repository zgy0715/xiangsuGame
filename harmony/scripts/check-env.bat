@echo off
rem ============================================================================
rem  PixelGame HarmonyOS - check the build environment
rem
rem  Run this first if a build fails. It reports:
rem    - DevEco Studio installation (SDK, node, hvigorw, hdc)
rem    - DEVECO_SDK_HOME / NODE_HOME resolution
rem    - connected devices / emulators (hdc list targets)
rem    - the LAN address the app should use to reach the game server
rem  Nothing is modified.
rem
rem  Chinese messages come from scripts\hvigor-run.ps1; this file is ASCII-only.
rem ============================================================================
setlocal
chcp 65001 >nul 2>&1
title PixelGame HarmonyOS - Environment check
cd /d "%~dp0.."

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

"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -Action check
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
