@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title S32L Qwen3-0.6B-HTP Batch Inference Tool
cd /d "%~dp0"

rem Find the default source Excel beside this launcher.
set "DEFAULT_INPUT_EXCEL="
for /f "delims=" %%F in ('dir /b /a-d /o:n "%~dp0calib_data_v5_*.xlsx" 2^>nul') do if not defined DEFAULT_INPUT_EXCEL set "DEFAULT_INPUT_EXCEL=%~dp0%%F"

if not defined DEFAULT_INPUT_EXCEL (
    echo [ERROR] No default calib_data_v5_*.xlsx file was found beside this launcher.
    set "exitCode=2"
    goto :finished
)

set "INPUT_EXCEL=%DEFAULT_INPUT_EXCEL%"

echo ============================================================================
echo S32L Qwen3-0.6B-HTP Batch Inference Tool
echo Drag the source Excel file into this window and press Enter.
echo Press Enter directly to use the default Excel:
set DEFAULT_INPUT_EXCEL
echo ============================================================================
echo.

set "USER_INPUT_EXCEL="
set /p "USER_INPUT_EXCEL=Source Excel file: "
if defined USER_INPUT_EXCEL set "INPUT_EXCEL=%USER_INPUT_EXCEL%"

rem Remove quotes added automatically by Windows drag-and-drop and resolve the path.
for %%I in ("%INPUT_EXCEL:"=%") do set "INPUT_EXCEL=%%~fI"

if not exist "%INPUT_EXCEL%" (
    echo.
    echo [ERROR] The source Excel file does not exist:
    set INPUT_EXCEL
    set "exitCode=2"
    goto :finished
)

for %%I in ("%INPUT_EXCEL%") do set "INPUT_EXCEL_EXTENSION=%%~xI"
if /i not "%INPUT_EXCEL_EXTENSION%"==".xlsx" (
    echo.
    echo [ERROR] The source file must use the .xlsx extension:
    set INPUT_EXCEL
    set "exitCode=2"
    goto :finished
)

echo.
echo Selected source Excel:
set INPUT_EXCEL
echo Results will be exported to:
echo "%~dp0batch_results"
echo.

py -3 -c "import sys" >nul 2>&1
if not errorlevel 1 (
    py -3 "%~dp0S32L_run_batch_inference.py" --input-excel "%INPUT_EXCEL%" --clean-device-history %*
) else (
    where python >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] Python 3 was not found. Install Python 3 and enable the PATH option.
        set "exitCode=9009"
        goto :finished
    )
    python "%~dp0S32L_run_batch_inference.py" --input-excel "%INPUT_EXCEL%" --clean-device-history %*
)
set "exitCode=%ERRORLEVEL%"

:finished
echo.
if "%exitCode%"=="0" (
    echo Batch inference automation finished successfully.
) else (
    echo Batch inference automation failed. Exit code: %exitCode%
)
echo.
pause
exit /b %exitCode%
