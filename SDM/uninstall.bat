@echo off
setlocal EnableExtensions
title SDM uninstall
cd /d "%~dp0"
set "PY=python"
py -3 -c "1" >nul 2>nul && set "PY=py -3"
echo.
echo    Stopping SDM ...
%PY% sdm.py --quit >nul 2>nul
echo    Removing browser helper and shortcuts ...
%PY% install_helper.py uninstall
echo.
choice /c YN /n /m "   Also delete SDM settings and download history? (your downloaded files stay) [Y/N] "
if %errorlevel% equ 1 rmdir /s /q "%APPDATA%\SDM"
echo.
echo    Done. Remove "SDM Catcher" in chrome://extensions, then delete this folder.
echo.
pause
