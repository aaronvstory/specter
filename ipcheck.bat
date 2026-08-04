@echo off
REM Specter exit-IP check. Double-click: opens the checker in your browser.
REM Paste a proxy URL (or just an IP), hit Check. Ctrl-C in this window to stop.
setlocal
cd /d "%~dp0"
echo [specter] exit-IP check  %date% %time%
set PYTHONPATH=%cd%
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" -m specter.ipcheck --serve %*
) else (
  where python >nul 2>&1 || ( echo [!] need python on PATH ^& pause ^& exit /b 1 )
  python -m specter.ipcheck --serve %*
)
if %errorlevel% neq 0 pause
endlocal
