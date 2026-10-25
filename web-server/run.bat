@echo off
title Galaxy Watch 4 Hardware Monitor Server
cd /d "%~dp0"
color 0b
echo ========================================================
echo    Starting Galaxy Watch 4 Hardware Monitor Server...
echo ========================================================
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python is not installed or not in PATH.
    echo Please install Python 3.9+ from https://www.python.org/
    pause
    exit /b 1
)

echo [*] Checking dependencies...
pip install -r requirements.txt --quiet --disable-pip-version-check

echo [*] Launching Server...
start "" http://localhost:8080
python server.py 8080

pause
