# debug-v5.28.1（2026-09-23 深夜，C-031 续：上一轮定案不完整，真机抓到现行）

## C-031 续 · 「追踪名额已满（1/3）」误报（boss 真机实测炸雷）

### 第一轮定案错在哪

- 我在 v5.28.0 验收时宣布"追踪问题已解决"：依据是计数口径带 `deleted=0`（真机计数=1）
  + 设置页实况一致。**但没有真机点一次追踪按钮**。
- boss 手测点习惯「健身」的「追踪」→ 弹「追踪名额已满（1/3）」→ 我被当场打脸。
- 真凶有两条，都和"计数口径"无关：
  1. **A3 弹窗误报**：`vm.startTracking(uuid) { ok -> if (!ok) showLimitGate = true }`
     —— startTracking 的 false 有三种原因（任务不存在/已完成/名额满），UI 一律当"名额满"。
  2. **daily 任务被"已完成"校验拦**：每日习惯昨天打卡后 DB `done=1`（跨天折算只在读取层、
     不落库），v5.26.0 的 `if (target.done == 1) return false` 把它拦了。
     → 健身这条路径：done=1 → false → UI 弹"名额已满(1/3)"。三重错叠加。

### 修复（v5.28.1）

- `Models.kt`：新增 `enum TrackStartResult { OK, LIMIT_REACHED, ALREADY_DONE, NOT_FOUND }`
- `TaskRepository.startTracking`：返回细分结果；daily 放行（`done==1 && !isDaily` 才拦）
- `TaskViewModel.startTracking`：透传 `TrackStartResult`
- UI 三处（Screens.kt HabitRow/TaskRow、TaskDetailScreen.kt）：只有 `LIMIT_REACHED` 才弹窗/toast
- ⭐ 教训：**返回值语义**——一个函数有多种失败原因时，禁止返回 Boolean 让 UI 猜。

## v5.28.1 其他两改

- **我的页分数行删除**（boss：问候语和身份卡之间夹分数不好看）——副行「N 分」删掉，
  分数身份卡右侧本来就有。真机验证无该行。
- **习惯页入口补上**（boss：哪里有习惯页？）——`composable("habit")` 是死路由（全项目无
  navigate 调用），v5.28.0 C1 补卡功能藏在进不去的页面。「我的」页「习惯坚持」卡加
  clickable + 「›」→ 进「每日任务」页。真机验证可达、打卡/补卡可见。

## 真机验收记录（2026-09-23 深夜，全部 tap 实测）

- tap 健身「追踪」(744,1101) → 主页「正在追踪 2」，两任务均「追踪中」，无弹窗 ✅
- 顶栏身份徽章 (96,186) → 我的页：「夜深了，历练学徒」直接接身份卡，无独立分数行 ✅
- 「习惯坚持」卡 (822,1048) → 「每日任务」页，健身/1 均有「打卡」「补卡」键 ✅
- 编译：一次失败（repo 缺 import TrackStartResult）→ 补 import → BUILD SUCCESSFUL 1m14s
