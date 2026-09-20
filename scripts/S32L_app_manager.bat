@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
title S32L Qwen3 HTP Demo 应用管理工具 

rem ============================================================================
rem S32L Android 虚拟机应用管理菜单（adb -host + adb_and 双跳） 
rem 固定目标应用：com.qairt.qwen3htp / .MainActivity
rem ============================================================================

set "PACKAGE_NAME=com.qairt.qwen3htp"
set "MAIN_ACTIVITY=.MainActivity"
set "ADB_EXE=E:\QualComm\shangqi_S32L\windows_adb_7.1.39_20230925\adb.exe"
set "ADB_SERIAL="

rem ---- 固定使用 S32L 专用 ADB，不搜索 PATH -------------------------
if not exist "%ADB_EXE%" (
    echo [ERROR] 未找到指定的 S32L ADB："%ADB_EXE%"
    exit /b 1
)

:adb_found
call :run_adb get-state 1>nul 2>nul
if errorlevel 1 ( echo [ERROR] 设备未连接或无响应 & goto :eof )
echo [OK] 设备在线，目标应用 %PACKAGE_NAME%
echo.

:menu
echo ============================================================================
echo  请选择要执行的功能（直接按数字键，无需回车）： 
echo    1. 启动应用入口界面（display 0） 
echo    2. 强制停止应用（am force-stop） 
echo    3. 杀掉应用进程（killall -9） 
echo    4. 卸载应用 
echo    5. 检查应用是否已安装 
echo    6. 清除 logcat 缓存 
echo    7. 抓取 10 秒 logcat 到本地 apk_logs 目录 
echo    8. 推送并安装 APK（可把 APK 拖进本窗口） 
echo    0. 退出 
echo ============================================================================
choice /c 123456780 /n /m "请按键选择： "
set "RC=%errorlevel%"
if %RC% geq 9 goto :eof
if "%RC%"=="1" goto :do_start
if "%RC%"=="2" goto :do_stop
if "%RC%"=="3" goto :do_kill
if "%RC%"=="4" goto :do_uninstall
if "%RC%"=="5" goto :do_check
if "%RC%"=="6" goto :do_logclear
if "%RC%"=="7" goto :do_logcat
if "%RC%"=="8" goto :do_install
goto :menu

:do_start
echo.
echo [执行] am start -n %PACKAGE_NAME%/%MAIN_ACTIVITY% --display 0
call :run_adb shell "adb_and shell am start -n %PACKAGE_NAME%/%MAIN_ACTIVITY% --display 0"
goto :back

:do_stop
echo.
echo [执行] am force-stop %PACKAGE_NAME%
call :run_adb shell "adb_and shell am force-stop %PACKAGE_NAME%"
goto :back

:do_kill
echo.
echo [执行] killall -9 %PACKAGE_NAME%
call :run_adb shell "adb_and shell killall -9 %PACKAGE_NAME%"
goto :back

:do_uninstall
echo.
echo [执行] uninstall %PACKAGE_NAME%
call :run_adb shell "adb_and uninstall %PACKAGE_NAME%"
goto :back

:do_check
echo.
echo [执行] pm list packages %PACKAGE_NAME%
call :run_adb shell "adb_and shell pm list packages %PACKAGE_NAME%"
goto :back

:do_logclear
echo.
echo [执行] logcat -c
call :run_adb shell "adb_and shell logcat -c"
echo [OK] logcat 缓存已清除 
goto :back

:do_logcat
echo.
set "LOGDIR=%~dp0apk_logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
for /f %%T in ('powershell.exe -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss" 2^>nul') do set "STAMP=%%T"
if not defined STAMP set "STAMP=%DATE:~0,4%%DATE:~5,2%%DATE:~8,2%_%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%"
set "LOGFILE=%LOGDIR%\logcat_%STAMP%.log"
echo [执行] 抓取 10 秒 logcat ...
call :run_adb shell "adb_and shell logcat -c" 1>nul 2>nul
call :run_adb shell "timeout 10 adb_and shell logcat" > "%LOGFILE%" 2>&1
echo [OK] 已保存：%LOGFILE%
goto :back

:do_install
echo.
echo 请把 APK 文件拖入本窗口（路径会自动填入，支持带空格），然后按回车。 
echo 直接把 APK 拖到本脚本图标上启动时，路径已预填，直接回车即可。 
set "APK_IN=%~1"
set "APK_NEW="
set /p "APK_NEW=APK路径（直接回车使用上方路径）： %APK_IN%"
if defined APK_NEW set "APK_IN=%APK_NEW%"
if not defined APK_IN ( echo [ERROR] 未输入 APK 路径 & goto :back )
set "APK_PATH="
for %%I in (%APK_IN%) do set "APK_PATH=%%~fI"
if not defined APK_PATH ( echo [ERROR] 路径解析失败：%APK_IN% & goto :back )
if /i not "%APK_PATH:~-4%"==".apk" ( echo [ERROR] 不是 .apk 文件：%APK_PATH% & goto :back )
if not exist "%APK_PATH%" ( echo [ERROR] 文件不存在：%APK_PATH% & goto :back )
echo.
echo [1/3] 推送 %APK_PATH%
echo        到宿主 /cache/_s32l_install.apk（覆盖同名） 
call :run_adb push "%APK_PATH%" "/cache/_s32l_install.apk"
if errorlevel 1 ( echo [ERROR] push 失败 & goto :back )
echo [OK] 推送完成 
echo.
echo [2/3] 通过 adb_and 安装到 Android 虚拟机...
call :run_adb shell "adb_and install /cache/_s32l_install.apk"
if errorlevel 1 ( echo [ERROR] install 失败 & goto :back )
echo [OK] 安装完成 
echo.
choice /c YN /n /m "[3/3] 是否启动 %PACKAGE_NAME% 入口界面？[Y/N] "
if errorlevel 2 goto :back
call :run_adb shell "adb_and shell am start -n %PACKAGE_NAME%/%MAIN_ACTIVITY% --display 0"
goto :back

:back
echo.
pause
goto :menu

:run_adb
if defined ADB_SERIAL (
    "%ADB_EXE%" -host -s "%ADB_SERIAL%" %*
) else (
    "%ADB_EXE%" -host %*
)
exit /b %errorlevel%
