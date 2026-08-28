-- ============================================================
-- 任务指南 TaskGuide —— 统一数据库 Schema v1.0
-- 双端（Android / Tauri）共用此结构，保证同步一致
-- SQLite 方言
-- ============================================================

PRAGMA foreign_keys = ON;

-- ------------------------------------------------------------
-- 任务表：一次性 / 重复 / 无时限速记 / 定时习惯 / 周期目标
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tasks (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,   -- 本地自增主键
    uuid          TEXT    NOT NULL UNIQUE,             -- 跨端唯一标识（同步核心）
    type          TEXT    NOT NULL DEFAULT 'once',     -- once | repeat | note | habit | goal
    title         TEXT    NOT NULL,                    -- 任务标题
    desc          TEXT    DEFAULT '',                  -- 备注
    category      TEXT    DEFAULT '',                  -- 自定义分类（学习/生活/锻炼...）
    priority      TEXT    NOT NULL DEFAULT 'medium',   -- high | medium | low
    due_at        INTEGER DEFAULT NULL,                -- 提醒时间（Unix 毫秒时间戳）
    repeat_rule   TEXT    DEFAULT NULL,                -- 重复规则：daily / weekly:1,4 / monthly:15
    deadline      INTEGER DEFAULT NULL,                -- 周期目标截止日（Unix 毫秒时间戳）
    track_status  TEXT    NOT NULL DEFAULT 'pending',  -- pending(未开始) | tracking(追踪中) | done(已完成)
    done          INTEGER NOT NULL DEFAULT 0,          -- 快捷完成标记 0/1
    done_at       INTEGER DEFAULT NULL,                -- 完成时间
    delayed_count INTEGER NOT NULL DEFAULT 0,          -- 延迟次数（统计拖延用）
    created_at    INTEGER NOT NULL,                    -- 创建时间
    updated_at    INTEGER NOT NULL,                    -- 最后修改时间（同步冲突判断）
    deleted       INTEGER NOT NULL DEFAULT 0           -- 软删除 0/1（同步用）
);

-- ------------------------------------------------------------
-- 步骤表：任务拆解为多步骤，逐步推进（原神追踪模式）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS steps (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid        TEXT    NOT NULL UNIQUE,
    task_uuid   TEXT    NOT NULL REFERENCES tasks(uuid),
    title       TEXT    NOT NULL,                      -- 步骤名
    status      TEXT    NOT NULL DEFAULT 'todo',       -- todo | doing | done
    attr_label  TEXT    DEFAULT '',                    -- 自定义属性标签（"距离"、"进度"）
    attr_value  TEXT    DEFAULT '',                    -- 自定义属性值（"5857m"、"3/10"）
    sort_order  INTEGER NOT NULL DEFAULT 0,            -- 步骤顺序
    done_at     INTEGER DEFAULT NULL,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_steps_task ON steps(task_uuid);
CREATE INDEX IF NOT EXISTS idx_steps_status ON steps(status);

-- ------------------------------------------------------------
-- 习惯打卡记录表（定时习惯类型任务专用）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS habit_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_uuid   TEXT    NOT NULL REFERENCES tasks(uuid),
    check_date  TEXT    NOT NULL,                      -- 打卡日期 YYYY-MM-DD
    created_at  INTEGER NOT NULL,
    UNIQUE (task_uuid, check_date)                     -- 一天只能打一次卡
);

CREATE INDEX IF NOT EXISTS idx_habit_logs_task ON habit_logs(task_uuid);

-- ------------------------------------------------------------
-- 同步元信息表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_meta (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device          TEXT    NOT NULL,                  -- mobile | desktop
    last_sync       INTEGER DEFAULT 0,                 -- 最后同步时间戳
    last_change_id  INTEGER DEFAULT 0                  -- 增量同步游标
);

-- ------------------------------------------------------------
-- 设置表（键值对）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- ------------------------------------------------------------
-- 默认设置
-- ------------------------------------------------------------
INSERT OR IGNORE INTO settings (key, value) VALUES
    ('track_limit',      '3'),                          -- 同时追踪上限
    ('reminder_strength','standard'),                   -- standard | repeat | alarm
    ('auto_start',       '1'),                          -- 电脑挂件开机自启 0/1
    ('delay_options',    'custom'),                     -- 延迟天数方式：custom=自定义
    ('theme',            'frosted');                    -- frosted(毛玻璃) | card(简洁卡片)
