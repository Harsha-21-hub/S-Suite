@echo off
setlocal EnableExtensions EnableDelayedExpansion
title SDM setup
cd /d "%~dp0"
echo.
echo    . . . . . . . . . . . . . . . . . . .
echo    .   S D M   -   S Download Manager  .
echo    . . . . . . . . . . . . . . . . . . .
echo.

rem ---------- 1. Python 3.9+ ----------
set "PY="
call :try_py py -3
if not defined PY call :try_py python
if not defined PY call :try_py "%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if not defined PY call :try_py "%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY (
  echo    Python not found - installing Python 3.12 with winget ...
  winget install -e --id Python.Python.3.12 --scope user --accept-source-agreements --accept-package-agreements
  call :try_py "%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)
if not defined PY (
  echo.
  echo    Could not find or install Python automatically.
  echo    Install it from https://www.python.org/downloads/
  echo    ^(tick "Add python.exe to PATH"^), then run install.bat again.
  echo.
  pause
  exit /b 1
)
echo    [1/5] Python found: !PY!

rem ---------- 2. engine packages ----------
echo    [2/5] Installing engine packages: aiohttp, yt-dlp ...
!PY! -m pip install --disable-pip-version-check -q --upgrade --user -r requirements.txt
if errorlevel 1 !PY! -m pip install --disable-pip-version-check -q --upgrade -r requirements.txt
!PY! -c "import aiohttp" >nul 2>nul
if errorlevel 1 (
  echo    aiohttp could not be installed. Check your internet connection and run install.bat again.
  pause
  exit /b 1
)

rem ---------- 3. ffmpeg (optional) ----------
where ffmpeg >nul 2>nul
if errorlevel 1 (
  if exist "%LOCALAPPDATA%\Microsoft\WinGet\Links\ffmpeg.exe" goto :have_ffmpeg
  echo    [3/5] ffmpeg lets SDM merge best-quality video + audio from video sites.
  choice /c YN /n /m "          Install ffmpeg now? [Y/N] "
  if !errorlevel! equ 1 winget install -e --id Gyan.FFmpeg --accept-source-agreements --accept-package-agreements
) else (
  echo    [3/5] ffmpeg already installed
)
:have_ffmpeg

rem ---------- 4. browser helper + shortcuts ----------
echo    [4/5] Registering browser helper and shortcuts ...
!PY! install_helper.py install

rem ---------- 5. start ----------
echo    [5/5] Starting SDM ...
!PY! sdm.py --quit >nul 2>nul
if exist "%APPDATA%\Microsoft\Windows\Start Menu\Programs\SDM.lnk" (
  start "" "%APPDATA%\Microsoft\Windows\Start Menu\Programs\SDM.lnk"
) else (
  start "" !PY! sdm.py
)

echo.
echo    ------------------------------------------------------------
echo     LAST STEP - add the browser extension (one time only):
echo.
echo       1. Open  chrome://extensions   (Edge: edge://extensions)
echo       2. Switch on "Developer mode"
echo       3. Click "Load unpacked" and choose this folder:
echo          %~dp0extension
echo.
echo     From then on every download in your browser goes to SDM.
echo    ------------------------------------------------------------
echo.
explorer "%~dp0extension"
pause
exit /b 0

:try_py
%* -c "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)" >nul 2>nul
if not errorlevel 1 set "PY=%*"
exit /b 0
