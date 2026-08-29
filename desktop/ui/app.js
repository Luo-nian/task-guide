// 任务栏 桌面端前端逻辑 v3
// 默认视图 = 总览（4 分类：每日 / 目标 / 限时 / 单次）
// 侧边栏 = 分类 + 仓库；FAB = 新建；齿轮 = 设置
const isTauri = !!(window.__TAURI__ && window.__TAURI__.core);

async function call(cmd, args) {
  if (isTauri) {
    return window.__TAURI__.core.invoke(cmd, args || {});
  }
  const qs = args && Object.keys(args).length
    ? '?' + Object.entries(args).filter(([_, v]) => v != null)
        .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&')
    : '';
  const r = await fetch('/api/' + cmd + qs);
  if (!r.ok) throw new Error('API ' + cmd + ' 失败');
  return r.json();
}

// ================== 状态 ==================
let nav = 'overview';          // overview | tracking | today | habits | archive
let tasks = [];                // 当前缓存的全部任务（不含已完成）
let archive = [];              // 已完成任务（仓库）
let points = 0;
let settings = {
  tracking_max: 3,
  emergency_on: true,
  eta_short_pct: 30,
  eta_long_h: 36,
  pairing: null               // { url, deviceId } 或 null
};
let selectedUuid = null;
let emergencyQueue = [];       // 队列内多条紧急提醒
let lastEmergencyShown = {};   // {uuid: '2026-08-29T18:00'} 避免重复弹

// =============== 4 分类归类（task_type → 分类） ===============
const CAT_OF_TYPE = {
  habit: 'daily',
  goal: 'goal',
  repeat: 'time-limited',
  note: 'once',
  once: 'once'
};
function catOf(t) {
  if (t.category && ['daily','goal','time-limited','once'].includes(t.category)) return t.category;
  return CAT_OF_TYPE[t.type] || 'once';
}
const CAT_LABEL = { 'daily':'每日任务', 'goal':'目标任务', 'time-limited':'限时任务', 'once':'小任务' };

// =============== 工具 ===============
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function fmtRemaining(ms) {
  if (ms == null || ms <= 0) return '已到点';
  const min = Math.floor(ms / 60000);
  if (min < 60) return min + ' 分后';
  const hr = Math.floor(min / 60);
  if (hr < 24) return hr + ' 小时 ' + (min % 60) + ' 分后';
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
function isTracking(t) { return t.track_status === 'tracking'; }
const TYPE_LABEL = { goal:'目标', habit:'习惯', repeat:'重复', note:'速记', once:'单次' };
const PRIO_LABEL = { high:'高优先级', medium:'中优先级', low:'低优先级' };

// =============== 设置持久化 ===============
function loadSettings() {
  try {
    const raw = localStorage.getItem('taskbar.settings');
    if (raw) settings = { ...settings, ...JSON.parse(raw) };
  } catch(e) {}
  applySettingsToUi();
}
function saveSettings() {
  localStorage.setItem('taskbar.settings', JSON.stringify(settings));
  applySettingsToUi();
}
function applySettingsToUi() {
  document.getElementById('setTrackingMax').value = settings.tracking_max;
  document.getElementById('setEmergencyOn').checked = settings.emergency_on;
  document.getElementById('setEtaShort').value = settings.eta_short_pct;
  document.getElementById('setEtaLong').value = settings.eta_long_h;
  document.getElementById('setPairingStatus').textContent = settings.pairing ? '已配对：' + settings.pairing.url : '未配对';
}

async function persistSettingsServer() {
  // 同步到后端
  try {
    await call('set_setting', { key: 'tracking_max', value: String(settings.tracking_max) });
    await call('set_setting', { key: 'emergency_on', value: settings.emergency_on ? '1' : '0' });
    await call('set_setting', { key: 'eta_short_pct', value: String(settings.eta_short_pct) });
    await call('set_setting', { key: 'eta_long_h', value: String(settings.eta_long_h) });
    if (settings.pairing) {
      await call('save_pairing', { url: settings.pairing.url, deviceId: settings.pairing.deviceId || '' });
    }
  } catch(e) { /* preview mode */ }
}

// =============== 渲染主流程 ===============
async function fetchAll() {
  const [tasksResp, archiveResp, pointsResp] = await Promise.all([
    call('get_today_tasks'),
    call('get_archive', {}),
    call('get_total_points')
  ]);
  tasks = tasksResp || [];
  archive = archiveResp || [];
  points = typeof pointsResp === 'number' ? pointsResp : (pointsResp && pointsResp.points) || 0;
}

async function render() {
  try {
    await fetchAll();
    document.getElementById('totalPoints').textContent = '◆ ' + points;
    renderSideNav();
    if (nav === 'overview') renderOverview();
    else if (nav === 'archive') renderArchive();
    else renderListView();
    checkEmergency();
  } catch (e) {
    console.error('render 失败', e);
  }
}

// =============== 侧栏 ===============
function renderSideNav() {
  document.querySelectorAll('.nav-item').forEach(n => {
    n.classList.toggle('active', n.dataset.nav === nav);
  });
}

document.querySelectorAll('.nav-item').forEach(n => {
  n.addEventListener('click', () => {
    nav = n.dataset.nav;
    selectedUuid = null;
    showSection(nav);
    render();
  });
});

function showSection(section) {
  document.getElementById('overviewView').style.display = section === 'overview' ? '' : 'none';
  const lv = document.getElementById('listView');
  lv.style.display = (section === 'tracking' || section === 'today' || section === 'habits' || section === 'archive') ? '' : 'none';
  document.getElementById('detailView').style.display = 'none';
  document.getElementById('foldedView').style.display = 'none';
  if (section === 'archive') {
    document.getElementById('listViewTitle').textContent = '仓库 · 已完成';
    document.getElementById('listViewHint').textContent = '按时间从新到旧排序';
    document.getElementById('archiveSearch').style.display = '';
    document.getElementById('archiveSearch').oninput = () => render();
  }
}

// =============== 总览页（4 分类） ===============
function renderOverview() {
  showSection('overview');
  const groups = { 'daily': [], 'goal': [], 'time-limited': [], 'once': [] };
  for (const t of tasks) groups[catOf(t)].push(t);

  for (const cat of Object.keys(groups)) {
    const arr = groups[cat];
    document.getElementById('c' + catMapId(cat)).textContent = arr.length;
    const box = document.getElementById('list' + catMapId(cat));
    if (arr.length === 0) {
      box.innerHTML = `<div class="cc-empty">暂无任务</div>`;
    } else {
      box.innerHTML = arr.map(t => catTaskHtml(t)).join('');
    }
  }
}
function catMapId(cat) {
  return { 'daily':'Daily', 'goal':'Goal', 'time-limited':'Lim', 'once':'Once' }[cat];
}
function catTaskHtml(t) {
  return `<div class="cat-task ${isTracking(t)?'tracking':''}" data-uuid="${t.uuid}" onclick="openDetail('${t.uuid}')" oncontextmenu="ctxOpen(event,'${t.uuid}')">
    <span class="ct-diamond"></span>
    <span class="ct-title">${esc(t.title)}</span>
    <span class="ct-meta">${esc(t.due_at ? '⏰ '+fmtDue(t.due_at) : (isTracking(t) ? '◆ 追踪中' : (t.category || TYPE_LABEL[t.type] || '')))}</span>
  </div>`;
}

// =============== 列表视图（追踪中/今日/习惯/仓库） ===============
function renderListView() {
  showSection(nav);
  const box = document.getElementById('listViewBody');
  let arr = [];
  const titleMap = { tracking:'追踪中', today:'今日任务', habits:'习惯', archive:'仓库 · 已完成' };
  document.getElementById('listViewTitle').textContent = titleMap[nav];

  if (nav === 'tracking') {
    arr = tasks.filter(isTracking);
  } else if (nav === 'today') {
    // 四分类全部
    arr = tasks;
  } else if (nav === 'habits') {
    arr = tasks.filter(t => t.type === 'habit');
  } else if (nav === 'archive') {
    const kw = (document.getElementById('archiveSearch').value || '').toLowerCase().trim();
    arr = archive.filter(t => !kw || t.title.toLowerCase().includes(kw));
  }

  if (arr.length === 0) {
    box.innerHTML = `<div class="empty-line">暂无任务</div>`;
  } else {
    box.innerHTML = arr.map(t => `
      <div class="list-task ${isTracking(t)?'tracking':''}" data-uuid="${t.uuid}" onclick="openDetail('${t.uuid}')" oncontextmenu="ctxOpen(event,'${t.uuid}')">
        <span class="ct-diamond"></span>
        <span class="ct-title">${esc(t.title)}</span>
        <span class="ct-meta">${esc(t.due_at ? '⏰ '+fmtDue(t.due_at) : t.category || TYPE_LABEL[t.type] || '')}</span>
      </div>`).join('');
  }
}

function renderArchive() {
  nav = 'archive';
  renderListView();
}

// =============== 详情页 ===============
window.openDetail = async function(uuid) {
  selectedUuid = uuid;
  nav = '__detail__';
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  // 不隐藏 overviewView——保持左侧栏可见，详情面板浮在右侧（左右分栏）
  document.getElementById('listView').style.display = 'none';
  document.getElementById('foldedView').style.display = 'none';
  document.getElementById('detailView').style.display = '';
  document.getElementById('detailView').classList.add('detailing');
  document.getElementById('detailTitle').textContent = '任务详情';
  const body = document.getElementById('detailBody');
  body.innerHTML = `<div style="color:var(--dim);font-size:11px;padding:30px;text-align:center;">加载中…</div>`;

  const d = await call('get_task_detail', { taskUuid: uuid });
  const t = d.task, steps = d.steps || [];
  if (!t) { body.innerHTML = `<div style="color:var(--dim);padding:30px;text-align:center;">任务不存在</div>`; return; }
  const trackingCount = tasks.filter(isTracking).length;
  const canTrack = !isTracking(t) && trackingCount >= settings.tracking_max;

  body.innerHTML = `
    <div class="dv-name">${esc(t.title)}</div>
    <div class="dv-sub">${esc(t.category || '未分类')}${t.due_at ? ' · ' + esc(fmtDue(t.due_at)) : ''}${t.deadline ? ' · 截止 ' + esc(fmtDue(t.deadline)) : ''}</div>
    <div class="chip-row">
      <span class="chip cat-${catOf(t)}">${CAT_LABEL[catOf(t)]}</span>
      <span class="chip type">${TYPE_LABEL[t.type] || t.type}</span>
      <span class="chip prio-${(t.priority||'m').charAt(0)}">${PRIO_LABEL[t.priority]||''}</span>
      ${isTracking(t) ? '<span class="chip type">◆ 追踪中</span>' : ''}
    </div>
    <div class="goal-box">
      <div class="goal-head">任务目标 · 步骤 ${steps.filter(s=>s.status==='done').length}/${steps.length}</div>
      ${steps.length === 0 ? '<div class="goal-item"><span class="g-play">▶</span><span class="g-text">（尚未拆解步骤）</span></div>' :
        steps.map(s => `<div class="goal-item ${s.status==='done'?'done':''}">
          <span class="g-play">${s.status==='done'?'✓':'▶'}</span>
          <span class="g-text">${esc(s.title)}</span>
          ${s.attr_value ? `<span class="g-attr">${esc(s.attr_label||'')}: ${esc(s.attr_value)}</span>` : ''}
        </div>`).join('')}
    </div>
    ${t.desc ? `<div class="detail-desc">${esc(t.desc)}</div>` : ''}
    <div class="divider">◆</div>
    <div class="reward-title">完成任务可获得</div>
    <div class="reward-row">
      <div class="reward-item hl"><span class="reward-ico">💠</span><span class="reward-num">+${t.reward_points||10}</span></div>
      <div class="reward-item"><span class="reward-ico">📘</span><span class="reward-num">进度</span></div>
      <div class="reward-item"><span class="reward-ico">🏆</span><span class="reward-num">坚持</span></div>
    </div>
    <div class="btn-row">
      ${isTracking(t)
        ? `<button class="track-btn already" onclick="closeDetail();render()">当前追踪中 · 收起</button>
           <button class="untrack-btn" onclick="untrackTask('${t.uuid}')">取消追踪</button>
           <button class="complete-btn" onclick="completeTask('${t.uuid}')">标记完成 → 仓库</button>`
        : `<button class="track-btn ${canTrack?'disabled':''}" onclick="trackTask('${t.uuid}')" ${canTrack?'disabled':''}>
             ${canTrack ? '已达追踪上限 ' + settings.tracking_max : '追踪目标'}
           </button>
           <button class="complete-btn" onclick="completeTask('${t.uuid}')">直接完成</button>`
      }
    </div>
  `;
};

window.closeDetail = function() {
  selectedUuid = null;
  nav = 'overview';
  document.getElementById('detailView').style.display = 'none';
  document.getElementById('detailView').classList.remove('detailing');
  document.getElementById('overviewView').style.display = '';
  render();
};
document.getElementById('detailBack').addEventListener('click', closeDetail);

// =============== 操作 ===============
window.trackTask = async function(uuid) {
  const trackingCount = tasks.filter(isTracking).length;
  if (trackingCount >= settings.tracking_max) {
    alert(`已达追踪上限（${settings.tracking_max}），请到设置里调整或先取消追踪。`);
    return;
  }
  await call('start_tracking', { taskUuid: uuid });
  closeDetail();
};
window.untrackTask = async function(uuid) {
  await call('stop_tracking', { taskUuid: uuid });
  closeDetail();
};
window.completeTask = async function(uuid) {
  if (!confirm('标记该任务为已完成？将移入仓库。')) return;
  await call('complete_task', { taskUuid: uuid });
  closeDetail();
};

// 上下文菜单
const ctxMenu = document.getElementById('ctxMenu');
let ctxUuid = null;
window.ctxOpen = function(e, uuid) {
  e.preventDefault();
  ctxUuid = uuid;
  ctxMenu.style.display = 'block';
  // 定位
  const rect = document.getElementById('app').getBoundingClientRect();
  ctxMenu.style.left = (e.clientX - rect.left - 8) + 'px';
  ctxMenu.style.top = (e.clientY - rect.top) + 'px';
};
document.addEventListener('click', () => ctxMenu.style.display = 'none');
ctxMenu.querySelectorAll('button').forEach(b => {
  b.addEventListener('click', async () => {
    const cmd = b.dataset.cmd;
    if (!ctxUuid) return;
    if (cmd === 'track') await call('start_tracking', { taskUuid: ctxUuid });
    if (cmd === 'untrack') await call('stop_tracking', { taskUuid: ctxUuid });
    if (cmd === 'complete') await call('complete_task', { taskUuid: ctxUuid });
    if (cmd === 'delete') {
      if (!confirm('确定删除？')) return;
      await call('delete_task', { taskUuid: ctxUuid });
    }
    render();
  });
});

// =============== 新建表单 ===============
const addOverlay = document.getElementById('addOverlay');
let draftCat = null;
let draftDdl = 'none';
let draftPrio = 'medium';

function openAdd() {
  draftCat = null; draftDdl = 'none'; draftPrio = 'medium';
  document.getElementById('addTitle').value = '';
  document.querySelectorAll('.cat-btn').forEach(b => b.classList.toggle('active', false));
  document.querySelectorAll('.ddl-btn').forEach(b => b.classList.toggle('active', b.dataset.ddl === 'none'));
  document.querySelectorAll('.prio-btn').forEach(b => b.classList.toggle('active', b.dataset.prio === 'medium'));
  document.getElementById('addCatHint').textContent = '请选择一个分类';
  document.getElementById('addSubmit').disabled = true;
  addOverlay.style.display = '';
  setTimeout(() => document.getElementById('addTitle').focus(), 50);
}
function closeAdd() { addOverlay.style.display = 'none'; }
document.getElementById('fabAdd').addEventListener('click', openAdd);
document.querySelectorAll('[data-close-overlay="addOverlay"]').forEach(b => b.addEventListener('click', closeAdd));

// 4 分类（互斥，单选）—— 关键修复：点亮的清除其他的
document.querySelectorAll('.cat-btn').forEach(b => {
  b.addEventListener('click', () => {
    draftCat = b.dataset.cat;
    document.querySelectorAll('.cat-btn').forEach(x => x.classList.toggle('active', x === b));
    document.getElementById('addCatHint').textContent = CAT_LABEL[draftCat];
    document.getElementById('addSubmit').disabled = false;
  });
});
document.querySelectorAll('.ddl-btn').forEach(b => {
  b.addEventListener('click', () => {
    draftDdl = b.dataset.ddl;
    document.querySelectorAll('.ddl-btn').forEach(x => x.classList.toggle('active', x === b));
  });
});
document.querySelectorAll('.prio-btn').forEach(b => {
  b.addEventListener('click', () => {
    draftPrio = b.dataset.prio;
    document.querySelectorAll('.prio-btn').forEach(x => x.classList.toggle('active', x === b));
  });
});

document.getElementById('addSubmit').addEventListener('click', async () => {
  const title = document.getElementById('addTitle').value.trim();
  if (!title || !draftCat) return;
  await call('add_task', { title, category: draftCat, deadlineKey: draftDdl, priority: draftPrio });
  closeAdd();
  render();
});

// =============== 设置抽屉 ===============
document.getElementById('settingsBtn').addEventListener('click', () => {
  applySettingsToUi();
  document.getElementById('settingsOverlay').style.display = '';
});
document.querySelectorAll('[data-close-overlay="settingsOverlay"]').forEach(b => b.addEventListener('click', () => {
  document.getElementById('settingsOverlay').style.display = 'none';
}));

document.getElementById('setTrackingMax').addEventListener('change', async e => {
  settings.tracking_max = Math.max(1, Math.min(20, parseInt(e.target.value) || 3));
  e.target.value = settings.tracking_max;
  saveSettings();
  persistSettingsServer();
});
document.getElementById('setEmergencyOn').addEventListener('change', e => {
  settings.emergency_on = e.target.checked;
  saveSettings();
  persistSettingsServer();
});
document.getElementById('setEtaShort').addEventListener('change', e => {
  settings.eta_short_pct = Math.max(1, Math.min(100, parseInt(e.target.value) || 30));
  e.target.value = settings.eta_short_pct;
  saveSettings();
  persistSettingsServer();
});
document.getElementById('setEtaLong').addEventListener('change', e => {
  settings.eta_long_h = Math.max(1, Math.min(240, parseInt(e.target.value) || 36));
  e.target.value = settings.eta_long_h;
  saveSettings();
  persistSettingsServer();
});
document.getElementById('setPairBtn').addEventListener('click', async () => {
  const url = document.getElementById('setPairInput').value.trim();
  if (!url) return;
  const full = url.startsWith('http') ? url : 'http://' + url;
  try {
    await call('connect_server', { url: full });
    settings.pairing = { url: full, deviceId: '' };
    saveSettings();
    persistSettingsServer();
  } catch (e) {
    alert('连接失败：' + e.message);
  }
});
document.getElementById('setUnpair').addEventListener('click', async () => {
  settings.pairing = null;
  saveSettings();
  await call('disconnect_server', {});
  persistSettingsServer();
});

// =============== 紧急任务弹窗 ===============
// 规则：≤48h → 剩余时间 ≤ total*0.3 触发；>48h → 剩 36h 触发
function checkEmergency() {
  if (!settings.emergency_on) return;
  const now = Date.now();
  for (const t of tasks) {
    if (!t.deadline && !t.due_at) continue;
    if (t.track_status === 'done') continue;
    const dueAt = t.deadline || t.due_at;
    if (typeof dueAt !== 'number') continue;
    const total = (dueAt - (t.created_at || now)) ;
    const remain = dueAt - now;
    if (remain <= 0) continue; // 已超时另算
    const totalHours = total / 3600000;
    let trigger = false;
    let rule = '';
    if (totalHours <= 48) {
      const pctRemain = remain / total;
      if (pctRemain <= (settings.eta_short_pct / 100)) { trigger = true; rule = '短时长 3/10 阈值'; }
    } else {
      const remainH = remain / 3600000;
      if (remainH <= settings.eta_long_h) { trigger = true; rule = '长时长 36h 提前'; }
    }
    if (trigger) {
      const key = t.uuid + ':' + (t.deadline || t.due_at);
      const lastKey = 'shown:' + key;
      const last = +(localStorage.getItem(lastKey) || 0);
      if (!last || now - last > 60*60*1000) { // 同一任务同一期限 1h 内只弹一次
        localStorage.setItem(lastKey, now);
        emergencyQueue.push({ task: t, remainMs: remain, rule });
      }
    }
  }
  flushEmergency();
}

function flushEmergency() {
  if (emergencyQueue.length === 0) return;
  const { task, remainMs, rule } = emergencyQueue.shift();
  document.getElementById('emTaskName').textContent = task.title;
  document.getElementById('emCountdown').textContent = fmtRemaining(remainMs) + (rule ? '  · ' + rule : '');
  document.getElementById('emergencyOverlay').style.display = '';
  // 不打开详情，体验问题：自动打开主任务
}
document.getElementById('emLater').addEventListener('click', () => {
  document.getElementById('emergencyOverlay').style.display = 'none';
  flushEmergency();
});
document.getElementById('emClose').addEventListener('click', () => {
  document.getElementById('emergencyOverlay').style.display = 'none';
  flushEmergency();
});
document.getElementById('emOpen').addEventListener('click', () => {
  document.getElementById('emergencyOverlay').style.display = 'none';
  if (emergencyQueue[0] && emergencyQueue[0].task) openDetail(emergencyQueue[0].task.uuid);
  else if (lastEmergencyShown && lastEmergencyShown.uuid) openDetail(lastEmergencyShown.uuid);
  emergencyQueue = [];
});

// =============== 折叠态（默认客户端展开；挂件模式=右下角小条） ===============
// 客户端：默认 = .widget 全宽满屏
// 挂件模式 = .widget-mode：300x80 浮在桌面右下
document.getElementById('foldBtn').addEventListener('click', () => {
  const app = document.getElementById('app');
  const widgetMode = !app.classList.contains('widget-mode');
  if (widgetMode) {
    app.classList.add('widget-mode');
    document.getElementById('overviewView').style.display = 'none';
    document.getElementById('listView').style.display = 'none';
    document.getElementById('detailView').style.display = 'none';
    document.getElementById('foldedView').style.display = '';
    renderFolded();
    if (isTauri) call('set_window_size', { w: 320, h: 96 });
  } else {
    exitWidgetMode();
  }
});

function exitWidgetMode() {
  const app = document.getElementById('app');
  app.classList.remove('widget-mode');
  document.getElementById('foldedView').style.display = 'none';
  render();
  if (isTauri) call('set_window_size', { w: 1100, h: 720 });
}

function renderFolded() {
  const top = tasks.filter(isTracking).sort((a,b)=>(b.updated_at||0)-(a.updated_at||0))[0];
  const bar = document.getElementById('foldedBar');
  if (!top) {
    bar.innerHTML = `<span class="fb-icon">◆</span><span class="fb-text">暂无追踪任务 · 点击展开</span><span class="fb-meta"></span>`;
  } else {
    let remainStr = '';
    if (top.deadline || top.due_at) {
      const due = top.deadline || top.due_at;
      if (typeof due === 'number') remainStr = fmtRemaining(due - Date.now());
    }
    bar.innerHTML = `<span class="fb-icon">◆</span><span class="fb-text">${esc(top.title)}</span><span class="fb-meta">${esc(remainStr || '追踪中')}</span>`;
  }
  bar.onclick = exitWidgetMode;
}

// =============== 模式按钮 ===============
document.querySelectorAll('.mode-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const mode = btn.dataset.mode;
    document.querySelectorAll('.mode-btn').forEach(b => b.classList.toggle('active', b === btn));
    call('set_display_mode', { mode }).catch(()=>{});
    localStorage.setItem('mode', mode);
  });
});
const lastModeBtn = document.querySelector(`.mode-btn[data-mode="${localStorage.getItem('mode')||'top'}"]`);
if (lastModeBtn) lastModeBtn.classList.add('active');

// =============== 启动 ===============
loadSettings();
render();

// 轮询：总览和紧急提醒
setInterval(render, 15000);
setInterval(checkEmergency, 60000);

// 默认折叠态（电脑挂件模式）—— Tauri 启动即进入折叠；浏览器预览默认展开
if (isTauri) {
  // 客户端默认全宽展开（不自动折叠）
  setTimeout(() => {
    if (typeof call === 'function') call('set_window_size', { w: 1100, h: 720 }).catch(()=>{});
  }, 200);
}

// 双模式自动开：浏览器预览时默认总览
// 已被 layout 决定
