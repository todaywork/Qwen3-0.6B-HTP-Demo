@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title S32L Qwen3-0.6B-HTP Model Resource Push Tool

rem ============================================================================
rem USER CONFIGURATION - edit only the default local path when needed.
rem Keep the set "NAME=value" syntax and the surrounding double quotes.
rem ============================================================================
set "DEFAULT_LOCAL_MODEL_DIR=E:\QualComm\ai-hub-compiles\qwen3_0_6b-geniex_qairt-w4a16-qualcomm_sa8775p-office1"

rem Fixed device destination. The script does not prompt the user to change it.
set "REMOTE_MODEL_DIR=/data/local/tmp/genie_qwen3_quality"
rem S32L requires the vendor adb with -host support and its USB driver.
set "ADB_EXE=E:\QualComm\shangqi_S32L\windows_adb_7.1.39_20230925\adb.exe"
rem Dedicated host staging directory; Android uses REMOTE_MODEL_DIR above.
set "HOST_STAGE_DIR=/cache/S32L_qwen3_0_6b_htp_model"
set "ADB_SERIAL="

set "LOCAL_MODEL_DIR=%DEFAULT_LOCAL_MODEL_DIR%"

echo ============================================================================
echo Select the local model resource folder.
echo Drag a folder into this window and press Enter to use it.
echo Press Enter directly to use the default folder:
echo "%DEFAULT_LOCAL_MODEL_DIR%"
echo ============================================================================
echo.

rem The first command-line argument also overrides the local folder.
if not "%~1"=="" goto :use_argument

set "USER_LOCAL_MODEL_DIR="
set /p "USER_LOCAL_MODEL_DIR=Local model folder: "
if defined USER_LOCAL_MODEL_DIR set "LOCAL_MODEL_DIR=%USER_LOCAL_MODEL_DIR%"
goto :normalize_local_path

:use_argument
set "LOCAL_MODEL_DIR=%~1"

:normalize_local_path

rem Remove quotes added automatically by Windows drag-and-drop and resolve the path.
for %%I in ("%LOCAL_MODEL_DIR:"=%") do set "LOCAL_MODEL_DIR=%%~fI"

echo.

echo ============================================================================
echo S32L Qwen3-0.6B-HTP Model Resource Push Tool
echo Local directory : "%LOCAL_MODEL_DIR%"
echo Device directory: "%REMOTE_MODEL_DIR%"
if defined ADB_SERIAL echo ADB device      : "%ADB_SERIAL%"
echo ============================================================================
echo.

if not defined LOCAL_MODEL_DIR (
    echo [ERROR] LOCAL_MODEL_DIR is empty.
    goto :failed
)

if not exist "%LOCAL_MODEL_DIR%\" (
    echo [ERROR] Local model directory does not exist:
    echo         "%LOCAL_MODEL_DIR%"
    goto :failed
)

if not defined REMOTE_MODEL_DIR (
    echo [ERROR] REMOTE_MODEL_DIR is empty.
    goto :failed
)

rem Reject broad targets because this script deletes REMOTE_MODEL_DIR recursively.
if "%REMOTE_MODEL_DIR%"=="/" goto :unsafe_remote
if "%REMOTE_MODEL_DIR%"=="/data" goto :unsafe_remote
if "%REMOTE_MODEL_DIR%"=="/data/" goto :unsafe_remote
if "%REMOTE_MODEL_DIR%"=="/data/local" goto :unsafe_remote
if "%REMOTE_MODEL_DIR%"=="/data/local/" goto :unsafe_remote
if "%REMOTE_MODEL_DIR%"=="/data/local/tmp" goto :unsafe_remote
if "%REMOTE_MODEL_DIR%"=="/data/local/tmp/" goto :unsafe_remote

if not exist "%ADB_EXE%" (
    echo [ERROR] Required S32L vendor adb was not found: "%ADB_EXE%"
    goto :failed
)

:adb_found
rem Verify both transport layers before modifying model resources.
call :run_adb shell "echo S32L_HOST_READY"
if errorlevel 1 (
    echo [ERROR] S32L host is unavailable. Check the vendor driver and USB Type-C cable.
    goto :failed
)
call :run_adb shell "adb_and shell ls /data/local/tmp"
if errorlevel 1 (
    echo [ERROR] Android is unavailable through host adb_and.
    goto :failed
)

rem Exact path guards protect both recursive deletion targets.
if not "%REMOTE_MODEL_DIR%"=="/data/local/tmp/genie_qwen3_quality" goto :unsafe_remote
if not "%HOST_STAGE_DIR%"=="/cache/S32L_qwen3_0_6b_htp_model" goto :unsafe_remote

echo [1/6] Uploading model resources to S32L host cache...
call :run_adb shell "rm -rf -- '%HOST_STAGE_DIR%'"
if errorlevel 1 goto :adb_failed
call :run_adb push "%LOCAL_MODEL_DIR%" "%HOST_STAGE_DIR%"
if errorlevel 1 goto :adb_failed

echo [2/6] Stopping com.qairt.qwen3htp on Android...
call :run_adb shell "adb_and shell am force-stop com.qairt.qwen3htp"
if errorlevel 1 goto :adb_failed

echo [3/6] Removing old model resources from Android...
call :run_adb shell "adb_and shell rm -rf -- %REMOTE_MODEL_DIR%"
if errorlevel 1 goto :adb_failed

echo [4/6] Transferring host cache resources to Android...
rem adb_and push extends the document's host-to-Android adb_and workflow.
call :run_adb shell "adb_and push %HOST_STAGE_DIR% %REMOTE_MODEL_DIR%"
if errorlevel 1 goto :adb_failed

echo [5/6] Updating Android permissions and checking the destination...
call :run_adb shell "adb_and shell chmod -R 777 -- %REMOTE_MODEL_DIR%"
if errorlevel 1 goto :adb_failed
call :run_adb shell "adb_and shell ls -lah %REMOTE_MODEL_DIR%"
if errorlevel 1 goto :adb_failed

echo [6/6] Removing the host staging copy...
call :run_adb shell "rm -rf -- '%HOST_STAGE_DIR%'"
if errorlevel 1 goto :adb_failed

echo.
echo [SUCCESS] Model resources were pushed to Android:
echo           "%REMOTE_MODEL_DIR%"
echo.
pause
exit /b 0

:unsafe_remote
echo [ERROR] Refusing to delete an unexpected remote or staging directory.
goto :failed

:adb_failed
echo.
echo [ERROR] An S32L adb operation failed. Review the output above.
echo [INFO] Any remaining host staging files are kept for diagnosis:
echo        "%HOST_STAGE_DIR%"

:failed
echo.
pause
exit /b 1

:run_adb
if defined ADB_SERIAL (
    "%ADB_EXE%" -host -s "%ADB_SERIAL%" %*
) else (
    "%ADB_EXE%" -host %*
)
exit /b %errorlevel%
