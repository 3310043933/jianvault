@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\publish-release.ps1" %*
set "RELEASE_EXIT_CODE=%ERRORLEVEL%"
echo.
pause
endlocal & exit /b %RELEASE_EXIT_CODE%
