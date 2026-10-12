@echo off
rem S Drive: opens the watch page in your browser and saves received files to Downloads\S-Drive.
rem Runs without a window. Press Stop on the page to exit; double-click this file again to start.
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0S-Drive-PC.ps1"
