# debug-v5.29.0（最终版验证战报）

> 2026-09-25 晚 ｜ 验证环境：boss 手机（vivo，全新安装）+ 副屏桌面（CDP 驱动）

## C-033 · 桌面 GET 时间线 401 bad_sig（已解决）

- **现象**：配对成功、WS 已连接、POST 同步全通，但 `task_changes` 拉时间线返回 `HTTP 401 {"reason":"bad_sig"}`
- **根因**：`sync.rs secure_get()` 把 `path` 整串（含 `?task_uuid=...`）传给签名；手机端 `Auth.kt guard()` 用 **`request.path()`**（Ktor，不含 query）验签 → 两边签的不是同一个字符串
- **为何以前没炸**：`secure_get` 此前唯一调用方是 `/api/sync/full`（无 query）。本版第一个带 query 的 GET 踩中
- **修复**：`secure_get` 按 `?` 拆分——请求发完整 URL，签名与响应验签都只用 `?` 前的 path
- **教训**：**带 query 的 GET 签名，两端必须约定"签 path 不签 query"**；新引入带参 GET 时必须真机过一次（编译通过 ≠ 签名一致）

## 本版其余验证结论（直接可抄）

- **判别值验 B-009**：42 分 → 新表 Lv1 历练学徒（旧 5 级表会显 Lv2 风华游侠）；完成 +14 → 56 分 Lv2。一条数据即可判别新旧表
- **闹钟随任务完成自动撤销**：桌面 complete → 手机 `dumpsys alarm` 该时刻消失（v5.28.4 完成撤钟链路在同步路径下同样生效）
- **历史任务弹窗行（.lg-row）左键不开详情**——boss v5.15.16 定过的交互（右键=恢复到今日）。验证详情请走今日列表/搜索
- **重装后恢复链**（数据目录被清时）：①重输 owner 码点亮完整版（`secrets/owner-code.txt`）②手机 设置→配对 进「等待电脑申请配对」③桌面 设置→解除配对→手动配对 填 `http://<手机IP>:8899` ④手机弹窗点允许 → 双端已连接
- **数据目录被清的取证法**：`dumpsys package | grep firstInstallTime` —— firstInstallTime=本次装机时间 ⇒ 装机前 App 就不在（不是装机清的数据）
- **launch-debug.cmd 在 Git Bash 下别用**（中文注释 GBK 乱码炸掉）；直接 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=... ./taskbar-desktop.exe &`

## D-01 闭环证据链（本版新增功能）

1. 桌面完成任务 → 手机 `change_logs` 新增 `('亚克','complete','完成了任务')`（run-as 拉库实查）
2. 桌面详情「▶ 动态」区渲染：`亚克 修改了任务 20:22｜亚克 创建了任务 20:12`
3. 截图：`build/shots/v529-timeline-final.png`、`v529-datepicker.png`（A-01）、`v529-home-tag.png`（A-03）
