// 任务栏 桌面端前端 v4
// 暖色浅色原神风 + 三栏布局（7% / 30% / 63%）
// 等级系统 + 每日进度条（三样式）+ 涟漪追踪 + 步骤推进 + 挂件隐藏键
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
let nav = 'overview';
let tasks = [];
let archive = [];
let points = 0;
let level = null;                  // {lv, name, title, char, icon, min, max}
let progressInfo = { done: 0, total: 0, over: 0, style: 'bar' };
let settings = {
  tracking_max: 3,
  emergency_on: true,
  eta_short_pct: 30,
  eta_long_h: 36,
  progress_style: 'bar',          // bar | circle | dots
  time_limit_min: 5,              // 限时任务默认时长（分钟）
  count_default: 1,                // 次数任务默认次数
  daily_refresh: true,             // 每日任务每日 0 点自动刷新
  night_notify: true,              // 22:00 未完成弹窗
  ai_api_key: '',                  // DeepSeek API key（AI 拆解用，可留空走本地模板）
  pairing: null
};
let selectedUuid = null;
let emergencyQueue = [];
let lastEmergencyShown = {};

// =============== 4 分类归类 ===============
const CAT_OF_TYPE = {
  habit: 'daily', goal: 'goal', repeat: 'time-limited', note: 'once', once: 'once'
};
function catOf(t) {
  if (t.category && ['daily','goal','time-limited','once'].includes(t.category)) return t.category;
  return CAT_OF_TYPE[t.type] || 'once';
}
const CAT_LABEL = { 'daily':'每日任务', 'goal':'目标任务', 'time-limited':'限时任务', 'once':'次数任务' };

// =============== 5 段等级（集满积分升级，励志角色） ===============
// ico 指向 ICONS 里的 SVG 图标名（不再用 emoji / 数字，避免系统字体渲染成彩色符号）
const LEVELS = [
  { lv:1, name:'历练学徒',  title:'敢开始，就已经赢了一半',      ico:'lv1', min:0,   max:20  },
  { lv:2, name:'风华游侠',  title:'汗水从不会辜负你',            ico:'lv2', min:20,  max:60  },
  { lv:3, name:'破浪骑士',  title:'风浪越大，越显本色',          ico:'lv3', min:60,  max:120 },
  { lv:4, name:'群星行者',  title:'你走过的每一步都算数',        ico:'lv4', min:120, max:200 },
  { lv:5, name:'传奇勇者',  title:'你就是自己的传说',            ico:'lv5', min:200, max:999 }
];

// =============== 内联 SVG 图标库 ===============
// 统一线性风格：24 视口 / stroke=currentColor / 圆头圆角
// 用 SVG 而非 Unicode 字符的原因：⏳📜🛡🗡 等字符会被系统 emoji 字体接管渲染成彩色图标，
// 在暖色浅色 UI 里风格不搭，且各字符宽度不一致（14px vs 19px）导致图标视觉失衡。
const ICONS = {
  // —— 侧栏分类 ——
  overview: '<rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/>',
  daily:    '<rect x="3.2" y="5" width="17.6" height="16" rx="3"/><path d="M8 3v4M16 3v4M3.2 10h17.6"/><path d="M9.2 15.2l2 2 3.8-4"/>',
  lim:      '<path d="M7 3.2h10M7 20.8h10"/><path d="M8 3.2v3.7l4 3.9 4-3.9V3.2"/><path d="M8 20.8v-3.7l4-3.9 4 3.9v3.7"/>',
  once:     '<path d="M4.6 12a7.4 7.4 0 0 1 12.6-5.2L20 9"/><path d="M20 4.6V9h-4.4"/><path d="M19.4 12a7.4 7.4 0 0 1-12.6 5.2L4 15"/><path d="M4 19.4V15h4.4"/>',
  archive:  '<rect x="3.2" y="4" width="17.6" height="5" rx="1.7"/><path d="M5.2 9v9.6a2 2 0 0 0 2 2h9.6a2 2 0 0 0 2-2V9"/><path d="M10 13h4"/>',
  goal:     '<circle cx="12" cy="12" r="8.6"/><circle cx="12" cy="12" r="4.2"/><circle cx="12" cy="12" r="1.1" fill="currentColor" stroke="none"/>',
  // —— 等级角色 ——
  lv1: '<path d="M18.6 3.4 20.6 5.4 10.2 15.8 5.8 20.2l-2-2 4.4-4.4L18.6 3.4Z"/><path d="M14.8 7.2l2 2"/>',
  lv2: '<path d="M5.6 18.4c6.4-1.4 11.4-6.4 12.8-12.8"/><path d="M5.6 18.4l3.9-2.3"/><path d="M15.4 4.6l4 4"/>',
  lv3: '<path d="M12 3.2 5 5.7v5.8c0 4.3 2.9 7.3 7 8.8 4.1-1.5 7-4.5 7-8.8V5.7L12 3.2Z"/><path d="M9.2 11.9l2 2 3.6-3.9"/>',
  lv4: '<path d="M12 3.6l2.5 5.1 5.6.8-4 3.9.9 5.6-5-2.6-5 2.6.9-5.6-4-3.9 5.6-.8L12 3.6Z"/>',
  lv5: '<path d="M4.2 17.6h15.6M4.4 17.6 3 7.2l5.2 4L12 4.8l3.8 6.4L21 7.2l-1.4 10.4"/>',
  // —— 通用 ——
  gear:     '<circle cx="12" cy="12" r="3.1"/><path d="M12 2.8v2.5M12 18.7v2.5M21.2 12h-2.5M5.3 12H2.8M18.5 5.5l-1.8 1.8M6.5 17.6l-1.8 1.8M18.5 18.5l-1.8-1.8M6.5 6.5 4.7 4.7"/>',
  question: '<circle cx="12" cy="12" r="9"/><path d="M9.6 9.4a2.4 2.4 0 1 1 3.3 2.3c-.7.3-1.1 1-1.1 1.8v.3"/><circle cx="11.9" cy="17" r="1.05" fill="currentColor" stroke="none"/>',
  plus:     '<path d="M12 5.2v13.6M5.2 12h13.6"/>',
  check:    '<path d="M5.2 12.6l4.3 4.3L18.8 7.6"/>',
  back:     '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
  next:     '<path d="M9.5 5.5 16 12l-6.5 6.5"/>',
  close:    '<path d="M6.2 6.2l11.6 11.6M17.8 6.2 6.2 17.8"/>',
  bell:     '<path d="M18 8.6a6 6 0 1 0-12 0c0 5.4-2.2 6.9-2.2 6.9h16.4S18 14 18 8.6Z"/><path d="M13.7 19.4a2 2 0 0 1-3.4 0"/>',
  sparkle:  '<path d="M11 3.4l1.6 4.4 4.4 1.6-4.4 1.6L11 15.4 9.4 11 5 9.4l4.4-1.6L11 3.4Z"/><path d="M17.6 14.4l.7 1.9 1.9.7-1.9.7-.7 1.9-.7-1.9-1.9-.7 1.9-.7.7-1.9Z"/>',
  warn:     '<path d="M12 4.4 21 19.6H3L12 4.4Z"/><path d="M12 9.8v4.1"/><circle cx="12" cy="17" r="1.05" fill="currentColor" stroke="none"/>',
  star:     '<path d="M12 3.8l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.2-4.1 5.8-.8L12 3.8Z"/>',
  bar:      '<rect x="3" y="8.6" width="18" height="6.8" rx="3.4"/><path d="M3.4 12h8.4" stroke-width="2.6"/>',
  circle:   '<circle cx="12" cy="12" r="8.6"/><path d="M12 3.4a8.6 8.6 0 0 1 0 17Z" fill="currentColor" stroke="none"/>',
  dots:     '<circle cx="5.2" cy="12" r="1.9" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.9" fill="currentColor" stroke="none"/><circle cx="18.8" cy="12" r="1.9" fill="currentColor" stroke="none"/>',
  search:   '<circle cx="10.8" cy="10.8" r="6.3"/><path d="M15.4 15.4 20 20"/>',
  ai:       '<path d="M12 3.6l1.8 4.6 4.6 1.8-4.6 1.8L12 16.4l-1.8-4.6L5.6 10l4.6-1.8L12 3.6Z"/><path d="M18.6 16.2l.8 2.1 2.1.8-2.1.8-.8 2.1-.8-2.1-2.1-.8 2.1-.8.8-2.1Z"/>',
  ring:     '<circle cx="12" cy="12" r="8.4"/>',
  clock:    '<circle cx="12" cy="12" r="8.6"/><path d="M12 7.2V12l3.1 1.9"/>',
  coin:     '<circle cx="12" cy="12" r="8.6"/><path d="M12 7.4v9.2M14.5 9.5a2.7 2.7 0 0 0-2.5-1.5c-1.5 0-2.5.9-2.5 2.1 0 2.9 5.2 1.5 5.2 4.3 0 1.3-1.1 2.2-2.7 2.2a3 3 0 0 1-2.6-1.4"/>',
  trend:    '<path d="M3.5 16.5l5-5 3.5 3.5 7-7.2"/><path d="M14.6 7.8h4.4v4.4"/>',
  trophy:   '<path d="M8 4.4h8v4.2a4 4 0 0 1-8 0V4.4Z"/><path d="M8 5.8H5.6a2.4 2.4 0 0 0 2.4 2.4M16 5.8h2.4a2.4 2.4 0 0 1-2.4 2.4"/><path d="M12 12.6v3.4M9.6 19.6h4.8M10.2 16h3.6l.5 3.6h-4.6l.5-3.6Z"/>'
};

// 生成内联 SVG：size 缺省 16，stroke 继承父级 color
function svgIcon(name, size, sw) {
  const body = ICONS[name];
  if (!body) return '';
  const s = size || 16;
  const w = sw || 1.8;
  return '<svg class="svg-ico" width="' + s + '" height="' + s + '" viewBox="0 0 24 24" fill="none" '
       + 'stroke="currentColor" stroke-width="' + w + '" stroke-linecap="round" stroke-linejoin="round" '
       + 'aria-hidden="true" focusable="false">' + body + '</svg>';
}

// 扫描页面上所有 data-icon 占位并注入 SVG（HTML 里只写 <span data-icon="daily"></span>）
function initIcons(root) {
  (root || document).querySelectorAll('[data-icon]').forEach(el => {
    const name = el.dataset.icon;
    const size = el.dataset.iconSize ? parseInt(el.dataset.iconSize, 10) : null;
    const sw = el.dataset.iconSw ? parseFloat(el.dataset.iconSw) : null;
    if (el.dataset.iconDone === name) return;   // 已注入过，避免重复覆盖
    el.innerHTML = svgIcon(name, size, sw);
    el.dataset.iconDone = name;
  });
}
function levelOf(p) {
  let cur = LEVELS[0];
  for (const lv of LEVELS) { if (p >= lv.min) cur = lv; }
  return cur;
}
function nextLevelOf(p) {
  // 返回第一个「最小积分门槛高于当前积分」的等级，即真正的下一级
  for (const lv of LEVELS) { if (p < lv.min) return lv; }
  return null;
}

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
  document.getElementById('setTimeLimit').value = settings.time_limit_min;
  document.getElementById('setCountDefault').value = settings.count_default;
  document.getElementById('setDailyRefresh').checked = settings.daily_refresh;
  document.getElementById('setNightNotify').checked = settings.night_notify;
  document.getElementById('setProgressStyle').value = settings.progress_style;
  document.getElementById('setAiKey').value = settings.ai_api_key || '';
  document.getElementById('setPairingStatus').textContent = settings.pairing ? '已配对：' + settings.pairing.url : '未配对';
  // 进度条样式 tab 高亮
  document.querySelectorAll('.style-tab').forEach(t => {
    t.classList.toggle('active', t.dataset.style === settings.progress_style);
  });
}
async function persistSettingsServer() {
  try {
    const map = {
      tracking_max: settings.tracking_max,
      emergency_on: settings.emergency_on ? '1' : '0',
      eta_short_pct: settings.eta_short_pct,
      eta_long_h: settings.eta_long_h,
      progress_style: settings.progress_style,
      time_limit_min: settings.time_limit_min,
      count_default: settings.count_default,
      daily_refresh: settings.daily_refresh ? '1' : '0',
      night_notify: settings.night_notify ? '1' : '0',
      ai_api_key: settings.ai_api_key || ''
    };
    for (const [k, v] of Object.entries(map)) {
      await call('set_setting', { key: k, value: String(v) });
    }
    if (settings.pairing) {
      await call('save_pairing', { url: settings.pairing.url, deviceId: settings.pairing.deviceId || '' });
    }
  } catch(e) {}
}

// =============== 渲染主流程 ===============
async function fetchAll() {
  const [tasksResp, archiveResp, pointsResp, levelResp, progressResp] = await Promise.all([
    call('get_today_tasks'),
    call('get_archive', {}),
    call('get_total_points'),
    call('get_level', {}).catch(() => null),
    call('get_daily_progress', {}).catch(() => null)
  ]);
  tasks = tasksResp || [];
  archive = archiveResp || [];
  points = typeof pointsResp === 'number' ? pointsResp : (pointsResp && pointsResp.points) || 0;
  level = levelResp || levelOf(points);
  progressInfo = progressResp || progressInfo;
  if (levelResp && levelResp.style) settings.progress_style = levelResp.style;
}
async function render() {
  try {
    await fetchAll();
    document.title = '[' + points + '分/' + (level?level.name:'无') + '] 任务栏';
    const tpEl = document.getElementById('totalPoints');
    if (tpEl) tpEl.textContent = points;
    renderLevelBadge();
    renderSideNav();
    if (nav === 'overview') renderOverview();
    else renderListView();
    renderDashboard();
    checkEmergency();
  } catch (e) {
    console.error('render 失败', e);
    // 调试模式：把错误显示到页面右下角（不静默）
    let dbg = document.getElementById('_dbg');
    if (!dbg) {
      dbg = document.createElement('div');
      dbg.id = '_dbg';
      dbg.style.cssText = 'position:fixed;right:8px;bottom:8px;z-index:9999;background:#fff8dc;border:1px solid #c0392b;color:#a02820;padding:6px 10px;border-radius:4px;font-size:11px;max-width:380px;line-height:1.4;box-shadow:0 2px 8px rgba(0,0,0,0.15);font-family:monospace;';
      document.body.appendChild(dbg);
    }
    dbg.textContent = '[render err] ' + (e.message || e) + '  points=' + (typeof points!=='undefined'?points:'?') + ' level=' + (typeof level!=='undefined'?(level?level.name:'null'):'?');
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
    // 历史任务 = 独立弹窗（不占用主视图）
    if (n.dataset.nav === 'archive') { openArchiveModal(); return; }
    nav = n.dataset.nav;
    selectedUuid = null;
    document.getElementById('detailView').style.display = 'none';
    render();
  });
});
document.getElementById('archiveSearch').addEventListener('input', renderArchiveModal);
document.querySelectorAll('[data-close-overlay="archiveOverlay"]').forEach(b => b.addEventListener('click', closeArchiveModal));

// =============== 总览页（4 分类） ===============
function renderOverview() {
  document.getElementById('overviewView').style.display = '';
  document.getElementById('listView').style.display = 'none';
  const groups = { 'daily': [], 'goal': [], 'time-limited': [], 'once': [] };
  for (const t of tasks) groups[catOf(t)].push(t);

  for (const cat of Object.keys(groups)) {
    const arr = groups[cat];
    const cntEl = document.getElementById('c' + catMapId(cat));
    if (cntEl) cntEl.textContent = arr.length;
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
  let meta = '', metaIcon = '';
  if (t.due_at) { metaIcon = 'clock'; meta = fmtDue(t.due_at); }
  else if (t.category === 'once' && t.count > 1) meta = '次数 ' + (t.done_count || 0) + '/' + t.count;
  const metaHtml = meta ? `<span class="ct-meta">${metaIcon ? svgIcon(metaIcon,11,2.1) : ''}${esc(meta)}</span>` : '';
  // 追踪中：左侧不显示任何标识，右侧显示会动的涟漪菱形
  const ri = isTracking(t) ? `<span class="ct-diamond"></span>` : '';
  return `<div class="cat-task ${isTracking(t)?'tracking':''}" data-uuid="${t.uuid}" onclick="openDetail('${t.uuid}')" oncontextmenu="ctxOpen(event,'${t.uuid}')">
    <span class="ct-title">${esc(t.title)}</span>
    ${metaHtml}
    ${ri}
  </div>`;
}

// =============== 列表视图（每日任务 / 限时任务 / 次数任务） ===============
function isTodayTask(t) {
  if (t.category === 'daily' || t.category === 'once') return true;
  if (t.category === 'time-limited' && t.due_at) {
    if (typeof t.due_at === 'number') return isToday(t.due_at);
  }
  return false;
}
function renderListView() {
  document.getElementById('overviewView').style.display = 'none';
  document.getElementById('listView').style.display = '';
  const box = document.getElementById('listViewBody');
  let arr = [];
  const titleMap = { daily:'每日任务', 'time-limited':'限时任务', once:'次数任务' };
  document.getElementById('listViewTitle').textContent = titleMap[nav] || '任务';

  if (nav === 'daily') {
    arr = tasks.filter(t => t.category === 'daily');
    document.getElementById('listViewHint').textContent = '每日 0 点自动刷新';
  } else if (nav === 'time-limited') {
    arr = tasks.filter(t => t.category === 'time-limited');
    document.getElementById('listViewHint').textContent = '到期前记得完成';
  } else if (nav === 'once') {
    arr = tasks.filter(t => t.category === 'once');
    document.getElementById('listViewHint').textContent = '次数任务可重复完成';
  }

  if (arr.length === 0) {
    box.innerHTML = `<div class="empty-line">暂无任务</div>`;
  } else {
    box.innerHTML = arr.map(t => {
      let meta = '', metaIcon = '';
      if (t.due_at) { metaIcon = 'clock'; meta = fmtDue(t.due_at); }
      else if (t.category === 'once' && t.count > 1) meta = '次数 ' + (t.done_count || 0) + '/' + t.count;
      const metaHtml = meta ? `<span class="ct-meta">${metaIcon ? svgIcon(metaIcon,11,2.1) : ''}${esc(meta)}</span>` : '';
      const ri = isTracking(t) ? `<span class="ct-diamond"></span>` : '';
      return `
      <div class="list-task ${isTracking(t)?'tracking':''}" data-uuid="${t.uuid}" onclick="openDetail('${t.uuid}')" oncontextmenu="ctxOpen(event,'${t.uuid}')">
        <span class="ct-title">${esc(t.title)}</span>
        ${metaHtml}
        ${ri}
      </div>`;
    }).join('');
  }
}

// =============== 历史任务弹窗（独立小窗口，不用原生界面） ===============
// 注意：必须用函数声明（提升），因为脚本顶部第 208 行会立即引用 closeArchiveModal
function openArchiveModal() {
  renderArchiveModal();
  document.getElementById('archiveOverlay').style.display = '';
}
window.openArchiveModal = openArchiveModal;
function closeArchiveModal() {
  document.getElementById('archiveOverlay').style.display = 'none';
}
window.closeArchiveModal = closeArchiveModal;
let archiveCat = 'all';
function switchArchiveCat(cat) {
  archiveCat = cat;
  renderArchiveModal();
}
window.switchArchiveCat = switchArchiveCat;
function renderArchiveModal() {
  const kw = (document.getElementById('archiveSearch').value || '').toLowerCase().trim();
  let arr = archive.filter(t => !kw || t.title.toLowerCase().includes(kw));
  if (archiveCat !== 'all') arr = arr.filter(t => catOf(t) === archiveCat);
  document.querySelectorAll('.arc-tab').forEach(b => b.classList.toggle('active', b.dataset.cat === archiveCat));
  const box = document.getElementById('archiveBody');
  if (arr.length === 0) {
    box.innerHTML = `<div class="empty-line">暂无历史任务</div>`;
    return;
  }
  box.innerHTML = arr.map(t => {
    // 完成时间容错：后端可能只给 track_status=done 而 done_at 为空，退回 updated_at
    const doneTs = t.done_at || (t.track_status === 'done' ? t.updated_at : null);
    return `
    <div class="arc-item">
      <span class="arc-ico cat-${catOf(t)}">${doneTs ? svgIcon('check',11,2.8) : '<i class="arc-dot"></i>'}</span>
      <div class="arc-main">
        <div class="arc-title">${esc(t.title)}</div>
        <div class="arc-sub">${CAT_LABEL[catOf(t)] || '未分类'} · 完成于 ${doneTs ? fmtDue(doneTs) : '—'}</div>
      </div>
      <span class="arc-points">+${t.reward_points || 10}</span>
    </div>`;
  }).join('');
}

// =============== 右侧今日概览仪表盘 ===============
function renderDashboard() {
  // 问候语
  const h = new Date().getHours();
  let greet = '夜深了';
  if (h < 6) greet = '夜深了'; else if (h < 11) greet = '早上好'; else if (h < 14) greet = '中午好'; else if (h < 18) greet = '下午好'; else greet = '晚上好';
  document.getElementById('dashGreetText').textContent = greet + '，冒险者';

  // 等级卡
  renderLevelCard();

  // 进度条（三样式）
  renderProgress();

  // 任务统计
  const todayTasks = tasks;             // 当前已加载的就是今日任务
  const tracking = tasks.filter(isTracking).length;
  const remain = progressInfo.total - progressInfo.done;
  document.getElementById('statRemain').innerHTML = remain + '<span class="unit">项</span>';
  document.getElementById('statRemainTrend').textContent = remain <= 0 ? '全部完成 ✦' : (remain <= 2 ? '即将完成' : '加油 ✦');
  document.getElementById('statTracking').innerHTML = tracking + '<span class="unit">项</span>';
  document.getElementById('statTrackingTrend').textContent = tracking > 0 ? '进行时' : '未追踪';
  document.getElementById('statWeek').innerHTML = (progressInfo.week || 0) + '<span class="unit">项</span>';
  document.getElementById('statWeekTrend').textContent = '稳步前行';

  // 22:00 提示
  const night = settings.night_notify;
  document.getElementById('tipText').innerHTML = night
    ? '每日任务每日 0 点自动刷新，<b>22:00 还没完成</b>会弹窗提醒'
    : '每日任务每日 0 点自动刷新（22:00 提醒已关闭）';
}

function renderLevelBadge() {
  const lv = level || levelOf(points);
  // 徽章里放等级 SVG 图标（不再放数字 "3"，也不重复显示分数）
  const ic = document.getElementById('levelIcon');
  ic.innerHTML = svgIcon(lv.ico || 'lv1', 13, 2);
  // 徽章只显示等级名，分数只在设置-个人信息里看（避免多处重复）
  document.getElementById('levelText').textContent = lv.name;
}
function renderLevelCard() {
  const lv = level || levelOf(points);
  const next = nextLevelOf(points);
  document.getElementById('levelChar').innerHTML = svgIcon(lv.ico || 'lv1', 30, 1.7);
  document.getElementById('levelName').textContent = lv.name;
  document.getElementById('levelTitle').textContent = lv.title;
  // 总览等级卡：只放等级名 + 灰色问号（点问号看经验条）。分数/进度/下一级全部挪到设置-个人信息
  const lt2 = document.getElementById('levelText2');
  if (lt2) lt2.textContent = '';
  const lf = document.getElementById('levelFill');
  if (lf) lf.style.width = '0%';
  // 设置-个人信息：经验条 + 分数 + 距下一级
  document.getElementById('setCurrentLevel').textContent = lv.name + ' · ' + points + ' / ' + lv.max + ' 分';
  document.getElementById('setTotalPoints').textContent = points;
  let pct = 100;
  if (next) {
    pct = Math.min(100, Math.max(0, ((points - lv.min) / (lv.max - lv.min)) * 100));
    document.getElementById('setLevelHint').textContent = '距下一级（' + next.name + '）还差 ' + (lv.max - points) + ' 分';
  } else {
    pct = 100;
    document.getElementById('setLevelHint').textContent = '已至巅峰，满级成就达成';
  }
  document.getElementById('setLevelFill').style.width = pct + '%';
}

// =============== 进度条三样式 ===============
function renderProgress() {
  const total = progressInfo.total || 0;
  const done = Math.min(progressInfo.done || 0, total);
  const over = Math.max(0, (progressInfo.done || 0) - total);
  document.getElementById('progressDone').textContent = done;
  document.getElementById('progressTotal').textContent = total;
  const labelEl = document.getElementById('progressLabel');
  if (over > 0) labelEl.textContent = '完成 +' + over; else labelEl.textContent = '已完成';

  const content = document.getElementById('progressContent');
  const style = settings.progress_style || 'bar';
  if (style === 'bar') {
    const pct = total > 0 ? (done / total) * 100 : 0;
    const overPct = total > 0 ? (over / total) * 100 : 0;
    content.innerHTML = `<div class="progress-bar ${over>0?'has-over':''}" style="flex:1">
      <div class="bar-fill" style="width:${pct}%"></div>
      <div class="bar-over" style="left:${pct}%; width:${overPct}%"></div>
      <span class="over-tag">+${over} 超额</span>
    </div>`;
  } else if (style === 'circle') {
    const pct = total > 0 ? (done / total) : 0;
    const C = 2 * Math.PI * 26;  // 半径 26 → 周长
    const dashoffset = C * (1 - Math.min(1, pct));
    content.innerHTML = `<div class="progress-circle">
      <svg viewBox="0 0 64 64">
        <defs><linearGradient id="goldGrad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#E6C77A"/>
          <stop offset="100%" stop-color="#9A7B1A"/>
        </linearGradient></defs>
        <circle class="track" cx="32" cy="32" r="26"></circle>
        <circle class="fill" cx="32" cy="32" r="26"
          stroke-dasharray="${C}" stroke-dashoffset="${dashoffset}"></circle>
      </svg>
      <div class="pct">${Math.round(pct*100)}%</div>
    </div>`;
  } else {
    // dots
    const n = Math.max(1, total);
    let html = '<div class="progress-dots">';
    for (let i = 0; i < n; i++) {
      const cls = i < done ? 'done' : '';
      html += `<div class="dot ${cls}"></div>`;
    }
    for (let i = 0; i < over; i++) {
      html += `<div class="dot over"></div>`;
    }
    html += '</div>';
    content.innerHTML = html;
  }
}

// 进度条样式切换
document.getElementById('progressStyleTabs').addEventListener('click', e => {
  const t = e.target.closest('.style-tab');
  if (!t) return;
  settings.progress_style = t.dataset.style;
  document.querySelectorAll('.style-tab').forEach(x => x.classList.toggle('active', x === t));
  saveSettings();
  persistSettingsServer();
  renderProgress();
});

// =============== 详情页 ===============
window.openDetail = async function(uuid) {
  selectedUuid = uuid;
  document.getElementById('detailView').style.display = '';
  document.getElementById('dashboardView').style.display = 'none';
  document.getElementById('detailTitle') && (document.getElementById('detailTitle').textContent = '任务详情');
  const body = document.getElementById('detailBody');
  body.innerHTML = `<div style="color:var(--ink-faint);font-size:11px;padding:30px;text-align:center;">加载中…</div>`;

  const d = await call('get_task_detail', { taskUuid: uuid });
  const t = d.task, steps = d.steps || [];
  if (!t) { body.innerHTML = `<div style="color:var(--ink-faint);padding:30px;text-align:center;">任务不存在</div>`; return; }
  const trackingCount = tasks.filter(isTracking).length;
  const canTrack = !isTracking(t) && trackingCount >= settings.tracking_max;
  const isTrk = isTracking(t);
  const doneSteps = steps.filter(s => s.status === 'done').length;
  const totalSteps = steps.length;
  const stepPct = totalSteps > 0 ? Math.round((doneSteps / totalSteps) * 100) : 0;

  document.getElementById('detailName').textContent = t.title;
  document.getElementById('detailSub').innerHTML = `${CAT_LABEL[catOf(t)] || '未分类'}${t.due_at ? ' · 截止 ' + fmtDue(t.due_at) : ''}${t.deadline && t.deadline !== t.due_at ? ' · 期限 ' + fmtDue(t.deadline) : ''}`;
  document.getElementById('detailChips').innerHTML = `
    <span class="chip cat-${catOf(t)}">${CAT_LABEL[catOf(t)] || ''}</span>
    <span class="chip prio-${(t.priority||'m').charAt(0)}">${PRIO_LABEL[t.priority] || ''}</span>
    ${isTrk ? '<span class="chip tracking">追踪中</span>' : ''}
    ${t.count ? `<span class="chip">次数 ${t.done_count || 0}/${t.count}</span>` : ''}
  `;

  body.innerHTML = `
    ${totalSteps > 0 ? `
    <div class="dv-progress-summary">
      <span class="ps-label">步骤进度</span>
      <div class="ps-bar"><div class="ps-fill" style="width:${stepPct}%"></div></div>
      <span class="ps-num">${doneSteps} / ${totalSteps}</span>
    </div>` : ''}

    <div class="goal-box">
      <div class="goal-head">
        <span>▶ 任务步骤</span>
        <span class="gh-progress">${doneSteps} / ${totalSteps || 0} 已完成</span>
      </div>
      ${totalSteps === 0 ? '<div class="goal-item" onclick="addStep(\'' + t.uuid + '\')"><span class="g-play">' + svgIcon('plus',13,2.2) + '</span><span class="g-text" style="opacity:0.7">（尚未拆解步骤 · 点此手动添加）</span></div>' :
        steps.map(s => `<div class="goal-item ${s.status==='done'?'done':''}" onclick="toggleStep('${t.uuid}','${s.uuid}','${s.status}')" title="点击切换完成状态">
          <span class="g-play ${s.status==='done'?'done':''}">${s.status==='done'?svgIcon('check',13,2.7):svgIcon('ring',13,1.8)}</span>
          <span class="g-text">${esc(s.title)}</span>
          ${s.attr_value ? `<span class="g-attr">${esc(s.attr_label||'')}: ${esc(s.attr_value)}</span>` : ''}
        </div>`).join('')}
      <div class="goal-tools">
        <div class="goal-add" onclick="addStep('${t.uuid}')">${svgIcon('plus',13,2.2)} 添加步骤</div>
        <div class="goal-ai" onclick="aiBreakdown('${t.uuid}','${esc(t.title)}')">${svgIcon('ai',13,1.9)} AI 拆解</div>
      </div>
    </div>

    ${t.desc ? `<div class="detail-desc">${esc(t.desc)}</div>` : ''}

    <div class="divider"></div>

    <div class="reward-title">完成任务可获得</div>
    <div class="reward-row">
      <div class="reward-item hl"><span class="reward-ico">${svgIcon('coin',15,1.8)}</span><span class="reward-num">+${t.reward_points||10} 积分</span></div>
      <div class="reward-item"><span class="reward-ico">${svgIcon('trend',15,1.9)}</span><span class="reward-num">推进进度</span></div>
      <div class="reward-item"><span class="reward-ico">${svgIcon('trophy',15,1.9)}</span><span class="reward-num">积攒坚持</span></div>
    </div>

    <div class="btn-row">
      <button class="track-btn ${isTrk?'tracking':''}" onclick="${isTrk ? `untrackTask('${t.uuid}')` : `trackTask('${t.uuid}')`}" ${(!isTrk && canTrack)?'disabled':''}>
        <span class="btn-ico">${svgIcon(isTrk ? 'check' : 'plus', 12, 2.4)}</span>${isTrk ? '停止追踪' : (canTrack ? '已达上限' : '追踪任务')}
      </button>
      <button class="complete-btn" onclick="completeTask('${t.uuid}')"><span class="btn-ico">${svgIcon('check', 12, 2.6)}</span>完成任务</button>
    </div>
  `;
};

window.closeDetail = function() {
  selectedUuid = null;
  document.getElementById('detailView').style.display = 'none';
  document.getElementById('dashboardView').style.display = '';
  render();
};
document.getElementById('detailBack').addEventListener('click', closeDetail);

// =============== 操作 ===============
window.trackTask = async function(uuid) {
  const trackingCount = tasks.filter(isTracking).length;
  if (trackingCount >= settings.tracking_max) {
    alert('已达追踪上限（' + settings.tracking_max + '），请到设置里调整或先取消追踪。');
    return;
  }
  await call('start_tracking', { taskUuid: uuid });
  await render();
  openDetail(uuid);
};
window.untrackTask = async function(uuid) {
  await call('stop_tracking', { taskUuid: uuid });
  await render();
  openDetail(uuid);
};
window.completeTask = async function(uuid) {
  await call('complete_task', { taskUuid: uuid });
  closeDetail();
  // 检查是否完成今日最后一项（每日 + 当日限时）
  await checkBlessing();
};
async function checkBlessing() {
  try {
    const r = await call('get_daily_progress', {});
    if (r && r.total > 0 && r.done >= r.total) {
      showBless();
    }
  } catch(e) {}
}
function showBless() {
  const msgs = [
    { t:'恭喜你已完成今日任务', s:'愿星辰指引你的前路', m:'日拱一卒，功不唐捐<br>愿你继续保持这份热情' },
    { t:'今日之约，已圆满', s:'下一段旅程在前方等你', m:'<b>坚持</b>是最高的技巧<br>你已经走在了大多数人前面' },
    { t:'愿你此刻内心安宁', s:'每一份努力都在积蓄力量', m:'今日播种，明日收获<br>休息一下，准备迎接新的挑战' }
  ];
  const m = msgs[Math.floor(Math.random() * msgs.length)];
  document.getElementById('blessTitle').textContent = m.t;
  document.getElementById('blessSub').textContent = m.s;
  document.getElementById('blessMsg').innerHTML = m.m;
  document.getElementById('blessOverlay').style.display = '';
}
document.getElementById('blessClose').addEventListener('click', () => {
  document.getElementById('blessOverlay').style.display = 'none';
});

// 步骤推进
window.toggleStep = async function(taskUuid, stepUuid, curStatus) {
  const next = curStatus === 'done' ? 'todo' : 'done';
  await call('advance_step', { taskUuid, stepUuid, status: next });
  openDetail(taskUuid);
  render();
};
window.addStep = async function(taskUuid) {
  const title = prompt('步骤名：');
  if (!title) return;
  await call('add_step', { taskUuid, title });
  openDetail(taskUuid);
  render();
};

// AI 拆解：有 API key 走云端（preview-server 转发 DeepSeek），没有就用本地模板兜底
window.aiBreakdown = async function(taskUuid, title) {
  const btn = event && event.target;
  if (btn) { btn.textContent = '拆解中…'; btn.style.opacity = '0.6'; }
  try {
    const r = await call('ai_breakdown', { title });
    const steps = (r && r.steps) || [];
    if (steps.length === 0) { alert('拆解失败，请稍后重试'); return; }
    for (const s of steps) {
      await call('add_step', { taskUuid, title: s });
    }
    openDetail(taskUuid);
    render();
  } catch (e) {
    alert('AI 拆解失败：' + e.message);
  } finally {
    if (btn) { btn.innerHTML = svgIcon('ai',13,1.9) + ' AI 拆解'; btn.style.opacity = ''; }
  }
};

// 上下文菜单
const ctxMenu = document.getElementById('ctxMenu');
let ctxUuid = null;
window.ctxOpen = function(e, uuid) {
  e.preventDefault();
  ctxUuid = uuid;
  ctxMenu.style.display = 'block';
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
let draftCount = 1;

function openAdd() {
  draftCat = null; draftDdl = 'none'; draftPrio = 'medium'; draftCount = settings.count_default;
  document.getElementById('addTitle').value = '';
  document.getElementById('addCount').value = settings.count_default;
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
document.getElementById('addCount').addEventListener('change', e => {
  draftCount = Math.max(1, parseInt(e.target.value) || 1);
  e.target.value = draftCount;
});

document.getElementById('addSubmit').addEventListener('click', async () => {
  const title = document.getElementById('addTitle').value.trim();
  if (!title || !draftCat) return;
  // 限时任务强制要求选个时长（不是 none）
  if (draftCat === 'time-limited' && draftDdl === 'none') {
    alert('限时任务请选择时长');
    return;
  }
  await call('add_task', {
    title, category: draftCat,
    deadlineKey: draftCat === 'time-limited' ? draftDdl : 'none',
    priority: draftPrio,
    count: draftCat === 'once' ? draftCount : 1
  });
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

function bindSetting(id, key, parser) {
  const el = document.getElementById(id);
  el.addEventListener('change', e => {
    settings[key] = parser ? parser(e.target.value) : e.target.checked !== undefined ? e.target.checked : e.target.value;
    saveSettings();
    persistSettingsServer();
  });
}
bindSetting('setTrackingMax', 'tracking_max', v => Math.max(1, Math.min(20, parseInt(v) || 3)));
bindSetting('setEmergencyOn', 'emergency_on');
bindSetting('setEtaShort', 'eta_short_pct', v => Math.max(1, Math.min(100, parseInt(v) || 30)));
bindSetting('setEtaLong', 'eta_long_h', v => Math.max(1, Math.min(240, parseInt(v) || 36)));
bindSetting('setTimeLimit', 'time_limit_min', v => Math.max(1, Math.min(1440, parseInt(v) || 5)));
bindSetting('setCountDefault', 'count_default', v => Math.max(1, Math.min(999, parseInt(v) || 1)));
bindSetting('setDailyRefresh', 'daily_refresh');
bindSetting('setNightNotify', 'night_notify');
bindSetting('setProgressStyle', 'progress_style');
bindSetting('setAiKey', 'ai_api_key', v => String(v).trim());

document.getElementById('setPairBtn').addEventListener('click', async () => {
  const url = document.getElementById('setPairInput').value.trim();
  if (!url) return;
  const full = url.startsWith('http') ? url : 'http://' + url;
  try {
    await call('connect_server', { url: full });
    settings.pairing = { url: full, deviceId: '' };
    saveSettings();
    persistSettingsServer();
  } catch (e) { alert('连接失败：' + e.message); }
});
document.getElementById('setUnpair').addEventListener('click', async () => {
  settings.pairing = null;
  saveSettings();
  await call('disconnect_server', {});
  persistSettingsServer();
});
document.getElementById('setWidgetMode').addEventListener('click', () => {
  document.getElementById('settingsOverlay').style.display = 'none';
  const app = document.getElementById('app');
  app.classList.add('widget-mode');
  document.getElementById('overviewView').style.display = 'none';
  document.getElementById('listView').style.display = 'none';
  document.getElementById('detailView').style.display = 'none';
  document.getElementById('dashboardView').style.display = 'none';
  document.getElementById('foldedView').style.display = '';
  renderFolded();
  if (isTauri) call('set_window_size', { w: 360, h: 88 }).catch(()=>{});
});

// 等级徽章点击 → 打开设置
document.getElementById('levelBadge').addEventListener('click', () => {
  applySettingsToUi();
  document.getElementById('settingsOverlay').style.display = '';
});
// 等级卡灰色问号 → 打开设置-个人信息（看经验条）
document.getElementById('levelQ').addEventListener('click', e => {
  e.stopPropagation();
  applySettingsToUi();
  document.getElementById('settingsOverlay').style.display = '';
  // 滚动到个人信息区块
  const exp = document.getElementById('setCurrentLevel');
  if (exp && exp.closest('.settings-section')) {
    exp.closest('.settings-section').scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
});

// =============== 紧急任务弹窗 ===============
function checkEmergency() {
  if (!settings.emergency_on) return;
  const now = Date.now();
  for (const t of tasks) {
    if (!t.deadline && !t.due_at) continue;
    if (t.track_status === 'done') continue;
    const dueAt = t.deadline || t.due_at;
    if (typeof dueAt !== 'number') continue;
    const total = (dueAt - (t.created_at || now));
    const remain = dueAt - now;
    if (remain <= 0) continue;
    const totalHours = total / 3600000;
    let trigger = false, rule = '';
    if (totalHours <= 48) {
      const pctRemain = remain / total;
      if (pctRemain <= (settings.eta_short_pct / 100)) { trigger = true; rule = '短时长 ' + Math.round(settings.eta_short_pct/10) + '/10 阈值'; }
    } else {
      const remainH = remain / 3600000;
      if (remainH <= settings.eta_long_h) { trigger = true; rule = '长时长 ' + settings.eta_long_h + 'h 提前'; }
    }
    if (trigger) {
      const key = t.uuid + ':' + (t.deadline || t.due_at);
      const lastKey = 'shown:' + key;
      const last = +(localStorage.getItem(lastKey) || 0);
      if (!last || now - last > 60*60*1000) {
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
  lastEmergencyShown = { uuid: task.uuid };
  document.getElementById('emTaskName').textContent = task.title;
  document.getElementById('emCountdown').textContent = fmtRemaining(remainMs) + (rule ? '  · ' + rule : '');
  document.getElementById('emergencyOverlay').style.display = '';
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
  if (lastEmergencyShown && lastEmergencyShown.uuid) openDetail(lastEmergencyShown.uuid);
  emergencyQueue = [];
});

// =============== 折叠态（桌面挂件） ===============
document.getElementById('foldedView').addEventListener('click', e => {
  if (e.target.closest('.fb-btn')) return;  // 隐藏按键不触发展开
  exitWidgetMode();
});

function exitWidgetMode() {
  const app = document.getElementById('app');
  app.classList.remove('widget-mode');
  document.getElementById('foldedView').style.display = 'none';
  document.getElementById('dashboardView').style.display = '';
  render();
  if (isTauri) call('set_window_size', { w: 1100, h: 720 }).catch(()=>{});
}

function renderFolded() {
  const top = tasks.filter(isTracking).sort((a,b)=>(b.updated_at||0)-(a.updated_at||0))[0];
  const bar = document.getElementById('foldedBar');
  const pct = progressInfo.total > 0 ? Math.min(100, (progressInfo.done / progressInfo.total) * 100) : 0;
  const overPct = progressInfo.total > 0 ? (Math.max(0, progressInfo.done - progressInfo.total) / progressInfo.total) * 100 : 0;
  bar.querySelector('.fb-fill').style.width = pct + '%';
  if (!top) {
    bar.querySelector('.fb-text').textContent = '暂无追踪任务 · 点击展开';
    bar.querySelector('.fb-meta').textContent = '';
  } else {
    bar.querySelector('.fb-text').textContent = top.title;
    let remainStr = '';
    if (top.deadline || top.due_at) {
      const due = top.deadline || top.due_at;
      if (typeof due === 'number') remainStr = fmtRemaining(due - Date.now());
    }
    bar.querySelector('.fb-meta').textContent = remainStr || '追踪中';
  }
}

// 挂件两个隐藏按键
document.getElementById('fbNext').addEventListener('click', async e => {
  e.stopPropagation();
  // 找下一个进度条范围内的任务（每日 + 当日限时）
  const inScope = tasks.filter(t => t.category === 'daily' || (t.category === 'time-limited' && t.due_at && isToday(t.due_at)));
  if (inScope.length === 0) return;
  const idx = Math.max(0, inScope.findIndex(t => t.track_status === 'tracking'));
  const next = inScope[(idx + 1) % inScope.length];
  await call('start_tracking', { taskUuid: next.uuid });
  await render();
  renderFolded();
});
document.getElementById('fbComplete').addEventListener('click', async e => {
  e.stopPropagation();
  const top = tasks.filter(isTracking)[0];
  if (!top) return;
  await call('complete_task', { taskUuid: top.uuid });
  await render();
  renderFolded();
  await checkBlessing();
});
function isToday(ts) {
  if (typeof ts !== 'number') return false;
  const d = new Date(ts), n = new Date();
  return d.toDateString() === n.toDateString();
}

// =============== 启动 ===============
loadSettings();
document.title = '…loading…';
// 等 DOM/资源全部 ready 再 render（避免 init 时拿不到某些元素）
function startApp() {
  initIcons();      // 先把所有 data-icon 占位注入成内联 SVG
  render();
  setTimeout(render, 250);
  setTimeout(render, 1000);
  setInterval(render, 15000);
  setInterval(checkEmergency, 60000);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', startApp);
} else {
  startApp();
}

// 22:00 弹窗检测（每分钟查一次，到 22:00 且有未完成每日任务时弹）
setInterval(() => {
  if (!settings.night_notify) return;
  const now = new Date();
  if (now.getHours() === 22 && now.getMinutes() === 0) {
    const undoneDaily = tasks.filter(t => t.category === 'daily' && t.track_status !== 'done');
    if (undoneDaily.length > 0) {
      const names = undoneDaily.map(t => t.title).join('、');
      alert('今日还有未完成的每日任务：\n' + names + '\n\n请尽快完成或处理');
    }
  }
}, 60000);
