@echo off
rem ============================================================================
rem  PixelGame HarmonyOS - start the game server (backend)
rem
rem  The server lives in the Android project folder (xiangsuGame\server), because
rem  BOTH clients share one server. This launcher finds that folder and calls its
rem  start-server-smtp.bat, so you can start everything from the HarmonyOS project.
rem
rem  Usage:  start-server.bat            default port 8000
rem          start-server.bat 8123       custom port
rem ============================================================================
setlocal
chcp 65001 >nul 2>&1
title PixelGame Server

rem this file lives in <DEVxiangs>\scripts, so the parent is the DevEco project
set "HERE=%~dp0"
set "DEVX=%HERE%.."

set "CAND1=%DEVX%\..\xiangsuGame\scripts\start-server-smtp.bat"
set "CAND2=%DEVX%\..\..\xiangsuGame\scripts\start-server-smtp.bat"
set "CAND3=%DEVX%\..\..\..\xiangsuGame\scripts\start-server-smtp.bat"

set "SERVER_BAT="
if exist "%CAND1%" set "SERVER_BAT=%CAND1%"
if not defined SERVER_BAT if exist "%CAND2%" set "SERVER_BAT=%CAND2%"
if not defined SERVER_BAT if exist "%CAND3%" set "SERVER_BAT=%CAND3%"

if not defined SERVER_BAT (
    echo [error] server launcher not found. Looked for:
    echo         %CAND1%
    echo         %CAND2%
    echo         %CAND3%
    echo [error] start it manually: xiangsuGame\scripts\start-server-smtp.bat
    call :hold
    exit /b 1
)

echo [info] using: %SERVER_BAT%
call "%SERVER_BAT%" %*
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------
rem  :hold - keep the window readable, but never block on stdin
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
