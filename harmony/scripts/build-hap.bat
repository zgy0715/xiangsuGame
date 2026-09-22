@echo off
rem ============================================================================
rem  PixelGame HarmonyOS - build the signed-ready HAP
rem
rem  Same idea as the Android side's gradlew build: one double-click, no need to
rem  open DevEco Studio. Sets DEVECO_SDK_HOME / NODE_HOME for you, then calls
rem  hvigorw (the HarmonyOS build tool, like gradlew).
rem
rem  Output: entry\build\default\outputs\default\entry-default-unsigned.hap
rem  (the "unsigned" name is normal - DevEco signs it with your signing config;
rem   without a signing config the raw HAP cannot be installed on a device)
rem
rem  Usage:  build-hap.bat            clean + build
rem          build-hap.bat --fast     incremental build (no clean)
rem
rem  Chinese messages come from scripts\hvigor-run.ps1 (saved as UTF-8 BOM so
rem  Windows PowerShell 5.1 can read it); this file stays ASCII-only on purpose.
rem ============================================================================
setlocal
chcp 65001 >nul 2>&1
title PixelGame HarmonyOS - Build HAP
cd /d "%~dp0.."

set "ACTION=build"
if /i "%~1"=="--fast" set "ACTION=build-fast"

set "PS1=%~dp0hvigor-run.ps1"
if not exist "%PS1%" (
    echo [error] hvigor-run.ps1 not found next to this .bat
    pause
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
if "%RC%"=="0" (
    echo [done] HAP is ready. Double-click run-hap.bat to install it on a device.
) else (
    echo [fail] build failed with exit code %RC%
)
call :hold
exit /b %RC%

rem ---------------------------------------------------------------------------
rem  :hold - keep the window readable, but NEVER block on stdin.
rem  Set KEEP_OPEN=1 (or run inside an interactive cmd) to wait for a key instead.
rem  Reason: "pause" waits for stdin; when this .bat is launched from a script or
rem  any non-interactive host, stdin never delivers a key and the window hangs
rem  forever - which looks exactly like "the build froze".
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
