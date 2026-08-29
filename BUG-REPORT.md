# BUG-REPORT —— 任务栏错误记录

> 用途：记录顽固 bug 的「现象 / 原因 / 解决」，避免同一个坑踩两次。
> 规则：每解决一个非trivial 的错误就追加一条。格式见下方模板。

---

## 记录模板

```
### [日期] 简短标题
- **现象**：
- **原因**：
- **解决**：
- **教训**（如何避免再犯）：
```

---

## 已记录

### 2026-08-29 Room 注解重复 version 参数
- **现象**：`AppDatabase` 的 `@Database` 注解里同时出现 `version = 1` 和 `version = 2`，编译报重复参数
- **原因**：用 Edit 工具局部替换时只匹配了 `exportSchema = false\n)` 片段，没注意上文已有 `version = 1`
- **解决**：改为单一 `version = 2`
- **教训**：改 Kotlin 注解类参数前，先 Read 整个注解块再整体替换，不要片段 Edit

### 2026-08-29 Rust 函数改签名后调用点漏改
- **现象**：`sync::full_sync` 签名从 `(state)` 改为 `(&db, &url)` 后，`connect_server` 里仍用旧写法 `full_sync(&state)`，编译不通过
- **原因**：改签名时只改了新调用的地方，没全局搜旧调用点
- **解决**：`connect_server` 改为 `sync::full_sync(&state.db, &state.server_url)`
- **教训**：Rust 改函数签名后，全局 grep 该函数名检查所有调用点

### 2026-08-29 Git Bash 里 taskkill 参数报错
- **现象**：`taskkill //F //PID 12345` 报「无效参数/选项 - '//F'」
- **原因**：Git Bash 的斜杠转义规则与 cmd 的 `/` 参数冲突
- **解决**：改用 PowerShell `Stop-Process -Id <PID> -Force`
- **教训**：Windows 上杀进程一律用 PowerShell，不用 taskkill

### 2026-08-29 预览服务器端口被占用（EADDRINUSE）
- **现象**：`node preview-server.js` 报 `EADDRINUSE :::3000`
- **原因**：上一次的 node 进程没被 kill 干净（或残留多个）
- **解决**：`netstat -ano | grep ":3000"` 找所有 LISTENING 的 PID，PowerShell `Stop-Process -Id <PID> -Force` 逐个杀
- **教训**：改完代码重启预览前，先确认旧进程已退出

---

## 待解决（首次编译时补记）

_（本地编译 Android / Tauri 时遇到的报错，按模板记到这里，AI 可据此逐条修）_

### 2026-08-29 分类按钮互斥失败（点别的也亮）
- **现象**：新建任务时点"每日锻炼"两个都亮；点"买辣条"还是显示"每日锻炼"
- **原因**：原实现里每次点击都把 `active` class 加到 `e.currentTarget`，但没有先清空兄弟节点的 active，导致多个按钮同时高亮
- **解决**：在 cat-btn / ddl-btn / prio-btn 的 click handler 里先 `querySelectorAll(...).forEach(x => x.classList.toggle('active', x === e.currentTarget))`，再做本节点处理
- **教训**：CSS `~` 兄弟选择器配合单选逻辑更稳；或者显式 toggle 一遍其他兄弟

### 2026-08-29 客户端 grid 把 side-nav 和 main-pane 堆到同一格
- **现象**：截图里侧栏 4 个图标和 4 张分类卡片挤在左边一列，文字竖排
- **原因**：`.widget` 的 grid 里 `.side-nav` 和 `.main-pane` 都写了 `grid-row: 2; grid-column: 1`，落到了同一个 cell
- **解决**：grid 改成 `grid-template-columns: 64px 1fr`，side-nav 占第 1 列、main-pane 占第 2 列；topbar / fab / overlay 用 `grid-column: 1 / span 2`
- **教训**：用 grid 布局时每个直接子元素必须落在一个唯一 cell，跨行/跨列用 `span N` 而非重叠

### 2026-08-29 挂件模式 grid 收缩为 0
- **现象**：进入 `.widget-mode` 后，里面所有内容 0×0，挂件显示空
- **原因**：`grid-template-rows: auto 1fr auto` + 所有子元素都 `display: none` → 三行高度全为 0
- **解决**：挂件模式覆盖 `grid-template-rows: 1fr; grid-template-columns: 1fr;`，只让 folded-view 占整个 cell
- **教训**：grid 行高是子元素内容驱动的，子元素全隐藏就塌成 0；要在父级显式覆盖 grid 模板

### 2026-08-29 folded-view 在 main-pane 里导致挂件空白
- **现象**：挂件模式下小条虽然渲染，但内容是空白
- **原因**：folded-view 之前是 main-pane 的子元素，挂件模式把 main-pane `display: none`，连带 folded-view 也隐藏
- **解决**：把 folded-view 提到 .widget 顶级，独立 grid 占位
- **教训**：要切换可见性的视图元素要放在稳定的容器里，别嵌套在会被一起关掉的容器中
