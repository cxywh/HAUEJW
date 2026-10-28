@echo off
chcp 65001 >nul
title 安装教务助手到手机
cd /d "%~dp0"

rem 自动找 dist 里最新的 APK：不再写死版本号，免得每次发版都要改这个脚本
set "APK="
for /f "delims=" %%f in ('dir /b /o-d "dist\*.apk" 2^>nul') do if not defined APK set "APK=dist\%%f"

set ADB=adb
where adb >nul 2>&1 || (
  echo 没找到 adb。请把 Android SDK 的 platform-tools 目录加入 PATH 后重来。
  pause
  exit /b 1
)

echo ================================================
echo  安装「教务助手」到已连接的手机
echo ================================================
echo.
"%ADB%" devices
echo.

for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" set FOUND=1
)

if not defined FOUND (
    echo [未检测到手机]
    echo.
    echo 请二选一：
    echo   1) 手机开发者选项里打开「USB 调试」，用数据线连电脑后重跑本脚本
    echo   2) 用 LocalSend 把 %APK% 传到手机，在手机上点击安装
    echo.
    pause
    exit /b
)

if not defined APK (
    echo [找不到 APK] 把安装包放到 dist\ 目录下再重跑本脚本
    echo.
    pause
    exit /b
)

echo [安装中] %APK%
"%ADB%" install -r "%APK%"
if %errorlevel%==0 (
    echo.
    echo [完成] 正在启动...
    "%ADB%" shell monkey -p com.cxywh.hauejw -c android.intent.category.LAUNCHER 1 >nul 2>&1
) else (
    echo.
    echo [失败] 若提示签名冲突，先在手机上卸载旧版再试。
)
echo.
pause
