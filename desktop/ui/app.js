// 任务指南 桌面端前端逻辑 v2
// 双模式：追踪条模式（图2，默认） ⇄ 详情面板模式（图1，点击展开）
const isTauri = !!(window.__TAURI__ && window.__TAURI__.core);

async function call(cmd, args) {
  if (isTauri) {
    return window.__TAURI__.core.invoke(cmd, args || {});
  }
  const qs = args ? '?' + new URLSearchParams(args).toString() : '';
  const r = await fetch('/api/' + cmd + qs);
  if (!r.ok) throw new Error('API ' + cmd + ' 失败');
  return r.json();
}

// ===== 状态 =====
let nav = 'tracking';          // tracking | today | habits（左导航）
let selectedUuid = null;       // 详情面板选中的任务
let currentTrackUuid = null;   // 当前追踪目标

// ===== 工具 =====
function fmtRemaining(ms) {
  if (ms <= 0) return '已到点';
  const min = Math.floor(ms / 60000);
  if (min < 60) return min + ' 分钟后';
  const hr = Math.floor(min / 60);
  if (hr < 24) return hr + ' 小时' + (min % 60) + ' 分后';
  return Math.floor(hr / 24) + ' 天后';
}
function fmtDue(dueAt) {
  if (dueAt == null) return '';
  if (typeof dueAt === 'number') {
    const d = new Date(dueAt), now = new Date();
    const hm = String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
    return (d.toDateString() === now.toDateString() ? '今日 ' : (d.getMonth()+1)+'月'+d.getDate()+'日 ') + hm;
  }
  return String(dueAt);
}
const TYPE_LABEL = { goal:'目标', habit:'习惯', repeat:'重复', note:'速记', once:'单次' };
const PRIO_LABEL = { high:'高优先级', medium:'中优先级', low:'低优先级' };
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ===== 渲染主流程 =====
async function render() {
  try {
    const [cards, tasks, progress, reminder, habits, points] = await Promise.all([
      call('get_track_cards'),
      call('get_today_tasks'),
      call('get_progress'),
      call('get_next_reminder'),
      call('get_habits_status'),
      call('get_total_points')
    ]);

    // 积分（顶栏）
    const pts = typeof points === 'number' ? points : (points && points.points) || 0;
    document.getElementById('totalPoints').textContent = '◆ ' + pts;

    // 提醒横幅
    const rb = document.getElementById('reminderBanner');
    if (reminder) {
      rb.style.display = '';
      rb.innerHTML = `<span class="b-title">📢 ${esc(reminder.title)}</span><span class="b-time">${fmtRemaining(reminder.remaining_ms)}</span>`;
    } else rb.style.display = 'none';

    // 追踪条模式 + 详情模式
    renderTrackMode(cards, progress);
    renderFullMode(cards, tasks, habits);
  } catch (e) {
    console.error('render 失败', e);
  }
}

// ===== 追踪条模式（图2） =====
function renderTrackMode(cards, progress) {
  const tl = document.getElementById('miniTrackList');
  document.getElementById('trackCount').textContent = cards.length;
  if (!cards || cards.length === 0) {
    tl.innerHTML = '<div class="empty">暂无追踪任务<br><span style="font-size:10px">点击右上角 ◤ 展开面板选择任务</span></div>';
  } else {
    // 当前追踪目标优先展示
    const ordered = currentTrackUuid
      ? [...cards.filter(c => c.task.uuid === currentTrackUuid), ...cards.filter(c => c.task.uuid !== currentTrackUuid)]
      : cards;
    tl.innerHTML = ordered.map(c => {
      const step = c.current_step;
      const stepLine = step ? step.title : '（未拆解步骤）';
      const attr = step && step.attr_value ? `${step.attr_label || '目标'}: ${step.attr_value}` : '';
      const pct = c.total_steps > 0 ? Math.round(c.done_steps / c.total_steps * 100) : 0;
      return `<div class="track-card" onclick="openFullWith('${c.task.uuid}')" title="点击展开详情">
        <div class="tc-icon"></div>
        <div class="tc-texts">
          <div class="tc-title">${esc(c.task.title)}</div>
          <div class="tc-step">▸ ${esc(stepLine)}${attr ? ' · ' + esc(attr) : ''}</div>
        </div>
        <div class="tc-progress"><div style="width:${pct}%"></div></div>
      </div>`;
    }).join('');
  }
  const p = progress || { done_today: 0, total_today: 0 };
  const pct = p.total_today > 0 ? Math.round(p.done_today / p.total_today * 100) : 0;
  document.getElementById('progressFill').style.width = pct + '%';
  document.getElementById('progressText').textContent = p.done_today + '/' + p.total_today;
}

// ===== 详情面板模式（图1） =====
function renderFullMode(cards, tasks, habits) {
  renderSideNav();
  renderFullList(cards, tasks, habits);
  renderDetail();
}

function renderSideNav() {
  document.querySelectorAll('.nav-item').forEach(n => {
    n.classList.toggle('active', n.dataset.nav === nav);
  });
}

function renderFullList(cards, tasks, habits) {
  const box = document.getElementById('fullTaskList');
  if (nav === 'tracking') {
    if (!cards || cards.length === 0) { box.innerHTML = '<div class="empty">暂无追踪任务</div>'; return; }
    box.innerHTML = `<div class="task-group-title">进行中</div>` + cards.map(t => taskItemHtml({
      uuid: t.task.uuid, title: t.task.title, sub: t.current_step ? t.current_step.title : '未拆解',
      cls: 'tracking'
    })).join('');
  } else if (nav === 'today') {
    const list = tasks || [];
    if (list.length === 0) { box.innerHTML = '<div class="empty">暂无任务</div>'; return; }
    const tracking = list.filter(t => t.track_status === 'tracking');
    const pending = list.filter(t => t.track_status !== 'tracking');
    let html = '';
    if (tracking.length) html += `<div class="task-group-title">追踪中</div>` + tracking.map(t => taskItemHtml({
      uuid: t.uuid, title: t.title, sub: fmtDue(t.due_at) || t.category || '', cls: 'tracking'
    })).join('');
    if (pending.length) html += `<div class="task-group-title">待办</div>` + pending.map(t => taskItemHtml({
      uuid: t.uuid, title: t.title, sub: (fmtDue(t.due_at) ? '⏰ ' + fmtDue(t.due_at) : '') + (t.priority === 'high' ? ' · 高优' : ''),
      cls: 'pending'
    })).join('');
    box.innerHTML = html;
  } else {
    if (!habits || habits.length === 0) { box.innerHTML = '<div class="empty">无习惯任务</div>'; return; }
    box.innerHTML = `<div class="task-group-title">习惯</div>` + habits.map(h => taskItemHtml({
      uuid: h.uuid, title: h.title, sub: h.checked ? '✓ 已打卡' : '○ 未打卡',
      cls: 'pending'
    })).join('');
  }
}

function taskItemHtml({ uuid, title, sub, cls }) {
  return `<div class="task-item ${selectedUuid === uuid ? 'selected' : ''}" data-uuid="${uuid}" onclick="selectTask('${uuid}')">
    <div class="ti-diamond ${cls}"></div>
    <div class="ti-texts"><div class="ti-title">${esc(title)}</div><div class="ti-sub">${esc(sub)}</div></div>
  </div>`;
}

// 详情渲染
async function renderDetail() {
  const pane = document.getElementById('detailPane');
  if (!selectedUuid) {
    pane.innerHTML = '<div class="detail-empty">选择左侧任务查看详情</div>';
    return;
  }
  try {
    const detail = await call('get_task_detail', { taskUuid: selectedUuid });
    if (!detail || !detail.task) { pane.innerHTML = '<div class="detail-empty">任务不存在</div>'; return; }
    const t = detail.task;
    const steps = detail.steps || [];
    const prioCls = 'prio-' + (t.priority || 'm').charAt(0).toLowerCase();
    const isTracking = t.track_status === 'tracking';

    pane.innerHTML = `
      <div class="detail-title">${esc(t.title)}</div>
      <div class="detail-loc">${esc(t.category || '未分类')}${t.due_at ? ' · ' + esc(fmtDue(t.due_at)) : ''}${t.deadline ? ' · 截止 ' + esc(fmtDue(t.deadline)) : ''}</div>
      <div class="chip-row">
        <span class="chip type">${TYPE_LABEL[t.type] || t.type}</span>
        <span class="chip ${prioCls}">${PRIO_LABEL[t.priority] || ''}</span>
        ${isTracking ? '<span class="chip type">◆ 追踪中</span>' : ''}
      </div>
      <div class="goal-box">
        <div class="goal-head">任务目标 · 步骤 ${steps.filter(s => s.status === 'done').length}/${steps.length}</div>
        ${steps.length === 0 ? '<div class="goal-item"><span class="g-play">▶</span><span class="g-text">（尚未拆解步骤）</span></div>' :
          steps.map(s => `
            <div class="goal-item ${s.status === 'done' ? 'done' : ''}">
              <span class="g-play">${s.status === 'done' ? '✓' : '▶'}</span>
              <span class="g-text">${esc(s.title)}</span>
              ${s.attr_value ? `<span class="g-attr">${esc(s.attr_label || '')}: ${esc(s.attr_value)}</span>` : ''}
            </div>`).join('')}
      </div>
      ${t.desc ? `<div class="detail-desc">${esc(t.desc)}</div>` : ''}
      <div class="divider">◆</div>
      <div class="reward-title">完成任务可获得</div>
      <div class="reward-row">
        <div class="reward-item hl"><span class="reward-ico">💠</span><span class="reward-num">+${t.reward_points || 10}</span></div>
        <div class="reward-item"><span class="reward-ico">📘</span><span class="reward-num">进度</span></div>
        <div class="reward-item"><span class="reward-ico">🏆</span><span class="reward-num">坚持</span></div>
      </div>
      <button class="track-btn ${isTracking ? 'already' : ''}" onclick="trackTarget('${t.uuid}')">
        ${isTracking ? '当前追踪中 · 收起' : '追踪目标'}
      </button>`;
  } catch (e) {
    pane.innerHTML = '<div class="detail-empty">加载详情失败</div>';
  }
}

// ===== 交互 =====
window.selectTask = function(uuid) {
  selectedUuid = uuid;
  document.querySelectorAll('.task-item').forEach(i => i.classList.toggle('selected', i.dataset.uuid === uuid));
  renderDetail();
};

// 追踪目标：确保任务进入追踪 → 收起回追踪条模式
window.trackTarget = async function(uuid) {
  const detail = await call('get_task_detail', { taskUuid: uuid });
  const isTracking = detail && detail.task && detail.task.track_status === 'tracking';
  if (!isTracking) {
    await call('start_tracking', { taskUuid: uuid });
  }
  currentTrackUuid = uuid;
  closeFull();
  render();
};

// 打开详情面板并选中任务
window.openFullWith = function(uuid) {
  selectedUuid = uuid;
  openFull();
  render();
};

function openFull() {
  document.getElementById('trackMode').style.display = 'none';
  document.getElementById('fullMode').style.display = '';
  document.getElementById('app').classList.add('expanded');
  if (isTauri) call('set_window_size', { w: 660, h: 540 });
}
function closeFull() {
  document.getElementById('fullMode').style.display = 'none';
  document.getElementById('trackMode').style.display = '';
  document.getElementById('app').classList.remove('expanded');
  if (isTauri) call('set_window_size', { w: 300, h: 560 });
}

// 左导航切换
document.querySelectorAll('.nav-item').forEach(n => {
  n.addEventListener('click', () => {
    nav = n.dataset.nav;
    selectedUuid = null;
    render();
  });
});
document.getElementById('expandBtn').addEventListener('click', openFull);

// 模式切换（置顶/普通/托盘）
document.querySelectorAll('.mode-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const mode = btn.dataset.mode;
    document.querySelectorAll('.mode-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    call('set_display_mode', { mode });
    localStorage.setItem('mode', mode);
  });
});
const lastMode = localStorage.getItem('mode') || 'top';
const lastBtn = document.querySelector(`.mode-btn[data-mode="${lastMode}"]`);
if (lastBtn) lastBtn.classList.add('active');

// 连接服务器
document.getElementById('connectBtn').addEventListener('click', async () => {
  const url = document.getElementById('serverUrl').value.trim();
  if (!url) return;
  const full = url.startsWith('http') ? url : 'http://' + url;
  try {
    const msg = await call('connect_server', { url: full });
    document.getElementById('connectBar').insertAdjacentHTML('beforebegin',
      `<div style="color:var(--jade);font-size:11px;padding:4px;">${esc(msg)}</div>`);
    setTimeout(render, 500);
  } catch (e) { alert('连接失败: ' + e.message); }
});

// 快捷添加
document.getElementById('quickAddBtn').addEventListener('click', async () => {
  const input = document.getElementById('quickInput');
  const title = input.value.trim();
  if (!title) return;
  await call('add_task', { title });
  input.value = '';
  render();
});
document.getElementById('quickInput').addEventListener('keydown', e => {
  if (e.key === 'Enter') document.getElementById('quickAddBtn').click();
});

render();
setInterval(render, 10000);
