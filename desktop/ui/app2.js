// 任务栏 桌面端前端 v4.6
// 暖色浅色 + 三栏布局（7% / 30% / 63%）
// 等级系统 + 每日进度条（三样式）+ 涟漪追踪 + 步骤推进 + 挂件模式 + SVG 图标库
// ⚠️ 2026-09-02 22:5x 修复：原 `const isTauri` 与 Tauri/WebView2 环境预置的全局保留名
// `isTauri`（window 上 defineProperty 的不可写属性，值恒 true，先于页面脚本注入）冲突，
// 导致本脚本顶层 const 声明抛 SyntaxError、整份 JS 被拒绝执行（图标不注入/按钮全瘫）。
// 改名 isTauriEnv 彻底避开；此处不依赖 window.isTauri（其恒 true 但不可写，语义不同）。
// =============== 全局禁用系统右键菜单（boss 反馈右键出系统级窗口） ===============
// contextmenu 默认动作（弹出 WebView2/系统级菜单）一律拦截；任务行自定义右键菜单（ctxOpen）由元素内联
// oncontextmenu 主动设置 ctxMenu.display = 'block' 实现，不依赖默认 action，不受 preventDefault 影响
document.addEventListener('contextmenu', e => e.preventDefault());

const isTauriEnv = !!(window.__TAURI__ && window.__TAURI__.core);

async function call(cmd, args) {
  if (isTauriEnv) {
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
  count_default: 5,                // 次数任务默认次数
  daily_refresh: true,             // 每日任务每日 0 点自动刷新
  night_notify: true,              // 22:00 未完成弹窗
  pairing: null,
  nickname: '历练者'               // 用户昵称（自称；打卡汇报用）
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
  // boss：之前 5 个 title 写的是鸡汤（"再小的开始也是一大步"等），被嫌尬。
  // 删成空串，渲染处用 lv.title || '' 保护，避免空 div。
  { lv:1, name:'历练学徒',  title:'',                  ico:'lv1', min:0,   max:20  },
  { lv:2, name:'风华游侠',  title:'',                  ico:'lv2', min:20,  max:60  },
  { lv:3, name:'破浪骑士',  title:'',                  ico:'lv3', min:60,  max:120 },
  { lv:4, name:'群星行者',  title:'',                  ico:'lv4', min:120, max:200 },
  { lv:5, name:'传奇勇者',  title:'',                  ico:'lv5', min:200, max:999 }
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
  lv1: '<circle cx="12" cy="6.5" r="1.5"/><path d="M12 8v13"/><path d="M8.5 13c2-1 3.2-2.2 3.5-5"/><path d="M15.5 13c-2-1-3.2-2.2-3.5-5"/>',
  lv2: '<path d="M12 4l8 9c-2 5-5 8-8 8s-6-3-8-8l8-9z"/>',
  // boss 认可的「骑枪破浪」：三角枪尖 + 枪杆 + 护手横档 + 垂直贯穿两道波形带
  // v4.11 全局 svg path { fill: ink-black-soft; stroke: none } —— 强制 fill 让开放路径（stroke-only 线条）糊成色块
  // 改用闭合路径（每个子组件独立闭合）让 fill 渲染为清晰图形
  lv3: '<path d="M12 2 L8.4 7 L15.6 7 Z"/><path d="M11 7 L13 7 L13 21.5 L11 21.5 Z"/><path d="M6.8 9 L17.2 9 L17.2 10.6 L6.8 10.6 Z"/><path d="M2.5 13.6 C 4.8 11.9 7.2 11.9 9.5 13.6 C 11.8 15.3 14.2 15.3 16.5 13.6 C 18.2 12.4 19.5 12.3 21.5 13 L 21.5 15 C 19.7 14.2 18.5 14.3 16.5 15.6 C 14.2 17.3 11.8 17.3 9.5 15.6 C 7.2 13.9 4.8 13.9 2.5 15.6 Z"/><path d="M2.5 17.6 C 4.8 15.9 7.2 15.9 9.5 17.6 C 11.8 19.3 14.2 19.3 16.5 17.6 C 18.2 16.4 19.5 16.3 21.5 17 L 21.5 19 C 19.7 18.2 18.5 18.3 16.5 19.6 C 14.2 21.3 11.8 21.3 9.5 19.6 C 7.2 17.9 4.8 17.9 2.5 19.6 Z"/>',
  lv4: '<path d="M12 3.6l2.5 5.1 5.6.8-4 3.9.9 5.6-5-2.6-5 2.6.9-5.6-4-3.9 5.6-.8L12 3.6Z"/>',
  lv5: '<path d="M4.2 17.6h15.6M4.4 17.6 3 7.2l5.2 4L12 4.8l3.8 6.4L21 7.2l-1.4 10.4"/>',
  // —— 通用 ——
  // boss：之前 gear 是"中心圆 + 8 条放射短线"，画出来就是太阳/星形
  // 改成 Lucide 标准齿轮（8 齿 + 中心圆），24×24 16px 都清晰
  gear:     '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
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
  code:     '<path d="M8.6 6.4 3 12l5.6 5.6"/><path d="M15.4 6.4 21 12l-5.6 5.6"/><path d="M13.4 5.2l-2.8 13.6"/>',
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
  // 数字设置 → 同步默认态 cur 文字
  const syncNum = (id, val) => {
    const cur = document.getElementById('cur_' + id);
    if (cur) cur.textContent = val;
    const inp = document.getElementById(id);
    if (inp) inp.value = val;
  };
  syncNum('setTrackingMax', settings.tracking_max);
  syncNum('setEtaShort', settings.eta_short_pct);
  syncNum('setEtaLong', settings.eta_long_h);
  syncNum('setTimeLimit', settings.time_limit_min);
  syncNum('setCountDefault', settings.count_default);
  document.getElementById('setEmergencyOn').checked = settings.emergency_on;
  // 每日刷新 / 进度条样式已撤出设置 UI（旧字段保留用于兼容 / 不再写入）
  document.getElementById('setNightNotify').checked = settings.night_notify;
  document.getElementById('setPairingStatus').textContent = settings.pairing ? '已配对：' + settings.pairing.url : '未配对';
  // 配对区条件显示：已配对时隐藏扫描设备 + 手动配对两行（防误解可同时连多个手机）
  const paired = !!settings.pairing;
  document.querySelectorAll('.ss-pair-hidden-on-pair').forEach(el => el.classList.toggle('ss-pair-hidden', paired));
  // 解除配对按钮：未配对时禁用 + 灰
  const unpair = document.getElementById('setUnpair');
  if (unpair) {
    unpair.disabled = !settings.pairing;
    unpair.classList.toggle('disabled', !settings.pairing);
    unpair.title = settings.pairing ? ('解除与 ' + settings.pairing.url + ' 的配对') : '尚未配对，无法解除';
  }
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
      nickname: settings.nickname || '历练者'
    };
    for (const [k, v] of Object.entries(map)) {
      await call('set_setting', { key: k, value: String(v) });
    }
    if (settings.pairing) {
      await call('save_pairing', { url: settings.pairing.url, deviceId: settings.pairing.deviceId || '' });
    }
  } catch(e) {}
}
// 启动时从后端把 user-profile 字段同步到 settings（昵称 / 头像 / ...)
async function hydrateUserProfile() {
  try {
    const nn = await call('get_setting', { key: 'nickname' });
    if (nn) settings.nickname = nn;
    const ai = await call('get_setting', { key: 'avatar_idx' });
    if (ai != null && !isNaN(parseInt(ai))) settings.avatar_idx = parseInt(ai);
  } catch (e) {}
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
      dbg.style.cssText = 'position:fixed;right:8px;bottom:8px;z-index:9999;background:#1A2336;border:1px solid #c0392b;color:#EDE7D8;padding:6px 10px;border-radius:4px;font-size:11px;max-width:380px;line-height:1.4;box-shadow:0 2px 8px rgba(0,0,0,0.15);font-family:monospace;';
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
    const card = document.querySelector(`.cat-card[data-cat="${cat}"]`);
    const cntEl = document.getElementById('c' + catMapId(cat));
    const box = document.getElementById('list' + catMapId(cat));
    // 无任务时整张分类卡隐藏（boss 反馈"无任务就不用显示了啊"）
    if (arr.length === 0) {
      if (card) card.style.display = 'none';
    } else {
      if (card) card.style.display = '';
      if (cntEl) cntEl.textContent = arr.length;
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
  return `<div class="cat-task ${isTracking(t)?'tracking':''}" data-uuid="${t.uuid}" onclick="openDetail('${t.uuid}')">
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
  const titleMap = { daily:'每日任务', goal:'目标任务', 'time-limited':'限时任务', once:'次数任务' };
  document.getElementById('listViewTitle').textContent = titleMap[nav] || '任务';

  if (nav === 'daily') {
    arr = tasks.filter(t => t.category === 'daily');
    document.getElementById('listViewHint').textContent = '每日 0 点自动刷新';
  } else if (nav === 'goal') {
    arr = tasks.filter(t => t.category === 'goal');
    document.getElementById('listViewHint').textContent = '为目标坚持推进';
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
      <div class="list-task ${isTracking(t)?'tracking':''}" data-uuid="${t.uuid}" onclick="openDetail('${t.uuid}')">
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
  // 问候语（动态昵称，不再固定"冒险者"）
  const h = new Date().getHours();
  let greet = '夜深了';
  if (h < 6) greet = '夜深了'; else if (h < 11) greet = '早上好'; else if (h < 14) greet = '中午好'; else if (h < 18) greet = '下午好'; else greet = '晚上好';
  const nick = settings.nickname || '历练者';
  document.getElementById('dashGreetText').textContent = greet + '，' + nick;

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

  // boss 反馈：22:00 提醒提示很没必要 → tipCard 已删整块（HTML+JS），不再渲染
}

function renderLevelBadge() {
  const lv = level || levelOf(points);
  // boss E：顶栏徽章左侧 .level-icon 优先显示用户头像（settings.avatar_img），
  // 没有头像则降级显示等级 SVG 图标
  const ic = document.getElementById('levelIcon');
  if (settings.avatar_img) {
    ic.innerHTML = '';
    ic.style.backgroundImage = "url(\"" + settings.avatar_img.replace(/"/g, '%22') + "\")";
    ic.style.backgroundSize = 'cover';
    ic.style.backgroundPosition = 'center';
    ic.style.backgroundRepeat = 'no-repeat';
  } else {
    ic.style.backgroundImage = '';
    ic.innerHTML = svgIcon(lv.ico || 'lv1', 13, 2);
  }
  document.getElementById('levelText').textContent = lv.name;
}
function renderLevelCard() {
  const lv = level || levelOf(points);
  const next = nextLevelOf(points);
  // 总览大卡
  document.getElementById('levelChar').innerHTML = svgIcon(lv.ico || 'lv1', 30, 1.7);
  document.getElementById('levelName').textContent = lv.name;
  // boss：title 全删，元素自身隐藏避免占行
  const ltEl = document.getElementById('levelTitle');
  if (ltEl) { ltEl.textContent = lv.title || ''; ltEl.style.display = lv.title ? '' : 'none'; }
  // 历史遗留：旧设置页等级卡的元素已删除，留空保护即可（个人信息的等级卡在下方同步刷新）
  const lt2 = document.getElementById('levelText2');
  if (lt2) lt2.textContent = '';
  const lf = document.getElementById('levelFill');
  if (lf) lf.style.width = '0%';
  // 个人信息 modal 的等级卡同步刷新（打开时即最新）
  const pn = document.getElementById('profileLevelName'); if (pn) pn.textContent = lv.name;
  const pt = document.getElementById('profileLevelTitle'); if (pt) { pt.textContent = lv.title || ''; pt.style.display = lv.title ? '' : 'none'; }
  const pi = document.getElementById('profileLevelIcon'); if (pi) pi.innerHTML = svgIcon(lv.ico || 'lv1', 36, 1);
  const pnum = document.getElementById('profilePointsNum'); if (pnum) pnum.textContent = points;
  const pfill = document.getElementById('profileExpFill');
  const phint = document.getElementById('profileExpHint');
  if (next) {
    const pct = Math.min(100, Math.max(0, ((points - lv.min) / Math.max(1, lv.max - lv.min)) * 100));
    if (pfill) pfill.style.width = pct + '%';
    // 0 分时空态给引导文案；>0 时显示距下一级具体分数
    if (phint) phint.textContent = points <= 0
      ? '开始你的第一项任务吧'
      : '距下一级（' + next.name + '）还差 ' + Math.max(0, lv.max - points) + ' 分';
  } else {
    if (pfill) pfill.style.width = '100%';
    if (phint) phint.textContent = '已至巅峰，满级成就达成';
  }
}

// 进度条统一硬编码条形样式；旧版本允许 bar/circle/dots 三选一，但 UI 看不清，移除冗余设计。
// 原 .progress-style-tabs 节点已从 index.html 删除，progressInfo.style 字段后端读取若返回也忽略。
function renderProgress() {
  const total = progressInfo.total || 0;
  const done = Math.min(progressInfo.done || 0, total);
  const over = Math.max(0, (progressInfo.done || 0) - total);
  document.getElementById('progressDone').textContent = done;
  document.getElementById('progressTotal').textContent = total;
  const labelEl = document.getElementById('progressLabel');
  if (over > 0) labelEl.textContent = '完成 +' + over; else labelEl.textContent = '已完成';
  const pct = total > 0 ? (done / total) * 100 : 0;
  const overPct = total > 0 ? (over / total) * 100 : 0;
  const content = document.getElementById('progressContent');
  content.innerHTML = `<div class="progress-bar ${over>0?'has-over':''}" style="flex:1">
      <div class="bar-fill" style="width:${pct}%"></div>
      <div class="bar-over" style="left:${pct}%; width:${overPct}%"></div>
      <span class="over-tag">+${over} 超额</span>
    </div>`;
}

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
  // boss B：详情底部"完成任务"按钮与步骤完成互斥——有步骤且未全完成时禁用并提示剩余步数
  const hasStep = totalSteps > 0;
  const allStepDone = hasStep && doneSteps === totalSteps;
  const remainingSteps = hasStep ? totalSteps - doneSteps : 0;

  document.getElementById('detailName').textContent = t.title;
  document.getElementById('detailSub').innerHTML = `${CAT_LABEL[catOf(t)] || '未分类'}${t.due_at ? ' · 截止 ' + fmtDue(t.due_at) : ''}${t.deadline && t.deadline !== t.due_at ? ' · 期限 ' + fmtDue(t.deadline) : ''}`;
  document.getElementById('detailChips').innerHTML = `
    <span class="chip cat-${catOf(t)}">${CAT_LABEL[catOf(t)] || ''}</span>
    <span class="chip prio-${(t.priority||'m').charAt(0)}">${PRIO_LABEL[t.priority] || ''}</span>
    ${isTrk ? '<span class="chip tracking">追踪中</span>' : ''}
    ${t.count > 1 ? `<span class="chip">次数 ${t.done_count || 0}/${t.count}</span>` : ''}
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
      ${totalSteps === 0 ? '<div class="goal-item" onclick="addStep(\'' + t.uuid + '\')"><span class="g-play">' + svgIcon('plus',13,2.2) + '</span><span class="g-text" style="opacity:0.7">（尚未拆解步骤 · 点此添加）</span></div>' :
        steps.map(s => `<div class="goal-item ${s.status==='done'?'done':''}" onclick="toggleStep('${t.uuid}','${s.uuid}','${s.status}')" title="点击切换完成状态">
          <span class="g-play ${s.status==='done'?'done':''}">${s.status==='done'?svgIcon('check',13,2.7):svgIcon('ring',13,1.8)}</span>
          <span class="g-text">${esc(s.title)}</span>
          ${s.attr_value ? `<span class="g-attr">${esc(s.attr_label||'')}: ${esc(s.attr_value)}</span>` : ''}
        </div>`).join('')}
      <!-- boss A：添加步骤单一入口，JSON 不再独立并排按钮，收进展开面板（手动/JSON 二合一） -->
      <div class="goal-tools">
        <div class="goal-add" id="goalAddBtn" onclick="addStep('${t.uuid}')">${svgIcon('plus',13,2.2)} 添加步骤</div>
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
      <button class="complete-btn ${hasStep && !allStepDone ? 'locked' : ''}" 
              onclick="${(hasStep && !allStepDone) ? '' : `completeTask('${t.uuid}')`}" 
              ${(hasStep && !allStepDone) ? 'disabled' : ''}
              title="${(hasStep && !allStepDone) ? '还有 ' + remainingSteps + ' 个步骤未完成，请先完成所有步骤' : ''}">
        <span class="btn-ico">${svgIcon((hasStep && !allStepDone) ? 'lock' : 'check', 12, 2.6)}</span>${(hasStep && !allStepDone) ? '还需 ' + remainingSteps + ' 步' : '完成任务'}
      </button>
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
  // 提前取 title/points（render 之后可能从 today 列表过滤掉已完成任务）
  const before = (typeof tasks !== 'undefined' ? tasks : []).find(t => t.uuid === uuid);
  const title = before ? before.title : '';
  const points = (before && before.reward_points) || 0;
  const r = await call('complete_task', { taskUuid: uuid });
  closeDetail();
  // 刷新主面板（积分/进度/列表）
  await render();
  // boss 要"完成任务弹奖励提示"（原神风）：每次真正完成（partial=false）都弹
  if (r && r.partial === false) {
    const habitPts = (r.habit && points === 0) ? 5 : points;
    showBless({ mode: 'reward', title: title, points: habitPts });
    // boss D：把今日奖励存 sessionStorage，设置页"查看今日奖励"可回放
    try {
      const key = 'todayRewards_' + new Date().toDateString();
      const list = JSON.parse(sessionStorage.getItem(key) || '[]');
      list.push({ title, points: habitPts, at: Date.now() });
      sessionStorage.setItem(key, JSON.stringify(list));
    } catch(e) {}
  }
  // partial 路径（次数任务累计中）：不弹窗打断节奏，仅在控制台
  else if (r && r.partial === true && r.done_count !== undefined) {
    console.log('[progress]', r.done_count + '/' + r.count);
  }
};
function showBless(opts) {
  opts = opts || {};
  const tEl = document.getElementById('blessTitle');
  const sEl = document.getElementById('blessSub');
  const mEl = document.getElementById('blessMsg');
  if (opts.mode === 'daily') {
    // 今日全完成祝福（每日仅一次）
    const msgs = [
      { t:'恭喜你已完成今日任务', s:'愿星辰指引你的前路', m:'日拱一卒，功不唐捐<br>愿你继续保持这份热情' },
      { t:'今日之约，已圆满', s:'下一段旅程在前方等你', m:'<b>坚持</b>是最高的技巧<br>你已经走在了大多数人前面' },
      { t:'愿你此刻内心安宁', s:'每一份努力都在积蓄力量', m:'今日播种，明日收获<br>休息一下，准备迎接新的挑战' }
    ];
    const m = msgs[Math.floor(Math.random() * msgs.length)];
    tEl.textContent = m.t; sEl.textContent = m.s; mEl.innerHTML = m.m;
  } else if (opts.mode === 'reward') {
    // 单任务完成奖励（原神风：+N 经验）
    const pts = opts.points || 0;
    const title = opts.title || '本回合';
    tEl.textContent = '+ ' + pts + ' 经验';
    sEl.textContent = title;
    mEl.innerHTML = '<b>任务已完成</b><br>这一小步，已被记下';
  } else {
    tEl.textContent = '完成';
    sEl.textContent = '';
    mEl.innerHTML = '继续保持';
  }
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
// 添加步骤 inline 版：原生 prompt 在 Tauri WebView2 里被禁用，会出现"按了没反应"的假死，
// 改成在目标任务的步骤列表里就地插入一行输入框，按回车 / 点"添加"提交，Esc/取消收起。
window.showInlineAddStep = function(taskUuid) {
  // 详情页步骤区的工具行容器，把内联输入行插到它前面
  const wrap = document.querySelector('#detailView .goal-tools, #detailBody .goal-tools');
  if (!wrap) return;
  if (document.getElementById('inlineStepRow')) return;          // 已展开就别重开
  // 入口按钮先隐藏，整个"添加"面板展开（boss A：手动输入 + JSON 导入收进同一卡片）
  const btn = document.getElementById('goalAddBtn');
  if (btn) btn.style.display = 'none';
  const row = document.createElement('div');
  row.className = 'goal-item goal-add-row goal-panel';
  row.id = 'inlineStepRow';
  row.innerHTML = `
    <div class="gap-input-line">
      <span class="g-play">${svgIcon('plus',13,2.2)}</span>
      <input class="g-input" id="inlineStepInput" placeholder="步骤名（按回车提交）" autocomplete="off" />
      <button class="g-submit" id="inlineStepSubmit">添加</button>
      <button class="g-cancel" id="inlineStepCancel">取消</button>
    </div>
    <div class="gap-json-entry" id="gapJsonEntry">
      ${svgIcon('code',12,1.8)} 或粘贴 JSON 批量导入步骤
    </div>
  `;
  wrap.parentNode.insertBefore(row, wrap);
  const inp = document.getElementById('inlineStepInput');
  inp.focus();
  inp.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); submitInlineStep(taskUuid); }
    else if (e.key === 'Escape') { cancelInlineStep(); }
  });
  // boss：JSON 入口改用 addEventListener（之前内联 onclick 串行 cancel+open 在某些时机异常
  // 导致"点了跟取消一样"）。先 openJsonImport 再 cancelInlineStep，关闭 inline 面板不挡弹窗
  const entry = row.querySelector('.gap-json-entry');
  if (entry) entry.addEventListener('click', () => {
    try { openJsonImport(taskUuid); } catch (e) { console.error('openJsonImport failed:', e); }
    cancelInlineStep();
  });
  document.getElementById('inlineStepSubmit').onclick = () => submitInlineStep(taskUuid);
  document.getElementById('inlineStepCancel').onclick = cancelInlineStep;
};
window.submitInlineStep = async function(taskUuid) {
  const inp = document.getElementById('inlineStepInput');
  if (!inp) return;
  const title = inp.value.trim();
  if (!title) { inp.focus(); return; }
  const subBtn = document.getElementById('inlineStepSubmit');
  if (subBtn) subBtn.disabled = true;
  try {
    await call('add_step', { taskUuid, title });
    await openDetail(taskUuid);
  } catch (e) {
    alert('添加失败：' + (e.message || e));
  } finally {
    if (subBtn) subBtn.disabled = false;
  }
};
window.cancelInlineStep = function() {
  const row = document.getElementById('inlineStepRow');
  if (row) row.remove();
  const btn = document.getElementById('goalAddBtn');
  if (btn) btn.style.display = '';
};
// 兼容旧入口（如有外部 / 调试引用），保留但走新流程
window.addStep = function(taskUuid) { showInlineAddStep(taskUuid); };
window._addStepLegacy = function(taskUuid) {                                  // 真·降级 prompt（仅 fallback）
  const title = prompt('步骤名：');
  if (!title) return;
  return call('add_step', { taskUuid, title }).then(() => { openDetail(taskUuid); render(); });
};

// 添加 JSON（外部 AI 拆解导入）：详情卡点「添加 JSON」→ 弹窗里粘贴外部 AI 生成的 JSON，
// 示例可一键复制、发给任意免费 AI 照着拆，粘回来后解析成步骤批量添加（支持 attr）。不再内置 AI。
let jsonImportTaskUuid = null;
function openJsonImport(taskUuid) {
  jsonImportTaskUuid = taskUuid;
  const area = document.getElementById('jsonArea');
  // 默认填一份示意模板：次数默认 5，与「次数任务默认次数」保持一致
  area.value = JSON.stringify({
    steps: [
      { title: '步骤一', attr_label: '次数', attr_value: '5 次' },
      { title: '步骤二' }
    ]
  }, null, 2);
  const hint = document.getElementById('jsonHint');
  hint.textContent = '';
  hint.className = 'hint';
  document.getElementById('jsonOverlay').style.display = '';
  setTimeout(() => area.focus(), 50);
}
function closeJsonImport() { document.getElementById('jsonOverlay').style.display = 'none'; }
document.querySelectorAll('[data-close-overlay="jsonOverlay"]').forEach(b => b.addEventListener('click', closeJsonImport));

// 解析步骤 JSON（容错语义对齐手机端 parseStepsJson）：
// 顶层 title 可省（桌面端只往已有任务加步骤）；steps 必须是数组；
// 步骤缺 title / 空 title 的丢弃；attr 缺省为空串
function parseJsonImport(raw) {
  let obj;
  try { obj = JSON.parse(raw); } catch (e) { return null; }
  if (!obj || typeof obj !== 'object' || !Array.isArray(obj.steps)) return null;
  const steps = [];
  for (const s of obj.steps) {
    if (!s || typeof s !== 'object') continue;
    const title = String(s.title == null ? '' : s.title).trim();
    if (!title) continue;
    steps.push({
      title,
      attr_label: s.attr_label == null ? '' : String(s.attr_label),
      attr_value: s.attr_value == null ? '' : String(s.attr_value)
    });
  }
  return { title: obj.title == null ? null : String(obj.title), steps };
}

document.getElementById('jsonApply').addEventListener('click', async () => {
  const raw = document.getElementById('jsonArea').value;
  const hint = document.getElementById('jsonHint');
  const taskUuid = jsonImportTaskUuid;
  if (!taskUuid) return;
  const parsed = parseJsonImport(raw);
  if (!parsed || parsed.steps.length === 0) {
    hint.textContent = 'JSON 格式不对，或里面没有可用步骤 —— 点「复制示例」对照格式';
    hint.className = 'hint json-err';
    return;
  }
  const applyBtn = document.getElementById('jsonApply');
  applyBtn.disabled = true;
  try {
    await call('import_steps', { taskUuid, steps: parsed.steps });
    closeJsonImport();
    openDetail(taskUuid);
    render();
  } catch (e) {
    hint.textContent = '添加失败：' + e.message;
    hint.className = 'hint json-err';
  } finally {
    applyBtn.disabled = false;
  }
});

document.getElementById('jsonCopyExample').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(document.getElementById('jsonExampleText').textContent);
    const btn = document.getElementById('jsonCopyExample');
    btn.textContent = '已复制 ✓';
    setTimeout(() => { btn.textContent = '复制示例'; }, 1600);
  } catch (e) {
    alert('复制失败，请手动选中示例文字复制');
  }
});

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
  // 次数行：默认态（"默认 N 次 · 修改"），点修改才出输入框
  document.getElementById('addCountText').textContent = settings.count_default;
  document.getElementById('addCountDefaultRow').classList.remove('hidden');
  document.getElementById('addCountEditRow').classList.add('hidden');
  document.querySelectorAll('.cat-btn').forEach(b => b.classList.toggle('active', false));
  document.querySelectorAll('.ddl-btn').forEach(b => b.classList.toggle('active', b.dataset.ddl === 'none'));
  document.querySelectorAll('.prio-btn').forEach(b => b.classList.toggle('active', b.dataset.prio === 'medium'));
  document.getElementById('addDdlCustom').classList.add('hidden');
  document.getElementById('addCatHint').textContent = '请选择一个分类';
  document.getElementById('addSubmit').disabled = true;
  addOverlay.style.display = '';
  setTimeout(() => document.getElementById('addTitle').focus(), 50);
}
function closeAdd() { addOverlay.style.display = 'none'; }
document.getElementById('fabAdd').addEventListener('click', openAdd);
document.querySelectorAll('[data-close-overlay="addOverlay"]').forEach(b => b.addEventListener('click', closeAdd));

// 次数"默认 N 次 · 修改"：点修改展开输入，点确定写回本次次数
document.getElementById('addCountEditBtn').addEventListener('click', () => {
  document.getElementById('addCount').value = draftCount;
  document.getElementById('addCountDefaultRow').classList.add('hidden');
  document.getElementById('addCountEditRow').classList.remove('hidden');
  document.getElementById('addCount').focus();
  document.getElementById('addCount').select();
});
document.getElementById('addCountOkBtn').addEventListener('click', () => {
  const v = Math.max(1, parseInt(document.getElementById('addCount').value, 10) || 1);
  draftCount = v;
  document.getElementById('addCountText').textContent = v;
  document.getElementById('addCountEditRow').classList.add('hidden');
  document.getElementById('addCountDefaultRow').classList.remove('hidden');
});

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
    // 选「自定义」时展开数字输入，选其他收起
    const custom = document.getElementById('addDdlCustom');
    if (custom) custom.classList.toggle('hidden', b.dataset.ddl !== 'custom');
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
  // 自定义时长：把"数值 + 单位"换算成毫秒传给后端（custom:<ms>）
  let deadlineKey = draftCat === 'time-limited' ? draftDdl : 'none';
  if (deadlineKey === 'custom') {
    const num = Math.max(1, parseInt(document.getElementById('ddlCustomNum').value, 10) || 0);
    if (num <= 0) { alert('请填写自定义时长'); return; }
    const unit = document.getElementById('ddlCustomUnit').value;
    const factor = unit === 'day' ? 86400000 : unit === 'hour' ? 3600000 : 60000;
    deadlineKey = 'custom:' + (num * factor);
  }
  await call('add_task', {
    title, category: draftCat,
    deadlineKey,
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
  if (!el) return;                       // UI 改版后某些旧控件已删除，找不到就跳过，不能中断整份脚本
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
// 「每日任务自动刷新」开关已撤（每日任务本就该每天刷新，UI 不再暴露，旧 key 保留兼容）
bindSetting('setNightNotify', 'night_notify');

// =============== 设置项：默认·修改两态（数字类） ===============
// 默认态显示「当前值 + 单位 + 修改」；点修改切到编辑态显示 input + 确定/取消；点确定触发 input change → bindSetting 写回 settings + 切回默认态
document.querySelectorAll('[data-toggle-edit]').forEach(btn => {
  btn.addEventListener('click', () => {
    const id = btn.dataset.toggleEdit;
    const dsp = document.getElementById('dsp_' + id);
    const edt = document.getElementById('edt_' + id);
    if (!dsp || !edt) return;
    const isEdit = getComputedStyle(edt).display !== 'none';
    if (isEdit) { edt.style.display = 'none'; dsp.style.display = ''; }   // 取消 → 默认
    else {                                                                  // 默认 → 编辑
      const inp = document.getElementById(id);
      const cur = document.getElementById('cur_' + id);
      if (inp && cur) inp.value = cur.textContent;
      dsp.style.display = 'none'; edt.style.display = '';
      setTimeout(() => inp && inp.focus(), 0);
    }
  });
});
document.querySelectorAll('[data-save-edit]').forEach(btn => {
  btn.addEventListener('click', () => {
    const id = btn.dataset.saveEdit;
    const inp = document.getElementById(id);
    if (!inp) return;
    inp.dispatchEvent(new Event('change', { bubbles: true }));   // 复用 bindSetting 的 change 逻辑
    const dsp = document.getElementById('dsp_' + id);
    const edt = document.getElementById('edt_' + id);
    if (dsp && edt) { edt.style.display = 'none'; dsp.style.display = ''; }
  });
});

// =============== 个人信息 modal ===============
// 头像用 emoji 或字符当占位（先用等级 SVG，后面可以换 Gravatar / 文件上传）
const AVATAR_GLYPHS = ['✦','✧','☀','☾','⚡','❄','♨','⚓','⚝','❀','✿','❁','✪','✯','✰','✸','✱','✲'];
let avatarIdx = 0;
function pickAvatarGlyph() { return AVATAR_GLYPHS[avatarIdx % AVATAR_GLYPHS.length]; }

function openProfileModal() {
  const lv = level || levelOf(points);
  const next = nextLevelOf(points);
  document.getElementById('profileLevelIcon').innerHTML = svgIcon(lv.ico || 'lv1', 36, 1);
  document.getElementById('profileLevelName').textContent = lv.name;
  // 同步处理 title 空串隐藏（与上方 renderLevelCard 一致）
  const _pt2 = document.getElementById('profileLevelTitle');
  if (_pt2) { _pt2.textContent = lv.title || ''; _pt2.style.display = lv.title ? '' : 'none'; }
  document.getElementById('profilePointsNum').textContent = points;
  // 经验条
  let pct = 100;
  if (next) {
    pct = Math.min(100, Math.max(0, ((points - lv.min) / Math.max(1, lv.max - lv.min)) * 100));
    // 0 分时空态给引导文案；>0 时显示距下一级具体分数
    document.getElementById('profileExpHint').textContent = points <= 0
      ? '开始你的第一项任务吧'
      : '距下一级（' + next.name + '）还差 ' + Math.max(0, lv.max - points) + ' 分';
  } else {
    pct = 100;
    document.getElementById('profileExpHint').textContent = '已至巅峰，满级成就达成';
  }
  document.getElementById('profileExpFill').style.width = pct + '%';
  // 配对
  document.getElementById('profilePairStatus').textContent =
    settings.pairing ? ('已配对：' + settings.pairing.url) : '未配对';
  // 昵称
  document.getElementById('profileNickname').value = settings.nickname || '历练者';
  // 头像：先用用户自定义图，没有再回退字符
  avatarIdx = (settings.avatar_idx != null) ? settings.avatar_idx : 0;
  renderProfileAvatar();
  document.getElementById('profileOverlay').style.display = '';
}
function closeProfileModal() { document.getElementById('profileOverlay').style.display = 'none'; }
window.openProfileModal = openProfileModal;
window.closeProfileModal = closeProfileModal;
document.querySelectorAll('[data-close-overlay="profileOverlay"]').forEach(b => b.addEventListener('click', closeProfileModal));

// 昵称保存：回车或失焦
const _profileNick = document.getElementById('profileNickname');
function commitNickname() {
  const v = (_profileNick.value || '').trim().slice(0, 12);
  if (!v) { _profileNick.value = settings.nickname || '历练者'; return; }
  settings.nickname = v;
  saveSettings();
  call('set_setting', { key: 'nickname', value: v }).catch(() => {});
  render();
}
_profileNick.addEventListener('change', commitNickname);
_profileNick.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); _profileNick.blur(); } });
// 昵称"保存"按钮：触发 blur → change → commit
const _profileNickSave = document.getElementById('profileNickSave');
if (_profileNickSave) _profileNickSave.addEventListener('click', () => { if (_profileNick) _profileNick.blur(); });

// ===== 自定义软件风 tooltip（替代系统黑底白字原生 title） =====
// boss 反馈：系统 tooltip 黑底白字太丑 + 经常被旁边挡住。改为浅底金边深字的软件风格。
// 排除 .nav-item（侧栏已有 .nav-tip 标签，避免双重显示）。
// 排除 input/textarea（避免截获用户输入焦点）。
(function installSoftTip() {
  let el = document.getElementById('_softTip');
  if (!el) { el = document.createElement('div'); el.id = '_softTip'; document.body.appendChild(el); }
  function show(text, rect) {
    el.textContent = text;
    el.style.display = 'block';
    el.style.left = (rect.left + rect.width / 2) + 'px';
    el.style.top  = (rect.bottom + 8) + 'px';
    requestAnimationFrame(() => {
      const er = el.getBoundingClientRect();
      if (er.bottom > window.innerHeight - 6) el.style.top = (rect.top - er.height - 8) + 'px';
      if (er.right > window.innerWidth - 6)   el.style.left = (window.innerWidth - er.width - 6) + 'px';
      if (er.left < 6) el.style.left = '6px';
    });
  }
  function hide() { el.style.display = 'none'; }
  document.addEventListener('mouseover', function(e) {
    const t = e.target.closest('[title]');
    if (!t || t.classList.contains('nav-item') || t.tagName === 'INPUT' || t.tagName === 'TEXTAREA') { hide(); return; }
    if (t.dataset.stHandled !== '1') {
      t.dataset.stOrig = t.getAttribute('title') || '';
      t.removeAttribute('title');
      t.dataset.stHandled = '1';
    }
    if (t.dataset.stOrig) show(t.dataset.stOrig, t.getBoundingClientRect());
  });
  document.addEventListener('mouseout', function(e) {
    const t = e.target.closest('[data-st-handled]');
    if (t) {
      t.setAttribute('title', t.dataset.stOrig || '');
      delete t.dataset.stHandled;
      delete t.dataset.stOrig;
    }
    hide();
  });
})();

// 头像：自定义图（base64）优先；没有则用字符。字符模式下"换"按钮切下一个字符；
// 有图时"换"按钮切回字符模式（清除图片 + 回到当前 avatarIdx 的字符）。
function renderProfileAvatar() {
  const av = document.getElementById('profileAvatar');
  if (!av) return;
  if (settings.avatar_img) {
    // boss #20 fix：彻底改用 background-image + cover 方案，避免 img 在 flex 容器内对齐问题。
    // 容器 border-radius:50% + overflow:hidden + background-size:cover 永远完美裁圆，不会溢出。
    av.textContent = '';
    av.style.backgroundImage = "url(\"" + settings.avatar_img.replace(/"/g, '%22') + "\")";
    av.style.backgroundSize = 'cover';
    av.style.backgroundPosition = 'center';
    av.style.backgroundRepeat = 'no-repeat';
  } else {
    av.style.backgroundImage = '';
    av.textContent = pickAvatarGlyph();
  }
}
// 字符切换
document.getElementById('profileAvatarEdit').addEventListener('click', () => {
  if (settings.avatar_img) {
    delete settings.avatar_img;     // 有图时按"换"→ 退回字符模式（保留 avatarIdx）
  } else {
    avatarIdx = (avatarIdx + 1) % AVATAR_GLYPHS.length;
    settings.avatar_idx = avatarIdx;
  }
  saveSettings();
  renderProfileAvatar();
});
// 头像本身点击：换字符（兼容旧行为）
document.getElementById('profileAvatar').addEventListener('click', () => {
  if (settings.avatar_img) return;        // 有图时点击不切（避免误清除）
  avatarIdx = (avatarIdx + 1) % AVATAR_GLYPHS.length;
  settings.avatar_idx = avatarIdx;
  saveSettings();
  renderProfileAvatar();
});
// 上传自己的图片：选文件 → 缩放到 96×96 → base64 存 settings.avatar_img
const _profileAvatarFile = document.getElementById('profileAvatarFile');
document.getElementById('profileAvatarUpload').addEventListener('click', () => _profileAvatarFile && _profileAvatarFile.click());
// boss #20：上传图片走裁剪器（参考社区软件头像裁剪），用户可调圆形区域再保存
_profileAvatarFile.addEventListener('change', e => {
  const f = e.target.files && e.target.files[0];
  if (!f) return;
  openCropper(f);
  e.target.value = '';
});

// ===== 头像裁剪器 v4.13.2 专业版（参考 Instagram/微信/Discord） =====
let _cropState = null, _cropDrag = null;

function openCropper(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = ev => {
    const img = new Image();
    img.onload = () => {
      // 初始 scale：让图片短边 = 圆形取景框直径 280px（保证初始覆盖圆）
      const s0 = 280 / Math.min(img.width, img.height);
      _cropState = { img, scale: s0, x: 0, y: 0 };
      renderCrop();
      document.getElementById('cropOverlay').classList.add('show');
    };
    img.src = ev.target.result;
  };
  reader.readAsDataURL(file);
}

function renderCrop() {
  const c = _cropState; if (!c || !c.img) return;
  const el = document.getElementById('cropImg');
  const wrap = document.getElementById('cropImgWrap');
  if (!el || !wrap) return;
  const w = c.img.width * c.scale;
  const h = c.img.height * c.scale;
  el.style.width = w + 'px';
  el.style.height = h + 'px';
  // 双层 div wrap：img 居中，wrap 负责位移（去旋转：保留简单的平移操作）
  wrap.style.transform = `translate(-50%, -50%) translate(${c.x}px, ${c.y}px)`;
}

function commitCrop() {
  const c = _cropState; if (!c || !c.img) return;
  const size = 128;
  const cv = document.createElement('canvas');
  cv.width = cv.height = size;
  const ctx = cv.getContext('2d');
  // 截图：圆形取景框 stage 280px → canvas 128px（缩小 0.457）
  // canvas 中心 = stage (140,140)；图片中心在 stage (140+c.x, 140+c.y)
  // 缩放 stage 坐标 → canvas 坐标用 ctx.scale
  const ratio = size / 280;
  ctx.translate(size/2, size/2);
  ctx.scale(ratio, ratio);
  // 在 stage 坐标下画图（wrap 中心 = stage 140,140 + 偏移 x,y；img 在 wrap 内居中 = 中心）
  const w = c.img.width * c.scale;
  const h = c.img.height * c.scale;
  ctx.drawImage(c.img, c.x - w/2, c.y - h/2, w, h);
  settings.avatar_img = cv.toDataURL('image/png');
  saveSettings();
  renderProfileAvatar();
  closeCrop();
}

function closeCrop() {
  const ov = document.getElementById('cropOverlay'); if (ov) ov.classList.remove('show');
  _cropState = null;
  _cropDrag = null;
}

// 拖拽平移（在 stage frame 上 mousedown）
const _cropFrame = document.getElementById('cropFrame');
if (_cropFrame) _cropFrame.addEventListener('mousedown', e => {
  if (!_cropState) return;
  e.preventDefault();
  _cropDrag = { mx: e.clientX, my: e.clientY, sx: _cropState.x, sy: _cropState.y };
});
window.addEventListener('mousemove', e => {
  if (!_cropDrag || !_cropState) return;
  _cropState.x = _cropDrag.sx + (e.clientX - _cropDrag.mx);
  _cropState.y = _cropDrag.sy + (e.clientY - _cropDrag.my);
  renderCrop();
});
window.addEventListener('mouseup', () => _cropDrag = null);

// 双击放大 1.5×
if (_cropFrame) _cropFrame.addEventListener('dblclick', e => {
  if (!_cropState || !_cropState.img) return;
  e.preventDefault();
  _cropState.scale *= 1.5;
  renderCrop();
});

// 滚轮缩放（上滑放大、下滑缩小，步进 1.1×）
if (_cropFrame) _cropFrame.addEventListener('wheel', e => {
  if (!_cropState || !_cropState.img) return;
  e.preventDefault();
  const factor = e.deltaY < 0 ? 1.1 : 1/1.1;
  _cropState.scale = Math.max(0.3, Math.min(4, _cropState.scale * factor));
  renderCrop();
}, { passive: false });

// 比例切换按钮（自由 / 1:1 / 4:3）：当前默认 1:1 圆形，比例按钮做高亮即可
document.querySelectorAll('.crop-btn.ratio').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.crop-btn.ratio').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    // 比例效果在 CSS 中体现（1:1=圆形；4:3=椭圆）—— 简化版：1:1 时 frame 100% 圆，4:3 时切换为椭圆遮罩
    const ratio = btn.dataset.ratio;
    const frame = document.getElementById('cropFrame');
    const wrap = document.querySelector('.crop-stage-wrap');
    if (ratio === '1:1') { frame.style.borderRadius = '50%'; wrap.style.borderRadius = '50%'; }
    else if (ratio === '4:3') { frame.style.borderRadius = '50%'; wrap.style.borderRadius = '37%'; }
    else { frame.style.borderRadius = '50%'; wrap.style.borderRadius = '50%'; }
  });
});

// 操作按钮
const _cropCancel = document.getElementById('cropCancel');
if (_cropCancel) _cropCancel.addEventListener('click', closeCrop);
const _cropConfirm = document.getElementById('cropConfirm');
if (_cropConfirm) _cropConfirm.addEventListener('click', commitCrop);

// ESC 取消 / Enter 确定（专业软件常见快捷键）
window.addEventListener('keydown', e => {
  if (!_cropState) return;
  if (e.key === 'Escape') closeCrop();
  else if (e.key === 'Enter') commitCrop();
});

// 跳转到"设置"页（连接手机端）
document.getElementById('profileGoSettings').addEventListener('click', () => {
  closeProfileModal();
  document.getElementById('settingsBtn').click();
});
// 「进度条样式选择器」已撤（统一条形），旧 key 保留兼容、不再绑定 UI

document.getElementById('setPairBtn').addEventListener('click', async () => {
  const url = document.getElementById('setPairInput').value.trim();
  if (!url) return;
  const full = url.startsWith('http') ? url : 'http://' + url;
  try {
    await call('connect_server', { url: full });
    settings.pairing = { url: full, deviceId: '' };
    saveSettings();
    persistSettingsServer();
    document.getElementById('setPairingStatus').textContent = '已配对：' + full;
  } catch (e) { alert('连接失败：' + e.message); }
});

// mDNS 自动发现手机
document.getElementById('setScanBtn').addEventListener('click', async () => {
  const wrap = document.getElementById('setDeviceListWrap');
  const box = document.getElementById('setDeviceList');
  wrap.style.display = '';
  box.innerHTML = '正在扫描…';
  let list = [];
  try { list = await call('discover_devices', { timeoutMs: 3000 }); } catch (e) { box.innerHTML = '扫描失败：' + e.message; return; }
  if (!list.length) { box.innerHTML = '未发现手机端服务。<br>请确认手机「任务栏」已打开同步（设置-同步服务器），且与电脑在同一 WiFi。'; return; }
  box.innerHTML = '';
  list.forEach(d => {
    const row = document.createElement('div');
    row.style.display = 'flex';
    row.style.alignItems = 'center';
    row.style.gap = '6px';
    const name = document.createElement('span');
    name.textContent = d.name.split('.').slice(0, 2).join('.') + '  ' + d.addr + ':' + d.port;
    name.style.flex = '1';
    const btn = document.createElement('button');
    btn.className = 'small-btn primary';
    btn.textContent = '配对';
    btn.onclick = async () => {
      try {
        await call('connect_server', { url: d.url });
        settings.pairing = { url: d.url, deviceId: '' };
        saveSettings();
        persistSettingsServer();
        box.innerHTML = '✓ 已配对：' + d.url;
        document.getElementById('setPairingStatus').textContent = '已配对：' + d.url;
      } catch (e) { alert('配对失败：' + e.message); }
    };
    row.appendChild(name);
    row.appendChild(btn);
    box.appendChild(row);
  });
});
document.getElementById('setUnpair').addEventListener('click', async () => {
  settings.pairing = null;
  saveSettings();
  await call('disconnect_server', {});
  persistSettingsServer();
});

// 任务完成时的奖励弹窗（原神风 +N 经验 + 任务名）已在 completeTask 中通过 showBless 触发，无需额外回放列表
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
  if (isTauriEnv) call('set_window_size', { w: 360, h: 88 }).catch(()=>{});
});

// 等级徽章点击 → 个人信息（昵称 / 头像 / 经验条都在那）
document.getElementById('levelBadge').addEventListener('click', () => {
  openProfileModal();
});
// 等级卡的「?」按钮已删除：经验条合并到个人信息 modal 里，避免多余入口

// v4.10 顶栏三键（最小化 / 最大化 / 隐藏），关闭只隐藏不退出，靠托盘图标唤回
const _winMin = document.getElementById('winMin');
const _winMax = document.getElementById('winMax');
const _winClose = document.getElementById('winClose');
if (_winMin) _winMin.addEventListener('click', () => isTauriEnv && call('win_minimize').catch(()=>{}));
if (_winMax) _winMax.addEventListener('click', () => isTauriEnv && call('win_toggle_maximize').catch(()=>{}));
if (_winClose) _winClose.addEventListener('click', () => isTauriEnv && call('win_hide').catch(()=>{}));

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
  if (isTauriEnv) call('set_window_size', { w: 1100, h: 720 }).catch(()=>{});
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
  // 启动时把昵称 / 头像索引从后端拉到本地，再调一次 render 让 dashboard 显示
  hydrateUserProfile().then(() => render()).catch(() => render());
  // 首次启动 seed 5 条示例任务（后端去重：seed_v4 标记 + 已有任务时跳过）
  call('seed_default_tasks', {}).then(r => {
    if (r && r.seeded) render();
  }).catch(() => {});
  setTimeout(render, 250);
  setTimeout(render, 1000);
  // 设置平时只在改动时推后端，启动补推一次，
  // 避免「改过设置但那次请求失败」导致后端一直用默认值
  persistSettingsServer().catch(() => {});
  setInterval(render, 15000);
  setInterval(checkEmergency, 60000);
  checkNightNotify();                    // 启动即查一次（22 点后开机也能补提醒）
  setInterval(checkNightNotify, 60000);
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', startApp);
} else {
  startApp();
}

// =============== 22:00 未完成提醒 ===============
// 判定交给后端（按「今天是否已提醒过」去重），前端只负责弹窗。
// 旧实现要求 now.getMinutes() === 0 —— 22:00 那一分钟若没运行（没开机 / 休眠）
// 就永远不会提醒；且用 alert 会阻塞界面。改为已过 22 点 + 当日未提醒即可触发。
async function checkNightNotify() {
  try {
    const r = await call('check_night_notify', {});
    if (r && r.pending) showNightNotify(r.titles || []);
  } catch (e) { /* 提醒失败不影响主流程 */ }
}
function showNightNotify(titles) {
  const shown = titles.slice(0, 8);
  const items = shown.map(t =>
    '<div class="night-list-item"><span class="dot"></span><span>' + esc(t) + '</span></div>'
  ).join('');
  const more = titles.length > shown.length
    ? '<div class="night-empty">…等共 ' + titles.length + ' 项</div>' : '';
  document.getElementById('nightMsg').innerHTML =
    '还有 <b>' + titles.length + '</b> 项没完成：' +
    '<div class="night-list">' + items + more + '</div>';
  document.getElementById('nightOverlay').style.display = '';
}
document.getElementById('nightClose').addEventListener('click', () => {
  document.getElementById('nightOverlay').style.display = 'none';
  // 告知后端今天已提醒，避免一晚上反复弹
  call('dismiss_night_notify', {}).catch(() => {});
});
