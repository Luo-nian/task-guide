// 任务栏 桌面端 Node 预览服务器 v4
// 暖色浅色 + 三栏布局 + 等级 + 进度条 + 步骤推进 + 22:00 提醒
// 运行：node preview-server.js  → 浏览器打开 http://localhost:3000
const http = require('http');
const fs = require('fs');
const path = require('path');

const UI_DIR = path.join(__dirname, 'ui');
const PORT = 3000;

function nowMs() { return Date.now(); }

// ====== Mock 任务对象 ======
function newTask(uuid, title, opts = {}) {
  return {
    uuid, title,
    track_status: opts.track_status || 'pending',
    type: opts.type || 'once',
    desc: opts.desc || '',
    category: opts.category || opts.cat || 'once',
    priority: opts.priority || 'medium',
    due_at: opts.due_at ?? null,
    deadline: opts.deadline ?? null,
    reward_points: opts.reward_points ?? 10,
    count: opts.count ?? 1,                // 次数任务总次数
    done_count: opts.done_count ?? 0,      // 次数任务已完成次数
    delayed_count: 0, done: 0, done_at: opts.done_at ?? null,   // 原来硬编码 null，历史任务的完成时间被吞掉
    created_at: opts.created_at ?? nowMs(),
    updated_at: opts.updated_at ?? nowMs(),
    deleted: 0
  };
}

const DEADLINE_MS = {
  none: null, '5min': 5*60_000, '60min': 3600_000, '6h': 6*3600_000, '1d': 86400_000,
  '3d': 3*86400_000, '7d': 7*86400_000, '30d': 30*86400_000
};
function catToType(cat) {
  return { 'daily':'habit','goal':'goal','time-limited':'once','once':'once' }[cat] || 'once';
}

// 等级
const LEVELS = [
  { lv:1, name:'历练学徒',  title:'敢开始，就已经赢了一半',      char:'🗡', icon:'1', min:0,   max:20  },
  { lv:2, name:'风华游侠',  title:'汗水从不会辜负你',            char:'🏹', icon:'2', min:20,  max:60  },
  { lv:3, name:'破浪骑士',  title:'风浪越大，越显本色',          char:'🛡', icon:'3', min:60,  max:120 },
  { lv:4, name:'群星行者',  title:'你走过的每一步都算数',        char:'✨', icon:'4', min:120, max:200 },
  { lv:5, name:'传奇勇者',  title:'你就是自己的传说',            char:'👑', icon:'5', min:200, max:999 }
];
function levelOf(p) {
  let cur = LEVELS[0];
  for (const lv of LEVELS) if (p >= lv.min) cur = lv;
  return cur;
}
function nextLevelOf(p) {
  for (const lv of LEVELS) if (p < lv.min) return lv;
  return null;
}

const now = nowMs();

// 步骤 mock（task_uuid → step[]）
const mockSteps = {
  t1: [
    { uuid:'s1', title:'通读第 1 章 引言', status:'done',   attr_label:'进度', attr_value:'1/12' },
    { uuid:'s2', title:'做第 1 章课后习题', status:'doing',  attr_label:'待做', attr_value:'8 题' },
    { uuid:'s3', title:'通读第 2 章',     status:'todo',   attr_label:'',      attr_value:'' }
  ],
  t2: [
    { uuid:'s4', title:'复习 FFT 算法',   status:'doing',  attr_label:'进度', attr_value:'60%' },
    { uuid:'s5', title:'复习 Z 变换',     status:'todo',   attr_label:'',     attr_value:'' }
  ],
  tDaily: [
    { uuid:'sd1', title:'30 分钟慢跑',   status:'todo', attr_label:'', attr_value:'' },
    { uuid:'sd2', title:'拉伸 5 分钟',    status:'todo', attr_label:'', attr_value:'' }
  ],
  t3: [
    { uuid:'s7', title:'整理第 3 章笔记', status:'done',  attr_label:'', attr_value:'' },
    { uuid:'s8', title:'完成 8 道课后题', status:'doing', attr_label:'', attr_value:'5/8' }
  ]
};

let mockState = {
  totalPoints: 95,
  tasks: [
    // 追踪中
    newTask('t1', '一个月看完《深入理解计算机系统》', {
      track_status:'tracking', type:'goal', cat:'goal', priority:'high', category:'goal',
      desc:'每天看一点，12 章一个月啃完。',
      created_at: now - 30*86400_000, updated_at: now - 3600_000
    }),
    newTask('t2', '复习数字信号处理', {
      track_status:'tracking', type:'goal', cat:'goal', priority:'high', category:'goal',
      desc:'期末复习，重点 FFT 和 Z 变换。',
      created_at: now - 7*86400_000, updated_at: now - 1800_000
    }),
    // 每日任务
    newTask('tDaily', '每日锻炼', {
      type:'habit', cat:'daily', priority:'medium', category:'daily',
      deadline: now + 24*3600_000, desc:'每天 30 分钟慢跑', updated_at: now - 7200_000
    }),
    newTask('tDaily2', '背 50 个单词', {
      type:'habit', cat:'daily', priority:'medium', category:'daily',
      updated_at: now - 3600_000
    }),
    // 限时任务（t3 今天 30 分钟后截止 → 算今日任务；t5 明天截止 → 只在限时任务分类出现）
    newTask('t3', '交数字信号处理作业', {
      type:'once', cat:'time-limited', priority:'high', category:'time-limited',
      due_at: now + 30*60_000, deadline: now + 30*60_000,
      desc:'第 3 章课后作业，拍照上传。'
    }),
    newTask('t5', '给导师发周报', {
      type:'once', cat:'time-limited', priority:'medium', category:'time-limited',
      due_at: now + 30*3600_000, deadline: now + 30*3600_000
    }),
    // 次数任务
    newTask('tSnack', '买辣条', {
      type:'once', cat:'once', priority:'low', category:'once', count: 1, done_count: 0
    }),
    newTask('tWater', '今日喝 8 杯水', {
      type:'once', cat:'once', priority:'low', category:'once', count: 8, done_count: 5
    }),
    newTask('t4', '买根数据线', {
      type:'note', cat:'once', priority:'low', category:'once', count: 1, done_count: 0
    })
  ],
  archive: [
    newTask('tdone1', '看完操作系统导论第二版', { track_status:'done', cat:'goal', category:'goal', done_at: now - 86400_000, updated_at: now - 86400_000 }),
    newTask('tdone2', '六级真题一套', { track_status:'done', cat:'once', category:'once', done_at: now - 2*86400_000, updated_at: now - 2*86400_000 }),
    newTask('tdone3', '整理桌面垃圾文件', { track_status:'done', cat:'once', category:'once', done_at: now - 3*86400_000, updated_at: now - 3*86400_000 }),
    newTask('tdone4', '复习线性代数第 3 章', { track_status:'done', cat:'goal', category:'goal', done_at: now - 5*86400_000, updated_at: now - 5*86400_000 })
  ],
  settings: {},
  pairings: {}
};

const MIME = { '.html':'text/html', '.css':'text/css', '.js':'application/javascript', '.json':'application/json' };
function findTask(uuid) {
  return mockState.tasks.find(t => t.uuid === uuid)
      || mockState.archive.find(t => t.uuid === uuid)
      || null;
}

// 进度条 mock：今日任务 = 每日 + 当日限时；额外完成 = 次数任务超额
function computeDailyProgress() {
  const todayTasks = mockState.tasks.filter(t => {
    if (t.track_status === 'done') return false;
    if (t.category === 'daily') return true;
    if (t.category === 'time-limited' && t.due_at) {
      const d = new Date(t.due_at), n = new Date();
      return d.toDateString() === n.toDateString();
    }
    return false;
  });
  const total = todayTasks.length;
  let done = 0, over = 0;
  for (const t of todayTasks) {
    if (t.category === 'once' && t.count > 1) {
      // 次数任务按 done_count 算
      done += t.done_count || 0;
    } else if (t.track_status === 'done' || t.daily_done) {
      done += 1;
    }
  }
  if (done > total) { over = done - total; done = total; }
  const week = mockState.archive.filter(t => (t.done_at || 0) > now - 7*86400_000).length;
  return { done, total, over, week, style: mockState.settings.progress_style || 'bar' };
}

// 简单 step 存储（mock 内存）
const mockStepState = JSON.parse(JSON.stringify(mockSteps));

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  const q = Object.fromEntries(url.searchParams);

  if (p.startsWith('/api/')) {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const cmd = p.slice(5);
    try { await handleApi(cmd, q, req, res); } catch (e) { res.writeHead(500); res.end('{"error":"' + e.message + '"}'); }
    return;
  }

  let file = p === '/' ? '/index.html' : p;
  const fp = path.join(UI_DIR, file);
  fs.readFile(fp, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not Found'); }
    res.setHeader('Content-Type', MIME[path.extname(fp)] || 'text/plain');
    res.setHeader('Cache-Control', 'no-store'); // 开发预览：禁止缓存，避免改完不生效
    res.writeHead(200);
    res.end(data);
  });
});

async function handleApi(cmd, q, req, res) {
  switch (cmd) {
    case 'get_today_tasks': {
      const arr = mockState.tasks.filter(t => t.track_status !== 'done');
      return ok(res, arr);
    }
    case 'get_archive': {
      const kw = (q.search || '').toLowerCase().trim();
      const arr = kw
        ? mockState.archive.filter(t => t.title.toLowerCase().includes(kw))
        : mockState.archive;
      return ok(res, [...arr].sort((a, b) => (b.updated_at||0) - (a.updated_at||0)));
    }
    case 'get_total_points': return ok(res, { points: mockState.totalPoints });
    case 'get_level': {
      const lv = levelOf(mockState.totalPoints);
      return ok(res, { ...lv, points: mockState.totalPoints, style: mockState.settings.progress_style || 'bar' });
    }
    case 'get_daily_progress': return ok(res, computeDailyProgress());
    case 'get_habits_status': return ok(res, []);
    case 'get_next_reminder': return ok(res, { title:'交通信原理作业', remaining_ms: 5_400_000 });

    case 'get_task_detail': {
      const t = findTask(q.taskUuid);
      const steps = t ? (mockStepState[t.uuid] || []) : [];
      return ok(res, { task: t, steps });
    }

    case 'start_tracking': {
      const t = findTask(q.taskUuid);
      if (!t) return ok(res, { status:'not_found' });
      const max = parseInt(mockState.settings.tracking_max || '3');
      const cur = mockState.tasks.filter(x => x.track_status === 'tracking').length;
      if (cur >= max) return ok(res, { status:'error', msg:'已达追踪上限（' + max + '）' });
      t.track_status = 'tracking'; t.updated_at = nowMs();
      return ok(res, { status:'ok' });
    }
    case 'stop_tracking': {
      const t = findTask(q.taskUuid);
      if (t) { t.track_status = 'pending'; t.updated_at = nowMs(); }
      return ok(res, { status:'ok' });
    }
    case 'complete_task': {
      const t = findTask(q.taskUuid);
      if (!t) return ok(res, { status:'not_found' });
      // 次数任务：done_count++；满额才移入仓库
      if (t.category === 'once' && t.count > 1) {
        t.done_count = (t.done_count || 0) + 1;
        t.updated_at = nowMs();
        if (t.done_count >= t.count) {
          t.track_status = 'done'; t.done = 1; t.done_at = nowMs();
          mockState.totalPoints += t.reward_points || 10;
          mockState.tasks = mockState.tasks.filter(x => x.uuid !== t.uuid);
          mockState.archive.unshift(t);
        }
        return ok(res, { status:'ok', partial: t.done_count < t.count });
      }
      t.track_status = 'done'; t.done = 1; t.done_at = nowMs(); t.updated_at = nowMs();
      mockState.totalPoints += t.reward_points || 10;
      mockState.tasks = mockState.tasks.filter(x => x.uuid !== t.uuid);
      mockState.archive.unshift(t);
      return ok(res, { status:'ok' });
    }
    case 'delete_task': {
      mockState.tasks = mockState.tasks.filter(x => x.uuid !== q.taskUuid);
      mockState.archive = mockState.archive.filter(x => x.uuid !== q.taskUuid);
      return ok(res, { status:'ok' });
    }
    case 'add_task': {
      const title = q.title || '';
      if (!title) return ok(res, { status:'empty' });
      const cat = q.category || 'once';
      const typ = catToType(cat);
      const ddlKey = q.deadlineKey || 'none';
      const deadline = DEADLINE_MS[ddlKey] !== undefined && DEADLINE_MS[ddlKey] !== null
        ? nowMs() + DEADLINE_MS[ddlKey] : null;
      const uuid = 't-' + Math.random().toString(36).slice(2, 8);
      const count = parseInt(q.count || '1') || 1;
      const t = newTask(uuid, title, {
        type: typ, cat: cat, category: cat, priority: q.priority || 'medium',
        deadline: deadline, count: count, done_count: 0
      });
      mockState.tasks.push(t);
      return ok(res, { status:'ok', uuid });
    }

    case 'advance_step': {
      const taskUuid = q.taskUuid;
      const stepUuid = q.stepUuid;
      const status = q.status || 'done';
      const steps = mockStepState[taskUuid] || [];
      const s = steps.find(x => x.uuid === stepUuid);
      if (s) { s.status = status; s.updated_at = nowMs(); s.done_at = status === 'done' ? nowMs() : null; }
      return ok(res, { status:'ok' });
    }
    case 'add_step': {
      const taskUuid = q.taskUuid;
      const title = q.title || '新步骤';
      const steps = mockStepState[taskUuid] || (mockStepState[taskUuid] = []);
      const stepUuid = 's-' + Math.random().toString(36).slice(2, 6);
      steps.push({ uuid: stepUuid, title, status: 'todo', attr_label: '', attr_value: '', sort_order: steps.length, done_at: null, created_at: nowMs(), updated_at: nowMs(), deleted: 0 });
      return ok(res, { status:'ok', uuid: stepUuid });
    }

    case 'ai_breakdown': {
      const title = q.title || '';
      // 有 API key → 转发 DeepSeek；没有 → 本地模板兜底（0 成本）
      const key = mockState.settings.ai_api_key || '';
      let steps = [];
      if (key) {
        try {
          const r = await fetch('https://api.deepseek.com/chat/completions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key },
            body: JSON.stringify({
              model: 'deepseek-chat',
              messages: [
                { role: 'system', content: '你是任务拆解助手。把用户的任务拆成 3-6 个具体可执行的小步骤，每步 5-15 个字，直接输出步骤列表，不要序号和解释。' },
                { role: 'user', content: title }
              ],
              temperature: 0.3,
              max_tokens: 300
            })
          });
          const data = await r.json();
          const text = (data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content) || '';
          steps = text.split(/\n+/).map(s => s.replace(/^\d+[.、)\s]+/, '').trim()).filter(s => s.length >= 2 && s.length <= 30).slice(0, 6);
        } catch (e) { /* 云端失败 → 回落本地模板 */ }
      }
      if (steps.length === 0) steps = localBreakdown(title);
      return ok(res, { steps });
    }

    case 'set_setting': {
      mockState.settings[q.key] = q.value;
      return ok(res, { status:'ok' });
    }
    case 'get_setting': return ok(res, mockState.settings[q.key] || null);
    case 'connect_server': return ok(res, '已连接（预览模式 mock）');
    case 'disconnect_server': return ok(res, { status:'ok' });
    case 'save_pairing': {
      mockState.pairings[q.deviceId || 'default'] = q.url;
      return ok(res, { status:'ok' });
    }
    case 'load_pairing': return ok(res, mockState.pairings['default'] || null);
    case 'set_display_mode': return ok(res, { status:'ok' });
    case 'set_window_size': return ok(res, { status:'ok' });

    default: res.writeHead(404); return res.end('{"error":"unknown"}');
  }
}

function ok(res, obj) { res.writeHead(200); res.end(JSON.stringify(obj)); }

// 本地模板拆解（未配置 API key 时的 0 成本兜底）
function localBreakdown(title) {
  const t = title || '';
  const has = (kw) => t.includes(kw);
  if (has('看') || has('读') || has('书') || has('背')) {
    return ['通读核心内容', '划重点记笔记', '做一遍自测题', '总结复盘'];
  }
  if (has('复习') || has('学') || has('练')) {
    return ['整理知识点框架', '重点章节精读', '做配套练习题', '错题回顾总结'];
  }
  if (has('写') || has('交') || has('报告') || has('作业') || has('论文')) {
    return ['收集所需资料', '列出大纲初稿', '完成正文内容', '检查格式并提交'];
  }
  if (has('买') || has('购') || has('快递') || has('取')) {
    return ['列清单确认需求', '比价下单', '确认收货'];
  }
  if (has('锻炼') || has('运动') || has('跑') || has('健身')) {
    return ['热身 5 分钟', '完成主体训练', '拉伸放松 5 分钟'];
  }
  return ['明确目标范围', '列出执行步骤', '逐项推进完成'];
}

server.listen(PORT, () => {
  console.log('════════════════════════════════════════');
  console.log('  任务栏 桌面端预览服务器 v4 已启动');
  console.log('  浏览器打开: http://localhost:' + PORT);
  console.log('  暖色浅色 · 等级 · 进度条 · 步骤推进');
  console.log('  按 Ctrl+C 停止');
  console.log('════════════════════════════════════════');
});
