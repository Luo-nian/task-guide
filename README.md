# 任务栏 TaskBar

> 个人任务管理系统 —— 支持"任务追踪 + 步骤拆解"（原神任务模式），手机端 + 电脑端双客户端，专治"事情多总忘 + 拖延症"。

## 功能速览

- 📱 **手机端**（Android）：任务总览 + 桌面小部件 + 通知 + WorkManager 提醒
- 🖥️ **电脑端**（Win11 Tauri 客户端）：完整 App + 折叠小挂件（340×76 浮在桌面右下）
- 🔄 **局域网双端同步**：配对一次 → 之后同网络自动重连 → 显式 unpair 才断开
- 🎯 **任务追踪模式**：任务可追踪 → 拆解步骤 → 推进每一步 → 完成
- ✅ **4 分类**：每日任务 / 目标任务 / 限时任务 / 小任务（单次）
- ⚡ **紧急任务提醒**：≤48h 任务按 3/10 提醒；>48h 任务剩 36h 提醒
- 🏆 **积分系统**：完成 +10 分（可调），总积分显示在顶栏
- 📦 **仓库**：已完成任务放侧边栏底部，按时间从新到旧 + 检索

## 截图

桌面端默认（总览 + 4 分类）：

```
┌─ ◆ 任务栏  ◆ 130  ⚙ 📌🖼📥  ◣ ─┐
│  ◉                              │
│  ⚑   总览  每日 / 目标 / 限时 / 单次   │
│  ◆   ┌── 每日任务 (2) ─────────┐    │
│  ✓   │ ◆ 每日锻炼       daily │    │
│      │ ◆ test123        daily │    │
│  ▣   ├── 目标任务 (3) ───────┤    │
│      │ ◆ 一个月看完CS  追踪中 │    │
│      │ ◆ 复习数字信号   追踪中 │    │
│      │ ◆ 测试目标-A    追踪中 │    │
│      ├── 限时任务 (2) ───────┤    │
│      │ ◆ 交作业       8/30 00:25 │    │
│      │ ◆ 给导师发周报  8/30 06:25 │    │
│      └── 小任务 (1) ────────┘    │
│  ⊕                            │
└────────────────────────────────┘
```

电脑挂件模式（图 4 折叠态）：

```
┌─ ◆ 测试目标-A        2 天后 ──◆ ┐
│   (340×76 浮在桌面右下)             │
└──────────────────────────────────┘
```

## 目录结构

```
task-guide/
├── README.md                 # 本文件
├── BUG-REPORT.md             # 问题追踪
├── 运行指南.md                # 详细运行步骤
├── db/
│   └── schema.sql            # v3 统一数据库 Schema（双端共用）
├── desktop/                  # 电脑端（Tauri 2 / Rust + Web 前端）
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   ├── src/                  # Rust 后端（lib.rs / main.rs / sync.rs）
│   ├── ui/                   # Web 前端（HTML/CSS/JS）
│   ├── preview-server.js     # Node 预览服务器（不开 Tauri 也能看 UI）
│   └── icons/
├── mobile/                   # 手机端（Android / Kotlin + Compose）
│   ├── settings.gradle.kts
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/taskbar/app/    # 源码
│   │       │   ├── TaskBarApp.kt
│   │       │   ├── MainActivity.kt
│   │       │   ├── data/{model,db,repo}
│   │       │   ├── server/              # Ktor HTTP+WS 同步服务器
│   │       │   ├── notify/              # 通知 + WorkManager
│   │       │   ├── widget/              # 桌面小部件
│   │       │   └── ui/                  # Compose 界面
│   │       └── res/                     # 资源（字符串/主题/小部件）
└── docs/                     # 技术方案、协议
```

## 需求文档

完整需求定稿见：`deliverables/taskbar/2026-08-28/需求文档-任务栏.md`

## 技术栈

| 端 | 技术 |
|---|---|
| 手机 | Android Kotlin：Jetpack Compose + Room + AppWidget + WorkManager + Ktor 服务器 |
| 电脑 | Tauri 2：Rust 后端 + HTML/CSS/JS 前端（毛玻璃 / 原神风格） |
| 数据库 | 双端 SQLite（共用 schema.sql v3） |
| 同步 | mDNS 自动发现 + 手动 IP 兜底 + 持久化 pairing.json |

## 开发进度

- [x] 需求确认（v1.2 定稿）
- [x] 项目骨架（目录 + schema + 协议 + 技术方案）
- [x] P1 手机核心：Room 数据层 + 任务 CRUD + 步骤 + 追踪状态机
- [x] P2 手机提醒：WorkManager + 通知 + 紧急提醒
- [x] P3 手机小部件：AppWidget 追踪卡片
- [x] P4 手机服务器：Ktor HTTP API + WebSocket + mDNS
- [x] P5 电脑端：Tauri 窗口 + 本地库 + 原神风 UI v1
- [x] P6 电脑同步：同步客户端 + 离线缓存 + 配对持久化
- [x] P7 联调 + 预览
- [x] v3 重构：总览默认页 + 4 分类 + 仓库 + 紧急提醒 + 任务栏改名
- [x] v3.1：菱形缩小 + 蓝光动态边框
- [ ] v3.2：真机联调（Tauri build + Android Studio install）
- [ ] v4：统计 / 多用户 / 云备份

## 约束

- 不使用任何第三方付费服务
- 电脑客户端：默认 1100×720 全宽；折叠为 340×76 挂件
- 挂件闲置 CPU 接近 0，内存目标 30-60MB
- UI：半透明磨砂黑 + 暖金高亮 + 紫菱追踪 + 暖橘副文（非黑体、普通字重、紧凑间距）
