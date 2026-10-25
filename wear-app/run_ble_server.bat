@echo off
title Galaxy Watch 4 - Desktop BLE Telemetry Server
cd /d "%~dp0\desktop-server"
color 0a
python ble_server.py
pause
