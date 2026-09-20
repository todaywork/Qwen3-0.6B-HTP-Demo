@echo off
REM 一键生成校准语料：双击即可（默认 v5 工程路径）
cd /d "%~dp0"
python build_calibration_corpus.py %*
pause
