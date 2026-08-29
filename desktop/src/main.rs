// 任务栏 桌面端入口
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    taskbar_desktop_lib::run()
}
