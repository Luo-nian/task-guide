// 任务指南 桌面端 Node 预览服务器
// 用途：没装 Rust/Tauri 时，也能立刻看到挂件 UI 效果
// 运行：node preview-server.js  → 浏览器打开 http://localhost:3000
const http = require('http');
const fs = require('fs');
const path = require('path');

const UI_DIR = path.join(__dirname, 'ui');
const PORT = 3000;

// ===== 内存 mock 数据（模拟手机端同步过来的数据） =====
let mockState = {
  trackCards: [
    {
      task: { uuid: 't1', title: '一个月看完《深入理解计算机系统》', track_status: 'tracking' },
      current_step: { uuid: 's1', title: '做第1章课后习题', attr_label: '待做', attr_value: '8题' },
      done_steps: 1, total_steps: 3
    },
    {
      task: { uuid: 't2', title: '复习数字信号处理', track_status: 'tracking' },
      current_step: { uuid: 's2', title: '复习FFT算法', attr_label: '进度', attr_value: '60%' },
      done_steps: 2, total_steps: 5
    }
  ],
  progress: { done_today: 2, total_today: 5 },
  todayTasks: [
    { uuid: 't1', title: '一个月看完《深入理解计算机系统》', track_status: 'tracking', due_at: null, category: '学习', priority: 'high' },
    { uuid: 't3', title: '交数字信号处理作业', track_status: 'pending', due_at: '今日 15:00', category: '学习', priority: 'high' },
    { uuid: 't4', title: '买根数据线', track_status: 'pending', due_at: null, category: '生活', priority: 'low' },
    { uuid: 't5', title: '给导师发周报', track_status: 'pending', due_at: '今日 20:00', category: '学习', priority: 'medium' }
  ],
  reminder: { title: '交通信原理作业', due_at: 0, remaining_ms: 5_400_000 },
  habits: [
    { uuid: 'h1', title: '每天跑步30分钟', checked: true },
    { uuid: 'h2', title: '背50个单词', checked: false }
  ]
};

const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript', '.json': 'application/json' };

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
        mockState.trackCards = mockState.trackCards.filter(c => c.task.uuid !== tu);
        mockState.progress.done_today++;
        return ok(res, { status: 'ok' });
      }
      case 'add_task': {
        const title = q.title || '';
        if (title) mockState.progress.total_today++;
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
