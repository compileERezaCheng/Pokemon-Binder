@echo off
title Pokémon Binder Manager Launcher
cd /d "%~dp0"

:: Start the Python server silently in the background using pythonw.exe (windowless python)
start "" pythonw.exe backend/pokemon_server.py

:: Open Microsoft Edge in standalone chromeless App Mode
:: This spawns a clean dedicated desktop window without tabs or an address bar
start "" msedge --app=http://localhost:8000 --window-size=1320,880

exit
