@echo off
setlocal enabledelayedexpansion

echo ═══════════════════════════════════════════════════
echo  InvoiceDirect PDF Watcher — Windows Installation
echo ═══════════════════════════════════════════════════
echo.

set "SCRIPT_DIR=%~dp0"
set "INSTALL_DIR=C:\InvoiceDirect"

:: ── 1. Choose installation mode ──
echo Choose installation method:
echo.
echo   [1] Windows Service (via WinSW — recommended)
echo   [2] Scheduled Task (runs at user login)
echo   [3] Exit
echo.
set /p CHOICE="Enter 1, 2, or 3: "

if "%CHOICE%"=="1" goto install_service
if "%CHOICE%"=="2" goto install_task
if "%CHOICE%"=="3" exit /b 0

echo Invalid choice.
exit /b 1

:: ═══════════════════════════════════════════════════
::  OPTION 1: Windows Service via WinSW
:: ═══════════════════════════════════════════════════
:install_service
echo.
echo ── Option 1: Windows Service ──
echo.

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

:: Copy files
echo Copying files to %INSTALL_DIR%...
copy /Y "%SCRIPT_DIR%target\pdf-watcher-agent-1.0.0.jar" "%INSTALL_DIR%\" >nul 2>&1
copy /Y "%SCRIPT_DIR%winsw.xml" "%INSTALL_DIR%\" >nul 2>&1

:: Download WinSW if not already present
if not exist "%INSTALL_DIR%\InvoiceDirectWatcher.exe" (
    echo Downloading WinSW service wrapper...
    powershell -Command "& {Invoke-WebRequest -Uri 'https://github.com/winsw/winsw/releases/download/v3.1.0/WinSW-x64.exe' -OutFile '%INSTALL_DIR%\InvoiceDirectWatcher.exe'}"
)

:: Create watcher.properties if missing
if not exist "%INSTALL_DIR%\watcher.properties" (
    if exist "%SCRIPT_DIR%src\main\resources\watcher.properties" (
        copy /Y "%SCRIPT_DIR%src\main\resources\watcher.properties" "%INSTALL_DIR%\watcher.properties" >nul
        echo.
        echo ⚠ IMPORTANT: Edit %INSTALL_DIR%\watcher.properties with your settings
        echo   before starting the service.
    )
)

:: Create directories
if not exist "%INSTALL_DIR%\inbox" mkdir "%INSTALL_DIR%\inbox"
if not exist "%INSTALL_DIR%\emailed" mkdir "%INSTALL_DIR%\emailed"
if not exist "%INSTALL_DIR%\failed" mkdir "%INSTALL_DIR%\failed"
if not exist "%INSTALL_DIR%\logs" mkdir "%INSTALL_DIR%\logs"

echo.
echo Installing Windows service...
cd /d "%INSTALL_DIR%"
InvoiceDirectWatcher.exe install

echo.
echo ─────────────────────────────────────────────
echo  Service installed as "InvoiceDirectWatcher"
echo.
echo  Next steps:
echo   1. Edit %INSTALL_DIR%\watcher.properties
echo   2. Start the service:
echo        net start InvoiceDirectWatcher
echo      or via Services.msc
echo   3. Check logs in %INSTALL_DIR%\logs\
echo ─────────────────────────────────────────────
echo.
goto done

:: ═══════════════════════════════════════════════════
::  OPTION 2: Windows Task Scheduler
:: ═══════════════════════════════════════════════════
:install_task
echo.
echo ── Option 2: Scheduled Task ──
echo.

if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

:: Copy files
echo Copying files to %INSTALL_DIR%...
copy /Y "%SCRIPT_DIR%target\pdf-watcher-agent-1.0.0.jar" "%INSTALL_DIR%\" >nul 2>&1

:: Create watcher.properties if missing
set "PROPS=%INSTALL_DIR%\watcher.properties"
if not exist "%PROPS%" (
    if exist "%SCRIPT_DIR%src\main\resources\watcher.properties" (
        copy /Y "%SCRIPT_DIR%src\main\resources\watcher.properties" "%PROPS%" >nul
    )
)

:: Create directories
if not exist "%INSTALL_DIR%\inbox" mkdir "%INSTALL_DIR%\inbox"
if not exist "%INSTALL_DIR%\emailed" mkdir "%INSTALL_DIR%\emailed"
if not exist "%INSTALL_DIR%\failed" mkdir "%INSTALL_DIR%\failed"
if not exist "%INSTALL_DIR%\logs" mkdir "%INSTALL_DIR%\logs"

:: Create a wrapper batch file for the scheduled task
set "RUNNER=%INSTALL_DIR%\run-watcher.bat"
(
    echo @echo off
    echo cd /d "%%~dp0"
    echo java --enable-preview -Dwatcher.config="watcher.properties" -Dwatcher.log.dir="logs" -jar pdf-watcher-agent-1.0.0.jar
) > "%RUNNER%"

echo.
echo Creating scheduled task "InvoiceDirectWatcher"...
schtasks /create /tn "InvoiceDirectWatcher" /tr "%RUNNER%" /sc onstart /ru SYSTEM /rl HIGHEST /f

echo.
echo ─────────────────────────────────────────────
echo  Scheduled task created as "InvoiceDirectWatcher"
echo  (triggers at system startup, runs as SYSTEM).
echo.
echo  Next steps:
echo   1. Edit %INSTALL_DIR%\watcher.properties
echo   2. Test manually:
echo        %RUNNER%
echo   3. Or restart the machine to trigger the task
echo   4. Check logs in %INSTALL_DIR%\logs\
echo ─────────────────────────────────────────────
echo.
goto done

:: ═══════════════════════════════════════════════════
::  Done
:: ═══════════════════════════════════════════════════
:done
echo.
echo For ERP integration: configure your ERP to write
echo   PDF files + .json sidecars to:
echo     %INSTALL_DIR%\inbox\
echo.
echo See invoice-forwarder sidecar format documentation
echo for the expected .json structure.
echo.
pause
