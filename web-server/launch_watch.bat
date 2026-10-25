@echo off
setlocal
title Launch Hardware Monitor on Galaxy Watch 4
color 0a

cd /d "%~dp0"
python ..\tools\launch_watch.py %*
if errorlevel 1 (
    echo.
    echo [!] Launcher exited. Press any key to close...
    pause >nul
)
