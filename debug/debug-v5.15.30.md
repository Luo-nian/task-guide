# debug · v5.15.30

## B-006 · 手机端自适应图标「指南针只剩半圈」（画错 + 被裁，不是设计）

**现象（boss 截图）**：手机端 App 图标是一道金色 ∪ 形弧线 + 一颗金色竖菱形，看着像"项链吊坠"。
**根因**：`mobile/app/src/main/res/drawable/ic_launcher_foreground.xml`
- 该文件设计意图是「淡金色指南针」，但 **圆心写在 `y=28`**（`M54,28 m-26,0 a26,26 ...`）
- Android 自适应图标前景画布是 **108×108dp**，中心是 `(54,54)`，**安全区只有中心 66dp 圆**
- 圆画在 y=28 → 上半圈整个超出画布被裁 → **只剩下半圈那道 ∪ 弧**
- 菱形指针 `M54,38 L60,54 L54,70 L48,54 Z` 正好落在中心 → 看起来就成了"吊坠"

**判据**：拿到的是 "半圈 + 菱形" 而不是 "完整指南针"，就是这条。

**修法**：所有前景图形必须画在 `108×108` 画布的中心 **66dp 安全区**内（即坐标限制在 `x,y ∈ [21,87]`）；
或直接把 canvas 坐标系整体下移——把圆心从 `y=28` 改到 `y=54`。
**顺带**：本次 R17 决定统一双端图标，前景图形整体换成电脑端那套「三条清单 + 金勾」，此文件会重写，改的时候一并按 66dp 安全区画。

**验证方式**：真机装包后看桌面图标；或 `adb shell` 抓 launcher 图标位图比对。


---

## B-007 · 桌面连不上手机 = `pairing.json` 存着过期 IP（`push_full` 报 HTTP 500）

**现象**：`sync-debug.log` 里反复出现
```
WS 连接失败(5s后重试): URL error: Unable to connect to ws://10.103.157.33:8899/ws
push_full batch#0 失败: HTTP 500 Internal Server Error
push_full_to_mobile: 完成 0/237 条
```
但手机 App 明明开着、`http://<手机IP>:8899/api/ping` 也返回 ok。

**根因**：桌面端的手机地址持久化在
`C:\Users\<用户>\.taskguide\pairing.json`（`lib.rs::pairing_file()`，config_dir = `%TASKGUIDE_HOME%` 或 `%USERPROFILE%\.taskguide`）。
手机 DHCP 换 IP 后（本次 `10.103.157.33` → `10.103.157.105`），**这个文件仍留着旧 IP，而且还会被重新写回旧值**
（`savedAt` 在重启后仍被刷新）→ 桌面一直连一个不存在的地址。

⚠️ **注意 `HTTP 500` 是误导**：那只是连不上/半连接状态下的表象，**不是服务端代码 bug**。
换对地址后 `push_full` 立刻 **完成 235/235 条**，一次没失败。

**判据（下次 boss 说"连不上/同步不生效/数据不一致"先看这个）**：
1. `adb -s <dev> shell ip -4 addr show wlan0 | grep inet` 拿手机**当前** IP
2. 对比 `%USERPROFILE%\.taskguide\pairing.json` 里的 `url`
3. 不等 → 改成当前 IP（保留 `deviceId` 不动），重启客户端

**临时修（本次采用）**：直接改 `pairing.json` 的 `url`。改完立刻 `WS 已连接 → full_sync ok → push_full 235/235`。
**根治**：这就是 **B-005**（靠 `deviceId` 自动重发现）。没做之前，每次手机换 IP 都要手动改一次。
