Set WshShell = CreateObject("WScript.Shell")
exePath = "D:\idol\X\Z\Q\资料\其他\For-workBoddy-work\task-guide\desktop\target\release\taskbar-desktop.exe"
iconPath = "D:\idol\X\Z\Q\资料\其他\For-workBoddy-work\task-guide\desktop\icons\icon.ico"
lnkPath  = WshShell.SpecialFolders("Desktop") & "\任务栏桌宠.lnk"

If Not WshShell.FileExists(exePath) Then
    WScript.Echo "exe 不存在: " & exePath
    WScript.Quit 1
End If

Set lnk = WshShell.CreateShortcut(lnkPath)
lnk.TargetPath = exePath
lnk.WorkingDirectory = Replace(exePath, "\taskbar-desktop.exe", "")
lnk.IconLocation = iconPath
lnk.Description = "任务栏桌宠 v4.9 (原神化深色 HUD 主题)"
lnk.WindowStyle = 7
lnk.Save

WScript.Echo "已创建: " & lnkPath
