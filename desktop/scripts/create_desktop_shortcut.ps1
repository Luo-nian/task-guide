# 任务栏桌宠 桌面快捷方式
# 路径指向 v4.9 release exe（cargo build --release 产物）
$exePath = "D:\idol\X\Z\Q\资料\其他\For-workBoddy-work\task-guide\desktop\target\release\taskbar-desktop.exe"
$iconPath = "D:\idol\X\Z\Q\资料\其他\For-workBoddy-work\task-guide\desktop\icons\icon.ico"
$lnkPath  = [Environment]::GetFolderPath("Desktop") + "\任务栏桌宠.lnk"

if (-not (Test-Path $exePath)) { Write-Error "exe 不存在: $exePath"; exit 1 }

$ws = New-Object -ComObject WScript.Shell
$lnk = $ws.CreateShortcut($lnkPath)
$lnk.TargetPath = $exePath
$lnk.WorkingDirectory = Split-Path $exePath -Parent
$lnk.IconLocation = if (Test-Path $iconPath) { "$iconPath,0" } else { "$exePath,0" }
$lnk.Description = "任务栏桌宠 v4.9 (原神化深色 HUD 主题)"
$lnk.WindowStyle = 7   # 7=工具窗口模式
$lnk.Save()
Write-Output "已创建: $lnkPath"
Write-Output "exe: $exePath"
if (Test-Path $lnkPath) { Start-Process explorer.exe -ArgumentList "/select,`"$lnkPath`"" }
