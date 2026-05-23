@echo off
title Stop Pokemon Binder Server
cd /d "%~dp0"

echo ==================================================
echo       STOP POKEMON BINDER WEB SERVER
echo ==================================================
echo.

:: Find all processes listening on port 8000 and kill them
set "found="
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8000" ^| findstr "LISTENING"') do (
    taskkill /f /pid %%a >nul 2>nul
    echo [OK] Background server process on port 8000 PID %%a has been stopped.
    set "found=1"
)

if not defined found (
    echo [!] No active server process was found running on port 8000.
)

echo.
echo Press any key to exit...
pause >nul
