@echo off
rem Opens SDM (starts the engine if it is not running yet)
cd /d "%~dp0"
where pythonw >nul 2>nul && (start "" pythonw "%~dp0sdm.py" & exit /b 0)
py -3 -c "1" >nul 2>nul && (start "" pyw -3 "%~dp0sdm.py" & exit /b 0)
start "" python "%~dp0sdm.py"
