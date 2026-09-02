@echo off
:: 任务栏桌宠 v4.9 启动器
:: 编译后产物路径（D:\taskbuild2\desktop-target 走 ASCII target dir 绕中文路径）
:: 编译完后 v4.9 exe 路径
set EXE=D:\taskbuild2\desktop-target\release\taskbar-desktop.exe
if not exist "%EXE%" (
    echo [错误] %EXE% 不存在，请先编译桌面端
    pause
    exit /b 1
)
start "" "%EXE%"
