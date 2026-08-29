// 任务指南 桌面端 Node 预览服务器
// 用途：没装 Rust/Tauri 时，也能立刻看到挂件 UI 效果
// 运行：node preview-server.js  → 浏览器打开 http://localhost:3000
const http = require('http');
const fs = require('fs');
const path = require('path');

const UI_DIR = path.join(__dirname, 'ui');
const PORT = 3000;

// ===== 内存 mock 数据（模拟手机端同步过来的数据） =====
const baseTask = (u, title, extra = {}) => ({
  uuid: u, title, track_status: 'pending', type: 'once', desc: '',
  category: '', priority: 'medium', due_at: null, deadline: null,
  reward_points: 10, ...extra
});

const mockSteps = {
  t1: [
    { uuid: 's1', title: '通读第1章 引言', status: 'done', attr_label: '进度', attr_value: '1/12' },
    { uuid: 's2', title: '做第1章课后习题', status: 'doing', attr_label: '待做', attr_value: '8题' },
    { uuid: 's3', title: '通读第2章 信息的表示', status: 'todo', attr_label: '', attr_value: '' }
  ],
  t2: [
    { uuid: 's4', title: '复习FFT算法', status: 'doing', attr_label: '进度', attr_value: '60%' },
    { uuid: 's5', title: '复习Z变换', status: 'todo', attr_label: '', attr_value: '' }
  ],
  t3: [
    { uuid: 's6', title: '完成第一题组', status: 'todo', attr_label: '', attr_value: '' },
    { uuid: 's7', title: '完成第二题组', status: 'todo', attr_label: '', attr_value: '' }
  ]
};

let mockState = {
  totalPoints: 120,
  trackCards: [
    {
      task: baseTask('t1', '一个月看完《深入理解计算机系统》', { track_status: 'tracking', type: 'goal', category: '学习', priority: 'high', desc: '每天看一点，12章一个月啃完。' }),
      current_step: { uuid: 's2', title: '做第1章课后习题', attr_label: '待做', attr_value: '8题' },
      done_steps: 1, total_steps: 3
    },
    {
      task: baseTask('t2', '复习数字信号处理', { track_status: 'tracking', type: 'goal', category: '学习', priority: 'high', desc: '期末复习，重点FFT和Z变换。' }),
      current_step: { uuid: 's4', title: '复习FFT算法', attr_label: '进度', attr_value: '60%' },
      done_steps: 0, total_steps: 2
    }
  ],
  progress: { done_today: 2, total_today: 5 },
  todayTasks: [
    baseTask('t1', '一个月看完《深入理解计算机系统》', { track_status: 'tracking', type: 'goal', category: '学习', priority: 'high', desc: '每天看一点，12章一个月啃完。' }),
    baseTask('t3', '交数字信号处理作业', { type: 'once', category: '学习', priority: 'high', due_at: '今日 15:00', desc: '第三章课后作业，拍照上传。' }),
    baseTask('t4', '买根数据线', { type: 'note', category: '生活', priority: 'low' }),
    baseTask('t5', '给导师发周报', { type: 'once', category: '学习', priority: 'medium', due_at: '今日 20:00' })
  ],
  reminder: { title: '交通信原理作业', due_at: 0, remaining_ms: 5_400_000 },
  habits: [
    { uuid: 'h1', title: '每天跑步30分钟', checked: true },
    { uuid: 'h2', title: '背50个单词', checked: false }
  ]
};

const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript', '.json': 'application/json' };

function findTask(uuid) {
  return mockState.todayTasks.find(t => t.uuid === uuid)
    || mockState.trackCards.find(c => c.task.uuid === uuid)?.task
    || null;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;

  // ===== API =====
  if (p.startsWith('/api/')) {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    const cmd = p.slice(5);
    const q = Object.fromEntries(url.searchParams);

    switch (cmd) {
      case 'get_track_cards': return ok(res, mockState.trackCards);
      case 'get_today_tasks': return ok(res, mockState.todayTasks);
      case 'get_progress': return ok(res, mockState.progress);
      case 'get_next_reminder': return ok(res, mockState.reminder);
      case 'get_habits_status': return ok(res, mockState.habits);
      case 'get_total_points': return ok(res, mockState.totalPoints);
      case 'get_task_detail': {
        const task = findTask(q.taskUuid);
        return ok(res, { task, steps: (task && mockSteps[task.uuid]) || [] });
      }
      case 'start_tracking': {
        const task = findTask(q.taskUuid);
        if (task && !mockState.trackCards.find(c => c.task.uuid === task.uuid)) {
          const steps = mockSteps[task.uuid] || [];
          mockState.trackCards.push({
            task: { ...task, track_status: 'tracking' },
            current_step: steps.find(s => s.status === 'doing') || steps[0] || null,
            done_steps: (steps || []).filter(s => s.status === 'done').length,
            total_steps: (steps || []).length
          });
          mockState.todayTasks = mockState.todayTasks.map(t =>
            t.uuid === task.uuid ? { ...t, track_status: 'tracking' } : t);
        }
        return ok(res, { status: 'ok' });
      }
      case 'connect_server': return ok(res, '已连接（预览模式，使用 mock 数据）');
      case 'set_display_mode': return ok(res, { status: 'ok', note: '预览模式下显示模式仅作演示' });
      case 'advance_step': {
        const su = q.stepUuid;
        mockState.trackCards.forEach(c => {
          if (c.current_step && c.current_step.uuid === su) {
            c.done_steps++; c.current_step = null;
            if (c.done_steps >= c.total_steps) c.task.track_status = 'done';
          }
        });
        mockState.trackCards = mockState.trackCards.filter(c => c.task.track_status === 'tracking');
        return ok(res, { status: 'ok' });
      }
      case 'complete_task': {
        const tu = q.taskUuid;
        const done = mockState.trackCards.find(c => c.task.uuid === tu);
        mockState.trackCards = mockState.trackCards.filter(c => c.task.uuid !== tu);
        if (done) { mockState.progress.done_today++; mockState.totalPoints += done.task.reward_points || 10; }
        return ok(res, { status: 'ok' });
      }
      case 'add_task': {
        const title = q.title || '';
        if (title) { mockState.progress.total_today++; mockState.todayTasks.push(baseTask('t-new', title)); }
        return ok(res, { status: 'ok' });
      }
      default: res.writeHead(404); return res.end('{}');
    }
  }

  // ===== 静态文件 =====
  let file = p === '/' ? '/index.html' : p;
  const fp = path.join(UI_DIR, file);
  fs.readFile(fp, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not Found'); }
    res.setHeader('Content-Type', MIME[path.extname(fp)] || 'text/plain');
    res.writeHead(200);
    res.end(data);
  });
});

function ok(res, obj) { res.writeHead(200); res.end(JSON.stringify(obj)); }

server.listen(PORT, () => {
  console.log('════════════════════════════════════════');
  console.log('  任务指南 桌面端预览服务器已启动');
  console.log('  浏览器打开: http://localhost:' + PORT);
  console.log('  (这是预览模式，使用 mock 数据演示 UI)');
  console.log('  按 Ctrl+C 停止');
  console.log('════════════════════════════════════════');
});
