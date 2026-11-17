@echo off
title Build Resso Desktop Installer
echo ============================================
echo   Building Resso Windows Desktop Setup (.exe)
echo ============================================
echo.
cd /d "%~dp0web\desktop"
call pnpm dist
echo.
echo ============================================
echo   Build Complete!
echo   Setup file: web\desktop\dist-package\Resso Setup 2.0.2.exe
echo ============================================
pause
