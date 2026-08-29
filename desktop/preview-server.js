// 任务栏 桌面端 Node 预览服务器 v3
// 用途：没装 Rust/Tauri 时，立刻看到完整挂件 + 总览 + 仓库 + 新建表单 + 设置
// 运行：node preview-server.js  → 浏览器打开 http://localhost:3000
const http = require('http');
const fs = require('fs');
const path = require('path');

const UI_DIR = path.join(__dirname, 'ui');
const PORT = 3000;

// ====== Mock 数据 ======
function nowMs() { return Date.now(); }

const newTask = (uuid, title, opts = {}) => ({
  uuid, title,
  track_status: opts.track_status || 'pending',
  type: opts.type || 'once',
  desc: opts.desc || '',
  category: opts.category || opts.cat || 'once',
  priority: opts.priority || 'medium',
  due_at: opts.due_at ?? null,
  deadline: opts.deadline ?? null,
  reward_points: opts.reward_points ?? 10,
  delayed_count: 0, done: 0, done_at: null,
  created_at: opts.created_at ?? nowMs(),
  updated_at: opts.updated_at ?? nowMs(),
  deleted: 0
});

const catByName = { 'daily':'每日任务','goal':'目标任务','time-limited':'限时任务','once':'小任务' };

const DEADLINE_MS = {
  none: null, '60min': 3600_000, '6h': 6*3600_000, '1d': 86400_000,
  '3d': 3*86400_000, '7d': 7*86400_000, '30d': 30*86400_000
};
function catToType(cat) {
  return { 'daily':'habit','goal':'goal','time-limited':'once','once':'once' }[cat] || 'once';
}

const now = nowMs();
const mockSteps = {
  t1: [
    { uuid:'s1', title:'通读第1章 引言', status:'done', attr_label:'进度', attr_value:'1/12' },
    { uuid:'s2', title:'做第1章课后习题', status:'doing', attr_label:'待做', attr_value:'8题' },
    { uuid:'s3', title:'通读第2章', status:'todo', attr_label:'', attr_value:'' }
  ],
  t2: [
    { uuid:'s4', title:'复习FFT算法', status:'doing', attr_label:'进度', attr_value:'60%' },
    { uuid:'s5', title:'复习Z变换', status:'todo', attr_label:'', attr_value:'' }
  ],
  tDaily: [
    { uuid:'sd1', title:'30 分钟慢跑', status:'todo', attr_label:'', attr_value:'' },
    { uuid:'sd2', title:'拉伸 5 分钟', status:'todo', attr_label:'', attr_value:'' }
  ]
};

let mockState = {
  totalPoints: 120,
  // 未完成（任务栏显示）
  tasks: [
    // 追踪中（2 个）
    newTask('t1', '一个月看完《深入理解计算机系统》', {
      track_status:'tracking', type:'goal', cat:'goal', priority:'high',
      desc:'每天看一点，12章一个月啃完。', category:'goal',
      created_at: now - 30*86400_000, updated_at: now - 3600_000
    }),
    newTask('t2', '复习数字信号处理', {
      track_status:'tracking', type:'goal', cat:'goal', priority:'high',
      desc:'期末复习，重点FFT和Z变换。', category:'goal',
      created_at: now - 7*86400_000, updated_at: now - 1800_000
    }),
    // 待办 - 4 类（覆盖每日 / 目标 / 限时 / 单次）
    newTask('tDaily', '每日锻炼', {
      type:'habit', cat:'daily', priority:'medium', category:'daily',
      deadline: now + 24*3600_000, desc:'每天 30 分钟慢跑', updated_at: now - 7200_000
    }),
    newTask('tSnack', '买辣条', {
      type:'once', cat:'once', priority:'low', category:'once',
      deadline: DEADLINE_MS['6h'] ? now + DEADLINE_MS['6h'] : null,
      updated_at: now - 7200_000
    }),
    newTask('t3', '交数字信号处理作业', {
      type:'once', cat:'time-limited', priority:'high',
      due_at: now + 6*3600_000, deadline: now + 6*3600_000,
      desc:'第三章课后作业，拍照上传。', category:'time-limited'
    }),
    newTask('t4', '买根数据线', {
      type:'note', cat:'once', priority:'low', category:'once'
    }),
    newTask('t5', '给导师发周报', {
      type:'once', cat:'time-limited', priority:'medium',
      due_at: now + 12*3600_000, deadline: now + 14*3600_000, category:'time-limited'
    })
  ],
  // 已完成（仓库）
  archive: [
    newTask('tdone1', '看完操作系统导论第二版', { track_status:'done', cat:'goal', category:'goal', done_at: now - 86400_000, updated_at: now - 86400_000 }),
    newTask('tdone2', '六级真题一套', { track_status:'done', cat:'once', category:'once', done_at: now - 2*86400_000, updated_at: now - 2*86400_000 }),
    newTask('tdone3', '整理桌面垃圾文件', { track_status:'done', cat:'once', category:'once', done_at: now - 3*86400_000, updated_at: now - 3*86400_000 }),
    newTask('tdone4', '复习线性代数第3章', { track_status:'done', cat:'goal', category:'goal', done_at: now - 5*86400_000, updated_at: now - 5*86400_000 })
  ],
  // 设置持久化（mock）
  settings: {},
  pairings: {} // {deviceId: url}
};

const MIME = { '.html':'text/html', '.css':'text/css', '.js':'application/javascript', '.json':'application/json' };

function findTask(uuid) {
  return mockState.tasks.find(t => t.uuid === uuid)
      || mockState.archive.find(t => t.uuid === uuid)
      || null;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;
  const q = Object.fromEntries(url.searchParams);

  // ====== API ======
  if (p.startsWith('/api/')) {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const cmd = p.slice(5);
    try { await handleApi(cmd, q, req, res); } catch (e) { res.writeHead(500); res.end('{"error":"' + e.message + '"}'); }
    return;
  }

  // ====== 静态 ======
  let file = p === '/' ? '/index.html' : p;
  const fp = path.join(UI_DIR, file);
  fs.readFile(fp, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not Found'); }
    res.setHeader('Content-Type', MIME[path.extname(fp)] || 'text/plain');
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
    case 'get_progress': return ok(res, { done_today: 2, total_today: 5 });
    case 'get_next_reminder': return ok(res, { title:'交通信原理作业', remaining_ms: 5_400_000 });
    case 'get_habits_status': return ok(res, [
      { uuid:'tDaily', title:'每日锻炼', checked: false },
      { uuid:'h1', title:'背50个单词', checked: false }
    ]);
    case 'get_total_points': return ok(res, { points: mockState.totalPoints });

    case 'get_task_detail': {
      const t = findTask(q.taskUuid);
      return ok(res, { task: t, steps: t ? (mockSteps[t.uuid] || []) : [] });
    }

    case 'start_tracking': {
      const t = findTask(q.taskUuid);
      if (!t) return ok(res, { status:'not_found' });
      const max = parseInt(mockState.settings.tracking_max || '3');
      const cur = mockState.tasks.filter(x => x.track_status === 'tracking').length;
      if (cur >= max) return ok(res, { status:'error', msg:`已达追踪上限（${max}）` });
      t.track_status = 'tracking';
      t.updated_at = nowMs();
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
      t.track_status = 'done'; t.done = 1; t.done_at = nowMs(); t.updated_at = nowMs();
      mockState.totalPoints += t.reward_points || 10;
      // 从任务栏移到仓库
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
      const t = newTask(uuid, title, {
        type: typ, cat: cat, category: cat, priority: q.priority || 'medium',
        deadline: deadline
      });
      mockState.tasks.push(t);
      return ok(res, { status:'ok', uuid });
    }

    case 'advance_step': return ok(res, { status:'ok' });
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

server.listen(PORT, () => {
  console.log('════════════════════════════════════════');
  console.log('  任务栏 桌面端预览服务器 v3 已启动');
  console.log('  浏览器打开: http://localhost:' + PORT);
  console.log('  (预览模式，使用 mock 数据)');
  console.log('  按 Ctrl+C 停止');
  console.log('════════════════════════════════════════');
});
