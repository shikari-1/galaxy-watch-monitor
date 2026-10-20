@echo off
title Launch Hardware Monitor on Galaxy Watch 4
color 0a

echo ========================================================
echo    Launching Hardware Monitor on Galaxy Watch 4...
echo ========================================================
echo.

set ADB=tools\platform-tools\adb.exe

if not exist "%ADB%" (
    echo [ERROR] ADB not found at %ADB%
    pause
    exit /b 1
)

echo [*] Launching dashboard URL on watch...
"%ADB%" shell am start -a android.intent.action.VIEW -d "http://192.168.1.5:8080"

echo.
echo [OK] Done! The dashboard should be open on your watch screen.
timeout /t 3 >nul
