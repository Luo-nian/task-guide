-- 任务栏桌宠 副机测试数据 v2（丰富状态，让 UI 更丰富）
-- 用法：adb push 到 /data/local/tmp/test_data_v2.sql，sqlite3 taskguide.db < test_data_v2.sql

-- 清空旧测试数据（保留真实数据：以 uuid 不以 0000000 开头的）
DELETE FROM steps WHERE task_uuid LIKE '0000000%-%';
DELETE FROM tasks WHERE uuid LIKE '0000000%-%';
DELETE FROM habit_logs WHERE task_uuid LIKE '0000000%-%';

-- 8 个任务 + 6 步骤 + 1 习惯打卡
-- 时间戳：今天 9:00 ~ 18:00
INSERT INTO tasks (uuid, type, title, desc, category, priority, due_at, repeat_rule, deadline, track_status, done, done_at, delayed_count, reward_points, reminder_strength, progress, target, created_at, updated_at, deleted) VALUES
('00000001-0000-0000-0000-000000000001','goal','减重 5kg','三个月内减到 65kg','锻炼','high',strftime('%s','now','+2 hours')*1000,NULL,strftime('%s','now','+90 days')*1000,'tracking',0,NULL,0,22,NULL,0,1,strftime('%s','now','-3 days')*1000,strftime('%s','now')*1000,0),
('00000002-0000-0000-0000-000000000002','once','通读+做笔记+复习','学习《原则》第 5 章','学习','high',strftime('%s','now','+4 hours')*1000,NULL,NULL,'pending',0,NULL,0,15,NULL,0,1,strftime('%s','now','-1 day')*1000,strftime('%s','now')*1000,0),
('00000003-0000-0000-0000-000000000003','once','买牛奶','楼下便利店','生活','low',strftime('%s','now','+6 hours')*1000,NULL,NULL,'pending',0,NULL,0,9,NULL,0,1,strftime('%s','now','-30 minutes')*1000,strftime('%s','now')*1000,0),
('00000004-0000-0000-0000-000000000004','habit','晨跑 30 分钟','坚持跑步','锻炼','medium',NULL,'daily',NULL,'pending',0,NULL,0,5,NULL,0,1,strftime('%s','now','-7 days')*1000,strftime('%s','now')*1000,0),
('00000005-0000-0000-0000-000000000005','habit','每日读书 1 小时','读完一本好书','学习','medium',NULL,'daily',NULL,'pending',0,NULL,0,5,NULL,0,1,strftime('%s','now','-5 days')*1000,strftime('%s','now')*1000,0),
('00000006-0000-0000-0000-000000000006','repeat','写周报','本周工作复盘','工作','medium',strftime('%s','now','+1 day')*1000,'daily',NULL,'pending',0,NULL,0,14,NULL,0,1,strftime('%s','now','-2 days')*1000,strftime('%s','now')*1000,0),
('00000007-0000-0000-0000-000000000007','milestone','完成 v5.0 原型','10 次迭代','工作','high',strftime('%s','now','+30 days')*1000,NULL,NULL,'tracking',0,NULL,0,20,NULL,2,5,strftime('%s','now','-10 days')*1000,strftime('%s','now')*1000,0),
('00000008-0000-0000-0000-000000000008','note','整理桌面','工位整理','生活','low',NULL,NULL,NULL,'done',1,strftime('%s','now','-2 hours')*1000,0,3,NULL,0,1,strftime('%s','now','-2 days')*1000,strftime('%s','now','-2 hours')*1000,0),
-- 仓库（未来）任务：测试置顶/置底
('00000009-0000-0000-0000-000000000009','once','准备季度汇报','Q4 数据整理','工作','medium',strftime('%s','now','+5 days')*1000,NULL,NULL,'pending',0,NULL,0,11,NULL,0,1,strftime('%s','now','-1 day')*1000,strftime('%s','now')*1000,0);

-- 6 步骤：分配给减重 5kg (3) 和 通读+做笔记+复习 (3)
INSERT INTO steps (uuid, task_uuid, title, status, attr_label, attr_value, sort_order, done_at, created_at, updated_at, deleted) VALUES
('00000001-0000-0000-0000-0000000000a1','00000001-0000-0000-0000-000000000001','控制饮食，少吃精制糖','doing','','',0,NULL,strftime('%s','now','-3 days')*1000,strftime('%s','now')*1000,0),
('00000001-0000-0000-0000-0000000000a2','00000001-0000-0000-0000-000000000001','每周 3 次有氧运动 30 分钟','todo','频率','每周3次',1,NULL,strftime('%s','now','-3 days')*1000,strftime('%s','now')*1000,0),
('00000001-0000-0000-0000-0000000000a3','00000001-0000-0000-0000-000000000001','每月记录体重变化','todo','','',2,NULL,strftime('%s','now','-3 days')*1000,strftime('%s','now')*1000,0),
('00000002-0000-0000-0000-0000000000a4','00000002-0000-0000-0000-000000000002','通读第 5 章全部内容','doing','页数','约 60 页',0,NULL,strftime('%s','now','-1 day')*1000,strftime('%s','now')*1000,0),
('00000002-0000-0000-0000-0000000000a5','00000002-0000-0000-0000-000000000002','做读书笔记并整理要点','todo','','',1,NULL,strftime('%s','now','-1 day')*1000,strftime('%s','now')*1000,0),
('00000002-0000-0000-0000-0000000000a6','00000002-0000-0000-0000-000000000002','用笔记复习并对照自身工作','todo','','',2,NULL,strftime('%s','now','-1 day')*1000,strftime('%s','now')*1000,0);

-- 1 习惯打卡：晨跑 30 分钟 今日已打卡（测试习惯完成态）
INSERT INTO habit_logs (task_uuid, check_date, created_at) VALUES
('00000004-0000-0000-0000-000000000004', strftime('%Y-%m-%d','now'), strftime('%s','now')*1000);

-- 重置积分到一个有意义的值（触发升级测试：Lv.2 临近）
UPDATE settings SET value = '28' WHERE key = 'total_points';
