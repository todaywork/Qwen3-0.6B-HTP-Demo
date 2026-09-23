@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 936 >nul
title Qwen3-0.6B-HTP 批跑工具（推送 Excel → 批跑 → 导出结果）
cd /d "%~dp0"

rem ============================================================================
rem 交互式批跑入口：选择/输入 Excel → 推送到设备 → 等待 App 批跑完成
rem → 结果自动导出到本目录 batch_results\ 下
rem 底层逻辑全部由 run_batch_inference.py 完成。
rem ============================================================================

rem ---- 定位 Python ----
set "PYTHON_EXE="
where python >nul 2>nul && set "PYTHON_EXE=python"
if not defined PYTHON_EXE (
    where py >nul 2>nul && set "PYTHON_EXE=py -3"
)
if not defined PYTHON_EXE (
    echo [ERROR] 未找到 python。请安装 Python 3 并加入 PATH 后重试。
    pause
    exit /b 1
)

rem ---- 列出当前目录可选 Excel ----
echo.
echo ============================================================
echo  当前目录下的 Excel 文件：
echo ============================================================
set /a FILE_COUNT=0
for /f "delims=" %%F in ('dir /b /a-d /o:n "*.xlsx" 2^>nul ^| findstr /v /b "~$"') do (
    set /a FILE_COUNT+=1
    set "FILE_!FILE_COUNT!=%%F"
    echo   !FILE_COUNT!. %%F
)
if !FILE_COUNT! equ 0 (
    echo   （当前目录没有 .xlsx 文件，请直接输入完整路径）
)

rem ---- 选择 Excel：序号 或 完整路径 ----
echo.
set "EXCEL_PATH="
set /p "CHOICE=输入序号选择文件，或直接输入/拖入 Excel 完整路径: "
set "CHOICE=!CHOICE:"=!"

rem 纯数字且在范围内 → 选目录内文件
set "IS_NUMBER=1"
for /f "delims=0123456789" %%A in ("!CHOICE!") do set "IS_NUMBER=0"
if !IS_NUMBER! equ 1 if !CHOICE! geq 1 if !CHOICE! leq !FILE_COUNT! (
    set "EXCEL_PATH=!FILE_%CHOICE%!"
)
rem 非序号 → 视为路径
if not defined EXCEL_PATH set "EXCEL_PATH=!CHOICE!"

if not exist "!EXCEL_PATH!" (
    echo [ERROR] 文件不存在: !EXCEL_PATH!
    pause
    exit /b 1
)

rem ---- 设备序列号（可选）----
echo.
set "SERIAL="
set /p "SERIAL=输入设备序列号（多设备时必填，单设备直接回车）: "
set "SERIAL=!SERIAL:"=!"

set "SERIAL_ARG="
if defined SERIAL set "SERIAL_ARG=--serial !SERIAL!"

rem ---- 执行 ----
echo.
echo ============================================================
echo  输入 Excel : !EXCEL_PATH!
echo  结果导出到 : %~dp0batch_results\
echo  接下来请在设备上点击 App 的【开始】按钮
echo ============================================================
echo.

%PYTHON_EXE% "%~dp0run_batch_inference.py" --input-excel "!EXCEL_PATH!" !SERIAL_ARG!
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo [完成] 批跑成功，结果已导出到 batch_results 目录。
) else (
    echo [失败] 批跑未正常完成，退出码 %EXIT_CODE%，请查看上方日志。
)
pause
exit /b %EXIT_CODE%
