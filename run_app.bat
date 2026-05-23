@echo off
title Pokémon Binder Manager Launcher
cd /d "%~dp0"

:: Check if pywebview library is installed
python -c "import webview" >nul 2>nul
if errorlevel 1 (
    echo ==================================================
    echo       INSTALLING DESKTOP MODULES (ONE-TIME)
    echo ==================================================
    echo.
    echo Installing 'pywebview' library to enable native desktop window...
    python -m pip install pywebview
    if errorlevel 1 (
        echo.
        echo [Error] Failed to install pywebview.
        echo Please ensure Python is installed and connected to the internet.
        pause
        exit /b
    )
    echo.
    echo [OK] Installation completed successfully!
    echo.
)

:: Start the app in windowless python mode (hides command prompt immediately)
start "" pythonw.exe launch_app.py
exit
