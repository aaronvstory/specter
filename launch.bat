@echo off
REM Specter launcher (Windows). Double-click to open the dashboard.
setlocal
cd /d "%~dp0"
echo [specter] %date% %time%
where uv >nul 2>&1
if %errorlevel%==0 (
  uv run --with rich --with questionary python -m specter.cli tui
) else (
  where python >nul 2>&1 || ( echo [!] need python or uv & pause & exit /b 1 )
  python -c "import rich, questionary" 2>nul || python -m pip install rich questionary
  set PYTHONPATH=%cd%
  python -m specter.cli tui
)
if %errorlevel% neq 0 pause
endlocal
