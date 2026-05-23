@echo off
title Stop Pokemon Binder Server
cd /d "%~dp0"

echo ==================================================
echo       STOP POKEMON BINDER WEB SERVER
echo ==================================================
echo.

:: Find process listening on port 8000 and kill it
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8000" ^| findstr "LISTENING"') do (
    taskkill /f /pid %%a >nul 2>nul
    echo [OK] Background server on port 8000 (PID: %%a) has been stopped.
    goto done
)

echo [!] No active server process was found running on port 8000.

:done
echo.
echo Press any key to exit...
pause >nul
