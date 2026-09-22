@echo off
rem ============================================================================
rem  PixelGame HarmonyOS - install + launch the HAP on a device / emulator
rem
rem  Same idea as "run the app" in Android Studio: it
rem    1. finds hdc.exe inside your DevEco SDK,
rem    2. checks that a device (or emulator) is connected,
rem    3. installs the newest HAP it can find,
rem    4. launches the app.
rem
rem  Prerequisites: build-hap.bat has been run at least once, and the device is
rem  visible in "hdc list targets" (start the emulator in DevEco first, or plug in
rem  a real device with USB debugging enabled).
rem
rem  Usage:  run-hap.bat              install + launch
rem          run-hap.bat --launch     launch only (skip install)
rem
rem  Chinese messages come from scripts\hvigor-run.ps1; this file is ASCII-only.
rem ============================================================================
setlocal
chcp 65001 >nul 2>&1
title PixelGame HarmonyOS - Run on device
cd /d "%~dp0.."

set "ACTION=run"
if /i "%~1"=="--launch" set "ACTION=launch"

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
if not "%RC%"=="0" echo [fail] exit code %RC%
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
