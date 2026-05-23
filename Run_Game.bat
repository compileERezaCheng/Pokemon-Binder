@echo off
title Pokemon Binder Server
echo Starting Pokemon Binder Manager...
echo.

:: Check if the EXE exists
if not exist "Pokemon Binder.exe" (
    echo [ERROR] Could not find "Pokemon Binder.exe" in this folder.
    echo Please make sure you are running this from the installation folder.
    pause
    exit /b
)

echo [1/2] Launching server...
:: Run the EXE. We use 'start' so the batch script can continue and 'pause' if it crashes.
:: We remove the --noconsole from the PyInstaller build next to see what's happening.
"Pokemon Binder.exe"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] The app crashed with exit code %ERRORLEVEL%
    pause
)
