@echo off
setlocal enabledelayedexpansion

echo ═══════════════════════════════════════════════════
echo  InvoiceDirect PDF Watcher — Windows Uninstall
echo ═══════════════════════════════════════════════════
echo.

set "INSTALL_DIR=C:\InvoiceDirect"

:: ── Choose uninstall mode ──
echo Choose uninstall method:
echo.
echo   [1] Windows Service
echo   [2] Scheduled Task
echo   [3] Exit
echo.
set /p CHOICE="Enter 1, 2, or 3: "

if "%CHOICE%"=="1" goto uninstall_service
if "%CHOICE%"=="2" goto uninstall_task
if "%CHOICE%"=="3" exit /b 0

echo Invalid choice.
exit /b 1

:uninstall_service
echo.
echo Stopping and removing service...
net stop InvoiceDirectWatcher 2>nul
cd /d "%INSTALL_DIR%"
InvoiceDirectWatcher.exe uninstall 2>nul
echo Service removed.
goto done

:uninstall_task
echo.
echo Removing scheduled task...
schtasks /end /tn "InvoiceDirectWatcher" 2>nul
schtasks /delete /tn "InvoiceDirectWatcher" /f 2>nul
echo Scheduled task removed.
goto done

:done
echo.
echo To completely remove all files, delete %INSTALL_DIR%
echo Note: this will remove sent invoice records (ledger.json).
echo.
pause
