# debug-v5.15.22（2026-09-13）

## C-001 🐛 双端同步单方向失效（M11）—— 已解决 ⭐⭐ 本版最重要发现

**症状**：boss 反复反馈「双端任务没能完全同步」。实测：桌面 74 行任务（59 活+15 软删），手机只有 40 行（28 活+12 软删），34 行手机端从未见过；打卡日志桌面 75 / 手机 36。

**根因有两层，第二层才是真凶**：

1. **桌面 → 手机没有全量推送**：`push_change` 是 fire-and-forget（后台线程 5s 超时、失败不重试），`full_sync` 只做"拉手机→写桌面"。手机不在线期间的桌面改动**永久丢失**。
2. **系统代理劫持了 reqwest 的全部 HTTP** ⭐：boss 电脑开着系统代理 `127.0.0.1:7892`（WinINET ProxyEnable=1）。reqwest 默认读取系统代理，把发往手机内网 `http://10.112.227.105:8899` 的请求也塞给代理 → 代理回 **502 Bad Gateway**；GET /api/sync/full 拿回代理错误页 → `error decoding response body`。**而 WS 用 tungstenite 不走代理，所以"WS 显示已连接、HTTP 全军覆没"** —— 这解释了：
   - 为什么手机→桌面同步基本正常（走 WS ChangeBus）
   - 为什么桌面→手机的单条推送全部静默丢失（502）
   - 为什么"已连接"状态是好的但数据不对（ misleading ）

**修复**：
- `sync.rs::lan_client()`：所有发往手机的 HTTP 一律 `.no_proxy()` 直连（sync.rs 4 处 + lib.rs 5 处全换）
- `sync.rs::push_full_to_mobile()`：WS 连上后把桌面全量 tasks/steps/habit_logs 打包 POST 给手机（批 120 条，失败重试 3 轮，60s 节流）；安全性由手机端 LWW 保证
- `sync.rs::sync_debug_log()`：同步全链路日志写到 **exe 同目录 `sync-debug.log`**（log crate 没接 logger，log::info! 全是空操作，之前根本没法排查）

**验证**：log 显示 `full_sync ok` + `push_full 完成 216/216`；双端比对任务 59/59、步骤 48/48、打卡 75/75，字段差异 0。

**教训**：
- **排查同步问题先看代理** —— "WS 通 HTTP 不通" = 代理劫持的招牌症状
- reqwest 读 WinINET 代理但**不完整遵守 ProxyOverride**（`10.*` 通配没生效），别指望"内网地址在排除列表里就没事"
- 没 logger 的程序等于盲飞，sync-debug.log 这种土办法值得保留

## C-002 D1「等级栏还是太扁」—— 已解决

上轮明明改了 `.level-card`（18px/96px）却没生效。真凶：**style.css 文件尾部还有一条 `.level-card { padding:10px 14px !important; min-height:56px }`**（老版"等级卡整修"），同名规则靠后生效 + !important 把新值全压了。改为把尾部规则直接更新成加厚值（padding 20/22、min-height 112、图标 68px）并去掉 !important。CDP getComputedStyle 确认 20px/112px 生效。
> 呼应铁律：**CSS 同名规则可能有多处定义，生效的是靠后那处**（.nav-count 就是这么坑的）—— 改样式前先 `grep -n "类名" style.css` 查全部定义点。

## C-003 M6 按键不对齐的真根因 —— 已解决

不是键的尺寸（PressIcon 恒 40dp），而是：
- TaskRow 外层 padding end=6dp，HabitRow=12dp → 两类行的键右缘差 6dp
- TaskRow 两键之间没有 Spacer，HabitRow 有 10dp → 键间距不一致
修复：TaskRow end 6→12dp（内层 chips 行 12→6dp 补偿），键间距统一 10dp，图标统一 22dp。TrackTaskCard 间距 14→10dp 对齐。

## C-004 手机端 HTTP 8899 只在 **app 前台**时监听 —— 已知限制

排查发现：app 进程活着但退到后台时，/proc/net/tcp6 里 8899 消失；monkey 拉起前台后出现（tcp6）。桌面端 WS 每 5s 重试，所以手机一回到前台同步自动恢复。**boss 要双端实时同步，手机端 app 得在前台**（或后续把 Ktor 服务挂到前台 Service 的生命周期外）。

## C-005 adb 无线调试连接不稳 —— 规避方法

mDNS 设备（`adb-xxxx._adb-tls-connect._tcp`）息屏即消失，且沙箱每次新起 adb server。可靠套路（一条命令内完成）：
1. `for` 循环 `adb devices` 抓 `device` 状态序列号（最长 45s，等亮屏）
2. `adb -s SER forward tcp:18899 tcp:8899`
3. 宿主机 curl `127.0.0.1:18899`（手机不用 curl，/proc/net/tcp6 看 0x22B3=8899 是否 LISTEN）
配对端口和连接端口不同：boss 给的 `IP:46505` 是**配对**端口（adb pair 用），connect 靠 mDNS 自动连。
