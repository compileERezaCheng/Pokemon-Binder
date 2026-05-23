@echo off
title Pokemon Binder Manager
cd /d "%~dp0"

echo ==================================================
echo         POKEMON BINDER MANAGER RUNNER
echo ==================================================
echo.

:: Check if Python is installed
where python >nul 2>nul
if errorlevel 1 goto nopython

:: Check and install dependencies
echo Checking dependencies...
python -c "import gspread, google.auth" >nul 2>nul
if errorlevel 1 goto installdeps
echo [+] Dependencies check passed.
goto runapp

:installdeps
echo [i] Google Sheets libraries (gspread, google-auth) not found.
echo     Installing them automatically via pip...
python -m pip install gspread google-auth
if errorlevel 1 (
    echo [!] Failed to install dependencies. Running local CSV mode only.
) else (
    echo [+] Dependencies successfully installed!
)
goto runapp

:runapp
echo.
echo Starting Pokemon Binder Manager...
echo.
python pokemon_binder.py
goto end

:nopython
echo [!] Python is not installed or not in your Windows PATH!
echo     Please download and install Python 3.
echo     Make sure to check "Add Python to PATH" during installation.
echo     Download from: https://www.python.org/
echo.
pause
exit /b

:end
pause
