# C-032 ｜ 桌面改任务后手机闹钟不重排（旧闹钟照响、新时间不响——WS 接收路径漏接重排）

- **版本**：v5.28.2 → v5.28.3 修复
- **现象**（真机+真桌面联测抓获）：
  桌面端把每日任务「联测任务X」的提醒时间从 09:00 改到 01:29 → 手机库 `due_at` 同步到位，但 `dumpsys alarm` 里**没有 01:29 的新闹钟**，09:00 的旧闹钟还挂着。到点不响/响错时间。
- **根因**：
  v5.26.0 修过「UI 写路径」的 rescheduleOne（任务编辑保存后重排），但**桌面→手机的变更到达路径一直漏着**：
  - `ApiRoutes /api/sync/changes`（批量推送，push_full 也走这里）→ `applyChange()` 后无重排
  - `ApiRoutes /ws` 实时单条 → `applyChange()` 后无重排
  排程入口只有三处（冷启动 onCreate / BootReceiver / 上条闹钟响过），WS 接收不在其中。
- **修**（v5.28.3，ApiRoutes.kt 两处）：
  task 实体 applyChange 成功后 `ReminderScheduler.rescheduleAll(repo, app)`。
  rescheduleAll 幂等（唯一名 REPLACE + setAlarmClock），顺带把旧时间的闹钟撤掉。
- **验证**（真实链路全通）：
  桌面改 09:00→01:38 → 手机 dumpsys 出现 `01:38:00 RTC_WAKEUP ACTION_REMINDER_ALARM`、09:00 旧闹钟消失；
  01:38 到点：fire_trace 全链 `onReceive → fire-start → 守卫通过 → showReminder`，通知发出（ch_notify、三按钮、锁屏隐藏 vis=SECRET）；
  App 在后台时主页出现「你错过了 1 条提醒：联测任务X」补看横幅。
- **测试手法坑（重要）**：
  **vivo 拦截 `adb shell am broadcast` 到 exported=false 组件的显式广播**——dumpsys 显示 "dispatch+finish 正常" 但 receiver 从未执行（fire_trace 无记录实锤）。
  → 以后验证提醒**只能用真闹钟**（改提醒时间到 2 分钟后等它响）或 App 内演示键，别信 `am broadcast`。
- **教训**：
  「写路径接重排」这类契约，新增一条**数据到达路径**（WS 接收）时必须重新清点全部入口。
  另：覆盖安装 APK 后系统会清空 App 的全部 AlarmManager 闹钟——冷启动 rescheduleAll 是唯一恢复机会，它必须万无一失。
