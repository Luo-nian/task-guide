# debug-v5.15.27（2026-09-14）

> 主题：分类栏视觉分层 / 切换响应 / 追踪卡收起重排 / 设置项可用性
> 索引见 [`BUG-INDEX.md`](./BUG-INDEX.md)

## C-023 ⭐「每日提醒没用了」的真因：闹钟只在开机时恢复 —— 已解决

**症状**（boss）：「设置里那个每日提醒 现在好像没什么用了啊」。

**排查**：
- 代码本身没坏：`DailyReminderScheduler.scheduleNext()` 用
  `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` 单发，触发后接收器自动重排次日。
- 但 `restoreAll()` **只被 `BootReceiver`（BOOT_COMPLETED）调用一次**。

**根因**：AlarmManager 的闹钟存在系统侧，**重装 APK / 强行停止 / 被系统清理后台时会被全部清空**。
我这一天为了验证反复 `adb install -r` + `am force-stop` → 闹钟早没了，
而代码只在**重启手机**时才补排 → 用户感知就是「这功能失效了」。

**修复**：`TaskBarApp.onCreate` 里补一次 `restoreAll()`
→ 只要打开过 App，起床/睡前提醒就一直是活的。
另外在设置页把「**下次：今天/明天 07:30**」写出来 —— 让"它是活的"这件事**可见**，
不用再去猜（boss 这类"看起来没用的功能"，多半都该配一个可见的状态反馈）。

> 通用教训：**靠系统调度（AlarmManager / JobScheduler / 通知）的功能，安装/强停/杀后台后都可能被清**。
> 只要有 BOOT_COMPLETED 恢复逻辑，就**同时**要在「应用冷启动」补一次，别只依赖重启。

---

## C-024 ⭐ 电脑端「收起分组」导致页面抖动 —— 已解决

**症状**（boss）：分类展开/收起时页面抖动。

**根因**：
```js
function toggleCatGroup(k) {
  if (_collapsedGroups.has(k)) _collapsedGroups.delete(k); else _collapsedGroups.add(k);
  if (typeof render === 'function') render();      // ← 元凶：整页重渲染
}
```
`renderTodayView()` 会 `lvEl.innerHTML = html` 重建整棵子树 →
① 滚动位置被重置；② DOM 全部重建 → 一帧闪动 = **抖动**。

**修复**：给分组容器加 `data-cg="<key>"`，`toggleCatGroup` 改为**就地操作**：
```js
document.querySelectorAll('.cat-group[data-cg="'+k+'"]').forEach(g => {
  g.classList.toggle('collapsed', on);
  const a = g.querySelector('.cg-arrow');
  if (a) a.textContent = on ? '\u25B8' : '\u25BE';
});
```
折叠状态仍写进 `_collapsedGroups`，下次 render 保持一致。

**验证**（CDP）：包一层 `window.render` 计数 → 点击收起后 **render 调用次数 = 0**；
原 `.cat-group-head` DOM 节点 `=== ` 点击后的节点（**未被重建**）→ 证明没有整页重渲染。

> 通用教训：**「就地改 class」永远优于「整页重渲染」** —— 后者会丢滚动位置、丢焦点、闪一下。
> 一个 `render()` 调用在原型阶段很方便，容易在加交互时变成抖动源。

---

## C-025 视觉分层类问题的共同真因：底色透明度太低 —— 已解决

| 位置 | 原值 | 问题 |
| --- | --- | --- |
| 手机 `SectionHeader` | **没有底色** | 只有一行文字，和页面背景同色 |
| 电脑 `.cat-group-head` | `rgba(232,203,127,0.34 → 0.06)` | 渐变右端 6% ≈ 背景 |
| 电脑分组头图标 | 13px + 浅描边 | 压在金色栏上几乎看不见 |

**修复**：手机 = 主题色 13% 淡染 + 1dp 描边 + 9dp 圆角；
电脑 = 渐变 `0.55 → 0.22` + 1px `rgba(154,123,26,.30)` 描边 + 8px 圆角；
图标 = **20px 圆角方块 + 白色内描边（1.5px）+ 外阴影**，图标强制白色。

> 通用教训：**"分层"类需求（要和背景区分）用 ≤10% 的透明度基本等于没做**；
> 下限大概在 0.2 以上才肉眼可辨（浅色底上尤其）。

---

## C-026 ⭐⭐「每日提醒」开关点了关不掉 —— 已解决（M7 的另一半真因）

**症状**：adb 点「起床」开关 → 界面**纹丝不动**，还是 ON（`uiautomator` dump 里 `checked="true"`）。

**根因**：`SettingsScreen` 里开关的状态是**普通 val**、直接读 prefs，不是 Compose state：
```kotlin
val morningOn = prefs.getBoolean("daily_morning_on", false)      // ← 不是 state
Switch(checked = morningOn, onCheckedChange = { on ->
    prefs.edit().putBoolean("daily_morning_on", on).apply()
    ...
    showMorningPicker = on                                       // ← 关掉时 = false，等于没改状态
})
```
**开** 的时候之所以看着正常：`showMorningPicker = true` 正好构成状态变化 → 顺带重组了一次；
**关** 的时候 `showMorningPicker = false`（本来就是 false）→ **无状态变化 → 不重组 → Switch 保持旧视觉**。
（prefs 其实已经写进去了、闹钟也真取消了 —— 只有界面在骗人，而这正是最坏的一种 bug：
用户看到"没反应"，就会认定"这功能坏了、没用了"。）

**修复**：`var morningOn by remember { mutableStateOf(prefs.getBoolean(...)) }`，回调里显式 `morningOn = on`。

**验证**：修复后同一坐标点击 → dump 显示 `checked="false"` ✅

> 通用教训：**Compose 里凡是被 UI 展示、又会因交互改变的值，必须是 `State`**。
> 直接读 SharedPreferences / DB 的"冷值"当 `checked`，只有"顺带被别的状态变化带出一次重组"时才看起来正常，
> 一旦某条分支不引起状态变化，就会静默不同步。**"开关点不动"优先怀疑这个。**

---

## 本版决策记录

### 去掉页面切换动画（M3/M6）

boss：「页面切换还是卡卡的，要等按键的交互结束之后才弹出来，太慢了」
+「页面切换的 UI 方向应该和按键方向一致你听不懂吗？不行你把页面切换去掉吧」

- 体感"慢半拍" = 按键反馈（PressIcon 180ms 缩放）+ 页面转场（240ms）叠加；
- 方向语义（我的 ← 主页 → 追踪）已按 `MAIN_PAGE_ORDER` 做过一版，boss 仍不认可。

→ 按原话**转场置 `None`（0ms）**，点下即切。主页面带**左右滑动手势保留**（是手势不是动画）。
想恢复：把 `NavHost` 的四个 transition 参数换回 `slideIntoContainer` 即可。

### 追踪卡形态定稿（M4，boss 两次修订）

1. 初版需求：收起时展开键放下面、取消追踪/完成键占回原展开位置；
2. 二次修订：「**收起也应该放在下面**，我改需求，而且**展开/收起二者字体大小一样**」。

**最终形态**：
- **卡头右侧**：常驻「取消追踪 / 完成」两个 22dp 圆键（展开、收起都一样）
- **卡片底部**：常驻一条把手，展开态写「收起 ▾」、收起态写「展开 ▸」，**同一字号 20sp**
- 卡框与主页追踪卡同款：`1.5dp + Azure(alpha 0.65) + 12dp 圆角`

→ 展开/收起只影响"中间那段步骤栏"的显隐，**键位不再跳来跳去**。

## 本版改动一览

| 端 | 项 | 改动 |
| --- | --- | --- |
| 手机 | M1 | `SectionHeader` 加主题色 13% 底色 + 描边 + 9dp 圆角 |
| 手机 | M2 | 分类栏三角 12 → 22sp（并用分组主题色） |
| 手机 | M3/M6 | NavHost 四个 transition 置 None（0ms 切换） |
| 手机 | M4 | 追踪卡：两键上移卡头 + 底部常驻展开/收起把手 |
| 手机 | M5 | 展开/收起三角加大，且两状态同字号 20sp |
| 手机 | M7 | `TaskBarApp.onCreate` 补 `restoreAll()` + 设置页显示「下次：…」 |
| 手机 | M8 | `isIgnoringBatteryOptimizations` 检测 + ON_RESUME 刷新；已保活则撤按钮显示「已保活 ✓」 |
| 手机 | M9 | 「我的」页新增昵称编辑（写 settings nickname + 同步电脑端） |
| 电脑 | P1 | 连接胶囊移到「任务栏」名字**右边**（名字不动），`margin-right:auto` 紧贴 |
| 电脑 | P2 | 分组栏底色加深 + 描边 |
| 电脑 | P3 | 三角 11 → 20px，改金色 |
| 电脑 | P4 | 分组头图标 13px → 20px 圆角方块 + 白描边 + 阴影 |
| 电脑 | P5 | `toggleCatGroup` 就地切类，不再整页重渲染 |
| 手机 | M10 | 「正在追踪」分组去收起（主页置顶 + 所有任务页），常显 |
| 电脑 | P6 | 菱形 6→8px、金边加亮（原来看不见） |
| 电脑 | P7 | 涟漪从**圆形 box-shadow** 改为**菱形波纹**（`.ct-diamond::after` 继承 rotate(45°) 的方框描边 + scale/opacity） |
| 电脑 | P8 | 扩散上限 3.0→2.1 倍 + 任务行 `overflow:hidden`，保证涟漪不出行 |
| 手机 | M12 | 详情页无步骤时标题只写「步骤」，不再显示「步骤 (0/0)」 |

## 端到端验证记录（v5.15.27）

| 项 | 方式 | 结果 |
| --- | --- | --- |
| M1 | 真机截图 | ✅ 分类栏出现主题色底条 + 描边 |
| M2 | 真机截图 | ✅ 三角 12→22sp、用分组主题色 |
| M4 | 真机截图（收起 + 展开） | ✅ 收起=头部两键+底部「展开 ▸」；展开=底部「收起 ▾」，两态同字号 20sp |
| M5 | 真机截图 | ✅ |
| M7 | 真机 + uiautomator | ✅ 显示「下次：明天 07:30」；开关可正常关闭（C-026 已修） |
| M8 | 真机截图 | ✅ 显示「已保活 ✓」状态条，按钮已撤掉 |
| M9 | 真机 + 电脑端 DB | ✅ 「我的」页 →「用户名 boss [修改]」；改成 boss2 → **桌面 DB 落库 `nickname=boss2`**；改回 boss ✅ |
| M3/M6 | 编译通过 | ✅ 转场置空（点击即切） |
| P1 | CDP DOM | ✅ 顶栏顺序 = `["title","link-pill on","top-right"]` |
| P2 | CDP 样式 | ✅ 渐变 0.55→0.22 + 1px 描边 + 8px 圆角 |
| P3 | CDP 样式 | ✅ 三角 20px、金色 `rgb(138,106,28)` |
| P4 | CDP 样式 | ✅ 图标 20×20 + 白色内描边 1.5px + 外阴影 |
| P5 | CDP 注入计数 | ✅ 收起后 **render 调用次数 = 0**，原 DOM 节点未被重建、箭头 ▾→▸ |
| M9 桌面实时 | CDP 轮询 | ✅ 新增 5s 对账（setting 变更不触发 onRemoteSyncApplied，原来要重启桌面才看得到） |

## 设计说明：为什么"圆形涟漪"要改成"菱形"

`box-shadow` 的扩散圈**永远跟随元素的 `border-radius`** —— 无论怎么写都只能画圆/圆角矩形。
用 box-shadow 在菱形上做波纹，结果一定是"圆环"，视觉上和外层发光糊在一起 ——
这就是 boss 说「涟漪应该是菱形的，不是圆形的」的原因。

**改法**：不用 box-shadow，改用**子元素 `::after`** —— 它**继承父级的 `rotate(45deg)`**，
所以一个普通**方框描边**转出来就是菱形；再对它做 `scale + opacity` 关键帧。

> 通用结论：**要"形状跟随本体"的动效，用元素 transform，不要用 box-shadow 扩散**。
> box-shadow 只能画圆角矩形，画不出菱形/三角这类轮廓。
> 另外"不要出容器"这类约束，**动画本身收范围 + 容器 `overflow:hidden` 兜底**，两手都要有。

## ⚠️ 待观察

- M3/M6 去掉转场后 boss 是否接受（他给的两个选项里选了"去掉"，但可能又觉得太生硬）
- M8：`isIgnoringBatteryOptimizations` 只覆盖"电池优化白名单"，
  iQOO 还额外需要「自启动 / 后台高耗电」权限，**这两项无法用 API 检测** →
  所以已保活时补了一行提示"若仍连不上，再检查系统的自启动/后台高耗电白名单"。
