@echo off
title Pokemon Binder Manager Web Server
cd /d "%~dp0"

echo ==================================================
echo       POKEMON BINDER MANAGER WEB SERVER RUNNER
echo ==================================================
echo.

:: Check if Python is installed
where python >nul 2>nul
if errorlevel 1 goto nopython

echo Starting local web server...
echo Press Ctrl+C in this terminal window to stop the server.
echo.

:: Launch default web browser to localhost port 8000 in background
start "" "http://localhost:8000"

:: Start the Python backend server
python pokemon_server.py

goto end

:nopython
echo [!] Python is not installed or not in your Windows PATH!
echo     Please download and install Python 3.
echo     Make sure to check "Add Python to PATH" during installation.
echo.
pause
exit /b

:end
pause
