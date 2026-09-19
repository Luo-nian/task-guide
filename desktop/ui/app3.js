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
let nav = 'today';   // v5.14d：默认今日（boss 反馈：打开客户端默认看到今日待办）
// v5.15.18 K1（boss）：追踪任务是否"整页置顶"。
//   逻辑与手机端一致 —— 点追踪时**不当场**置顶（任务原位 + 追踪特效），
//   等**下次进入该页面**（切回今日待办/总览）才把它挪到最上方，
//   否则用户点完追踪键任务突然跳走，会以为任务不见了。
let _pinTracking = true;
let tasks = [];
let archive = [];
let points = 0;
let trackCards = [];   // v5.12：当前追踪任务 + 当前步骤（widget 专用数据源）
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
  daily_refresh_time: '00:00',     // v5.15.31：每日任务刷新时间 HH:MM（boss：设置里要能改）
  night_notify: true,              // 22:00 未完成弹窗
  pairing: null,
  nickname: '',                    // v5.15.29 L5：**用户自己编辑过**的昵称（空 = 没编辑过）
  nickname_custom: ''              // v5.15.29 L5：'1' = 用户编辑过（用 nickname）；'' = 跟随当前等级名称
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

// =============== 10 段等级（v5.13d 拉长升级曲线：boss 反馈 5-6 天就登顶太快） ===============
// 曲线设计：每日 ~60-100 分 → 前 5 级 2-3 天体验晋升爽感，lv5 后大幅拉长，40+ 天才登顶
// max = 下一级门槛（渲染进度条用）；末级 max 不参与（nextLevelOf 为 null）
// ico 指向 ICONS 里的 SVG 图标名（不再用 emoji / 数字，避免系统字体渲染成彩色符号）
const LEVELS = [
  { lv:1, name:'历练学徒',  title:'',                  ico:'lv1', min:0,    max:50   },
  { lv:2, name:'风华游侠',  title:'',                  ico:'lv2', min:50,   max:130  },
  { lv:3, name:'破浪骑士',  title:'',                  ico:'lv3', min:130,  max:250  },
  { lv:4, name:'群星行者',  title:'',                  ico:'lv4', min:250,  max:420  },
  { lv:5, name:'传奇勇者',  title:'',                  ico:'lv5', min:420,  max:660  },
  { lv:6, name:'苍穹守护者',title:'',                  ico:'lv6', min:660,  max:1000 },
  { lv:7, name:'深渊征服者',title:'',                  ico:'lv7', min:1000, max:1500 },
  { lv:8, name:'星辰霸主',  title:'',                  ico:'lv8', min:1500, max:2200 },
  { lv:9, name:'天命传奇',  title:'',                  ico:'lv9', min:2200, max:3200 },
  { lv:10,name:'寰宇传说',  title:'',                  ico:'lv10', min:3200,max:99999}
];

// =============== v5.15.29 L1 · 等级徽章（第八版定案 · 不要外壳，只放本体） ===============
// 说明：徽章只有本体 —— 没有外圈金环 / 齿边环 / 同心圆 / 角饰 / 顶冠 / 桂冠 / 羽翼 / 绶带 / 托盘。
//   全部在 -30~30 的坐标系里绘制，两色金属 + 深色描边，直接落在深色卡面上。
//   {M}主金 {D}暗金 {H}高光 {G}中金 {S}银(水/云专属) {O}描边
const LEVEL_EMBLEM = {
  1: // 历练学徒 · 嫩芽破土
    '<path d="M-20,20 Q0,13 20,20 L20,26 L-20,26 Z" fill="{D}" stroke="{O}" stroke-width="1"/>' +
    '<path d="M0,21 Q-2,10 0,-2" fill="none" stroke="{D}" stroke-width="4.2" stroke-linecap="round"/>' +
    '<path d="M0,-2 Q-15,-9 -19,-1 Q-10,4 0,3 Z" fill="{M}" stroke="{O}" stroke-width="1.1"/>' +
    '<path d="M0,-5 Q13,-14 19,-5 Q10,1 0,1 Z" fill="{M}" stroke="{O}" stroke-width="1.1"/>' +
    '<path d="M-1,-2 Q-11,-7 -16,-3" fill="none" stroke="{D}" stroke-width="1" opacity=".8"/>' +
    '<path d="M1,-5 Q10,-11 16,-6" fill="none" stroke="{D}" stroke-width="1" opacity=".8"/>',
  2: // 风华游侠 · 弓与箭
    '<path d="M6,-21 Q-18,0 6,21 Q-11,0 6,-21 Z" fill="{M}" stroke="{O}" stroke-width="1.1"/>' +
    '<path d="M6,-21 L6,21" fill="none" stroke="{H}" stroke-width="1.6"/>' +
    '<path d="M-16,0 L17,0" fill="none" stroke="{M}" stroke-width="3.4" stroke-linecap="round"/>' +
    '<path d="M17,-5.5 L27,0 L17,5.5 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>' +
    '<path d="M-16,-1 L-25,-7.5 L-11,-3.4 Z" fill="{D}"/>' +
    '<path d="M-16,1 L-25,7.5 L-11,3.4 Z" fill="{D}"/>',
  3: // 破浪骑士 · 盾与波浪（浪用银系）
    '<path d="M0,-23 L16,-17 L16,1 Q16,16 0,25 Q-16,16 -16,1 L-16,-17 Z" fill="{M}" stroke="{O}" stroke-width="1.3"/>' +
    '<path d="M0,-23 L0,25" stroke="{D}" stroke-width="1.3" opacity=".7"/>' +
    '<path d="M-11,0 Q-5.5,-6 0,0 Q5.5,6 11,0" fill="none" stroke="{S}" stroke-width="3" stroke-linecap="round"/>' +
    '<path d="M-11,9 Q-5.5,3 0,9 Q5.5,15 11,9" fill="none" stroke="{S}" stroke-width="3" stroke-linecap="round" opacity=".8"/>',
  4: // 群星行者 · 交叉星轨 + 四芒星
    '<path d="M-27,15 Q0,-17 27,15" fill="none" stroke="{M}" stroke-width="2.6" stroke-linecap="round"/>' +
    '<path d="M-27,-15 Q0,17 27,-15" fill="none" stroke="{M}" stroke-width="2.6" stroke-linecap="round" opacity=".7"/>' +
    '<circle cx="-18" cy="1" r="2.4" fill="{H}"/><circle cx="18" cy="1" r="2.4" fill="{H}"/>' +
    '<path d="M0,-17 L4.8,-4.8 L17,0 L4.8,4.8 L0,17 L-4.8,4.8 L-17,0 L-4.8,-4.8 Z" fill="{H}" stroke="{O}" stroke-width="1.1"/>',
  5: // 传奇勇者 · 交叉双剑
    (function () {
      const sw = '<path d="M0,-24 L4.4,-14 L4.4,3 L-4.4,3 L-4.4,-14 Z" fill="{M}" stroke="{O}" stroke-width="1"/>' +
                 '<rect x="-11" y="3" width="22" height="4.4" rx="2.2" fill="{G}" stroke="{O}" stroke-width=".8"/>' +
                 '<rect x="-2.6" y="7.4" width="5.2" height="8.4" rx="2" fill="{D}" stroke="{O}" stroke-width=".7"/>' +
                 '<circle cx="0" cy="17.4" r="3.2" fill="{H}" stroke="{O}" stroke-width=".8"/>';
      return '<g transform="translate(-9,11) rotate(33)">' + sw + '</g>' +
             '<g transform="translate(9,11) rotate(-33)">' + sw + '</g>' +
             '<path d="M0,-4 L4,2 L0,8 L-4,2 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>';
    })(),
  6: // 苍穹守护者 · 双翼之盾
    '<path d="M-5,3 Q-14,-5 -26,-3 Q-18,1 -11,6 Q-19,4 -27,8 Q-19,10 -9,12 Q-19,14 -25,19 Q-15,16 -6,13 Z" fill="{M}" stroke="{O}" stroke-width="1"/>' +
    '<path d="M5,3 Q14,-5 26,-3 Q18,1 11,6 Q19,4 27,8 Q19,10 9,12 Q19,14 25,19 Q15,16 6,13 Z" fill="{M}" stroke="{O}" stroke-width="1"/>' +
    '<path d="M0,-24 L16,-18 L16,0 Q16,15 0,23 Q-16,15 -16,0 L-16,-18 Z" fill="{G}" stroke="{O}" stroke-width="1.3"/>' +
    '<path d="M0,-18 L10,-14 L10,0 Q10,10 0,16 Q-10,10 -10,0 L-10,-14 Z" fill="none" stroke="{H}" stroke-width="1.2" opacity=".55"/>' +
    '<path d="M0,-9 L6,0 L0,9 L-6,0 Z" fill="{H}" stroke="{O}" stroke-width="1"/>',
  7: // 深渊征服者 · 漩涡 + 三叉戟
    '<path d="M0,-2 A2,2 0 0 1 0,2 A6,6 0 0 1 0,-6 A10,10 0 0 1 0,10 A14,14 0 0 1 0,-14" fill="none" stroke="{M}" stroke-width="3.6" stroke-linecap="round" opacity=".92"/>' +
    '<rect x="-2.6" y="-9" width="5.2" height="34" rx="2.4" fill="{M}" stroke="{O}" stroke-width=".9"/>' +
    '<path d="M0,-26 L3.6,-18 L0,-10 L-3.6,-18 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>' +
    '<path d="M-12,-20 Q-12,-12 -3,-9 M12,-20 Q12,-12 3,-9" fill="none" stroke="{M}" stroke-width="3.4" stroke-linecap="round"/>' +
    '<circle cx="0" cy="26" r="3.4" fill="{H}" stroke="{O}" stroke-width=".8"/>',
  8: // 星辰霸主 · 权杖 + 八芒星 + 双伴星
    '<path d="M0,-25 L5.4,-15 L0,-5 L-5.4,-15 Z" fill="{H}" stroke="{O}" stroke-width=".9"/>' +
    '<path d="M-16,-15 L-5,-15 M16,-15 L5,-15" stroke="{H}" stroke-width="2.4" stroke-linecap="round"/>' +
    '<rect x="-5" y="-8.5" width="10" height="5" rx="2.4" fill="{G}" stroke="{O}" stroke-width=".9"/>' +
    '<path d="M0,-3.5 L0,22" stroke="{M}" stroke-width="4.6" stroke-linecap="round"/>' +
    '<path d="M0,-3.5 L0,22" stroke="{H}" stroke-width="1.5" opacity=".55"/>' +
    '<circle cx="0" cy="26" r="4.2" fill="{D}" stroke="{O}" stroke-width="1"/>' +
    '<path d="M-23,-23 L-19,-19 L-23,-15 L-27,-19 Z" fill="{H}"/>' +
    '<path d="M23,-23 L27,-19 L23,-15 L19,-19 Z" fill="{H}"/>',
  9: // 天命传奇 · 命轮 + 六芒星
    '<circle r="25" fill="none" stroke="{M}" stroke-width="2.6"/>' +
    '<circle r="20" fill="none" stroke="{M}" stroke-width="3" stroke-dasharray="1.4 6.3" opacity=".9"/>' +
    '<path d="M0,-16 L4.3,-6 L15,-8 L7.5,0 L15,8 L4.3,6 L0,16 L-4.3,6 L-15,8 L-7.5,0 L-15,-8 L-4.3,-6 Z" fill="{H}" stroke="{O}" stroke-width="1"/>' +
    '<circle r="3" fill="{G}" stroke="{O}" stroke-width=".8"/>',
  10: // 寰宇传说 · 中心恒星 + 三条轨道 + 卫星
    '<ellipse rx="26" ry="10" fill="none" stroke="{M}" stroke-width="2.2"/>' +
    '<ellipse rx="26" ry="10" fill="none" stroke="{M}" stroke-width="2.2" opacity=".72" transform="rotate(60)"/>' +
    '<ellipse rx="26" ry="10" fill="none" stroke="{M}" stroke-width="2.2" opacity=".5" transform="rotate(120)"/>' +
    '<circle cx="26" cy="0" r="3.4" fill="{H}" stroke="{O}" stroke-width=".9"/>' +
    '<circle cx="-13" cy="22.5" r="2.8" fill="{G}" stroke="{O}" stroke-width=".9"/>' +
    '<circle cx="13" cy="-22.5" r="2.4" fill="{G}" stroke="{O}" stroke-width=".9"/>' +
    '<circle r="12" fill="{H}" opacity=".2"/>' +
    '<circle r="8" fill="#FBEECB" stroke="{G}" stroke-width="1.2"/>'
};

const EMBLEM_PAL = { M: '#E8CB7F', D: '#B8892B', H: '#F7E7BB', G: '#C9A227', S: '#D9DEE6', O: '#0A0A0D' };

function levelEmblemSvg(lvNum) {
  const n = Math.max(1, Math.min(10, lvNum | 0));
  let body = LEVEL_EMBLEM[n] || LEVEL_EMBLEM[1];
  body = body.replace(/\{([MDHGSO])\}/g, function (m, k) { return EMBLEM_PAL[k] || m; });
  return '<svg viewBox="-30 -30 60 60" aria-hidden="true">' + body + '</svg>';
}

// 入场特效：按等级分档（一次性，播完即止；不是常驻动效）
function emblemFxClass(lvNum) {
  const n = lvNum | 0;
  if (n <= 3) return 'dgb-rise';    // 学徒/游侠/骑士：自下升起
  if (n <= 6) return 'dgb-grow';    // 行者/勇者/守护者：由小长大
  if (n <= 8) return 'dgb-spin';    // 征服者/霸主：旋入
  return 'dgb-pop';                 // 传奇/传说：弹出 + 过冲
}

// =============== v5.15.29 L1 · Lv.10 身份卡星空粒子层 ===============
// 参数已按 boss「粒子提速」定稿：漂移 8~12s、明灭 3.4~5.4s、位移 20x27；流星每轮重掷起点与角度
const GREET_PARTICLE_SEED = [
  [6,14,3],[12,32,2],[18,9,4],[23,26,2],[29,16,3],[34,38,2],[40,11,3],[45,29,4],
  [51,18,2],[56,36,3],[62,12,3],[67,30,2],[73,20,4],[78,37,2],[84,14,3],[89,28,2],
  [94,19,3],[9,46,2],[15,58,3],[21,44,4],[27,60,2],[33,50,3],[39,64,2],[45,47,3],
  [52,62,2],[58,49,4],[64,66,2],[70,52,3],[76,68,2],[82,54,3],[88,70,2],[94,57,3],
  [11,76,2],[19,88,3],[26,80,2],[35,92,3],[43,78,2],[54,90,3],[66,82,2],[78,92,3],[90,84,2]
];

function rerollMeteor(el) {
  const top = Math.round(Math.random() * 72);
  const dir = 13 + Math.random() * 25;
  const dx = Math.round(640 + Math.random() * 140);
  const dy = Math.round(Math.tan(dir * Math.PI / 180) * dx);
  el.style.top = top + '%';
  el.style.setProperty('--rot', dir.toFixed(1) + 'deg');
  el.style.setProperty('--dx', dx + 'px');
  el.style.setProperty('--dy', dy + 'px');
}

function renderGreetParticles(lvNum) {
  const card = document.getElementById('dashGreeting');
  if (!card) return;
  const old = document.getElementById('greetParticles');
  if ((lvNum | 0) < 10) { if (old) old.remove(); return; }   // 星空粒子是 Lv.10 专属
  if (old) return;
  const layer = document.createElement('div');
  layer.id = 'greetParticles';
  let html = '';
  for (let i = 0; i < GREET_PARTICLE_SEED.length; i++) {
    const q = GREET_PARTICLE_SEED[i];
    const drift = (8 + (i % 5)).toFixed(1);
    const tw = (3.4 + (i % 5) * 0.5).toFixed(1);
    html += '<div class="gp" style="left:' + q[0] + '%;top:' + q[1] + '%;width:' + q[2] + 'px;height:' + q[2] +
            'px;animation-duration:' + drift + 's,' + tw + 's"></div>';
  }
  html += '<div class="gp-sh" data-sh="1"></div><div class="gp-sh" data-sh="2"></div>';
  layer.innerHTML = html;
  card.insertBefore(layer, card.firstChild);
  const meteors = layer.querySelectorAll('.gp-sh');
  for (let i = 0; i < meteors.length; i++) {
    (function (el) {
      rerollMeteor(el);
      el.addEventListener('animationiteration', function () { rerollMeteor(el); });
    })(meteors[i]);
  }
}

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
  // v5.14g fix：boss 反馈侧边栏今日/追踪图标空白 —— svg 库缺 sun/crosshair（lucide 标准 path）
  sun:      '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>',
  crosshair:'<circle cx="12" cy="12" r="10"/><path d="M22 12h-4M6 12H2M12 6V2M12 22v-4"/>',
  goal:     '<circle cx="12" cy="12" r="8.6"/><circle cx="12" cy="12" r="4.2"/><circle cx="12" cy="12" r="1.1" fill="currentColor" stroke="none"/>',
  // —— 等级角色 ——
  // —— 10 级等级（v5.13i 用 Lucide 专业图标：sprout/compass/sword/sparkles/swords/shield/flame/crown/wand-sparkles/orbit）——
  // lv1 历练学徒：萌芽（开始成长）
  lv1: '<path d="M14 9.536V7a4 4 0 0 1 4-4h1.5a.5.5 0 0 1 .5.5V5a4 4 0 0 1-4 4a4 4 0 0 0-4 4c0 2 1 3 1 5a5 5 0 0 1-1 3M4 9a5 5 0 0 1 8 4a5 5 0 0 1-8-4m1 12h14"/>',
  // v5.14h.7：lv2/3/4/5/7/8/9 图标换简单大轮廓（boss 反馈原 lucide 复杂细线小尺寸糊）
  lv2: '<path d="M20.24 12.24a6 6 0 0 0-8.49-8.49L5 10.5V19h8.5z"/><path d="M16 8 2 22"/><path d="M17.5 15H9"/>',
  lv3: '<path d="M22 18H2a4 4 0 0 0 4 4h12a4 4 0 0 0 4-4Z"/><path d="M21 14 10 2 3 14h18Z"/><path d="M10 2v16"/><path d="M4 17.5h16"/>',
  lv4: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/><path d="M19 3v4"/><path d="M21 5h-4"/>',
  lv5: '<path d="M8 4.4h8v4.2a4 4 0 0 1-8 0V4.4Z"/><path d="M8 5.8H5.6a2.4 2.4 0 0 0 2.4 2.4M16 5.8h2.4a2.4 2.4 0 0 1-2.4 2.4"/><path d="M12 12.6v3.4M9.6 19.6h4.8M10.2 16h3.6l.5 3.6h-4.6l.5-3.6Z"/>',
  lv6: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
  lv7: '<circle cx="12" cy="5" r="3"/><path d="M12 22V8"/><path d="M5 12H2a10 10 0 0 0 20 0h-3"/>',
  lv8: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>',
  lv9: '<path d="M2.8 12S6.2 5.6 12 5.6 21.2 12 21.2 12 17.8 18.4 12 18.4 2.8 12 2.8 12Z"/><circle cx="12" cy="12" r="2.7"/>',
  // lv10 寰宇传说：行星轨道（宇宙）
  lv10:'<path d="M20.341 6.484A10 10 0 0 1 10.266 21.85m-6.607-4.334A10 10 0 0 1 13.74 2.152"/><circle cx="12" cy="12" r="3"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="19" r="2"/>',
  // v5.14h.6：8 头像 glyph 补全（v5.13g 头像选择器 6/8 个图标从未定义 → 选王冠/火焰等空白）
  //   复用等级 icon 路径（crown=lv8王冠, flame=lv7烈焰, shield=lv6盾, orbit=lv10轨道, compass=lv2罗盘, sword=lv3剑, sparkles=lv4星光）
  compass: '<circle cx="12" cy="12" r="10"/><path d="m16.24 7.76l-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z"/>',
  sword:   '<path d="m11 19l-6-6m0 8l-2-2m5-3l-4 4m5.5-2.5L20.414 6.586A2 2 0 0 0 21 5.172V3h-2.172a2 2 0 0 0-1.414.586L6.5 14.5"/>',
  sparkles:'<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594zM20 2v4m2-2h-4"/><circle cx="4" cy="20" r="2"/>',
  crown:   '<path d="M11.562 3.266a.5.5 0 0 1 .876 0L15.39 8.87a1 1 0 0 0 1.516.294L21.183 5.5a.5.5 0 0 1 .798.519l-2.834 10.246a1 1 0 0 1-.956.734H5.81a1 1 0 0 1-.957-.734L2.02 6.02a.5.5 0 0 1 .798-.519l4.276 3.664a1 1 0 0 0 1.516-.294zM5 21h14"/>',
  shield:  '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
  flame:   '<path d="M12 3q1 4 4 6.5t3 5.5a1 1 0 0 1-14 0a5 5 0 0 1 1-3a1 1 0 0 0 5 0c0-2-1.5-3-1.5-5q0-2 2.5-4"/>',
  orbit:   '<path d="M20.341 6.484A10 10 0 0 1 10.266 21.85m-6.607-4.334A10 10 0 0 1 13.74 2.152"/><circle cx="12" cy="12" r="3"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="19" r="2"/>',
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
  // v5.13j：顶栏挂件开关换 lucide columns-2（更直观"独立窗格/桌面挂件"，手画 pin 渲染像 R 太丑）
  columns2: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M12 3v18"/>',
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
  // v5.15.12：动作键图标补齐 —— 此前 ICONS 里没有 stop/arrow/alert，
  //   行内三个键的前两个渲染成空圆（boss 截图："前面两个按键没图标"），正好是记忆里踩过的坑。
  play:     '<path d="M8 5.2l11.2 6.8L8 18.8z"/>',
  stop:     '<circle cx="12" cy="12" r="8.6"/><rect x="9" y="9" width="6" height="6" rx="1.5"/>',
  // v5.15.16（boss）：取消追踪不再用 ✕，改成"实心追踪键"（target 的反相：镂空处填满）。
  //   svgIcon 默认 fill=none，这里对 path 显式 fill=currentColor + stroke=none + evenOdd 挖环槽。
  targetFill: '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M3.4 12A8.6 8.6 0 1 1 20.6 12A8.6 8.6 0 1 1 3.4 12ZM6.9 12A5.1 5.1 0 1 1 17.1 12A5.1 5.1 0 1 1 6.9 12ZM8.7 12A3.3 3.3 0 1 1 15.3 12A3.3 3.3 0 1 1 8.7 12Z"/>',
  arrow:    '<path d="M4.6 12h13.4"/><path d="M12.8 6.6l5.4 5.4-5.4 5.4"/>',
  alert:    '<path d="M12 4.4L2.9 20h18.2z"/><path d="M12 9.8v4.3M12 17.1h.01"/>',
  popup:    '<rect x="2.6" y="4.6" width="18.8" height="12.4" rx="2.4"/><path d="M8 20.3h8"/>',
  expand:   '<path d="M9.4 3.6H4.4v5M14.6 3.6h5v5M9.4 20.4H4.4v-5M14.6 20.4h5v-5"/>',
  appico:   '<rect x="3.4" y="3.4" width="17.2" height="17.2" rx="4.2"/><path d="M8.4 9.4h7.2M8.4 13.2h7.2"/>',
  edit:     '<path d="M4.6 19.4h3l10.1-10.1a1.9 1.9 0 0 0 0-2.7l-.7-.7a1.9 1.9 0 0 0-2.7 0L4.6 16z"/><path d="M14.1 7.2l2.7 2.7"/>',
  trash:    '<path d="M4.4 7h15.2"/><path d="M9.6 4.5h4.8v2.5H9.6z"/><path d="M6.5 7l.9 12.1a1.8 1.8 0 0 0 1.8 1.7h5.6a1.8 1.8 0 0 0 1.8-1.7L17.5 7"/><path d="M10.4 11v6M13.6 11v6"/>',
  // v5.15.12：补齐 3 个此前缺失的图标（缺图标时 svgIcon 返回空串 → 按钮变空白圆）
  lock:     '<rect x="4.6" y="10.4" width="14.8" height="9.6" rx="2.2"/><path d="M8 10.4V7.8a4 4 0 0 1 8 0v2.6"/>',
  target:   '<circle cx="12" cy="12" r="8.4"/><circle cx="12" cy="12" r="3.4"/><path d="M12 3.6v2.2M12 18.2v2.2M3.6 12h2.2M18.2 12h2.2"/>',
  diamond:  '<path d="M12 3.4l6.4 4.2v8.8L12 20.6 5.6 16.4V7.6z"/>',
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
// =============== v5.15.29 L5/L6 · 昵称规则（与手机端完全一致） ===============
// L5：**没编辑过**昵称 → 昵称 = 当前等级名称（随等级自动变，如「星辰霸主」）；
//     **编辑过** → 用用户编辑的那个。
//     判定靠 settings.nickname_custom（'' = 未编辑 / '1' = 已编辑），走与手机端同一条 setting 同步通道。
// L6：编辑时若输入值等于**高于当前等级**的等级名称 → 只在编辑昵称的卡片里给一句提示，
//     **不阻止保存**（编辑什么昵称都是用户的自由）。
function activeNickname() {
  if (settings.nickname_custom === '1' && (settings.nickname || '').trim()) return settings.nickname.trim();
  const cur = (typeof level !== 'undefined' && level) ? level : levelOf(points);
  return (cur && cur.name) || '历练学徒';
}
function higherLevelName(text) {
  const t = (text || '').trim();
  if (!t) return null;
  const cur = (typeof level !== 'undefined' && level) ? level : levelOf(points);
  const curLv = (cur && cur.lv) || 1;
  for (let i = 0; i < LEVELS.length; i++) {
    if (LEVELS[i].name === t && LEVELS[i].lv > curLv) return LEVELS[i];
  }
  return null;
}
function nickWarnText(info) {
  return '这是 ' + info.lv + ' 级「' + info.name + '」的名称，您可以提前摘取高处的果实，' +
         '但通往成功的道路仍在您的前方，愿你早日到达。';
}
function refreshNickWarn(inputEl, warnEl) {
  if (!warnEl) return;
  const hi = higherLevelName(inputEl ? inputEl.value : '');
  if (hi) { warnEl.textContent = nickWarnText(hi); warnEl.style.display = ''; }
  else { warnEl.textContent = ''; warnEl.style.display = 'none'; }
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
// v5.14c：纯时间格式 HH:MM（用于任务卡"开始-结束"显示）
function fmtTime(ts) {
  if (!ts) return '--:--';
  const d = new Date(ts);
  return String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
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
  // v5.14h.13：头像等关键字段异步持久化到后端 settings 表（localStorage 在 WebView2 userData 里，
  //   清 EBWebView 缓存会连带清空 → 之前每次清缓存 boss 头像就丢）
  persistSettingsServer();
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
  // v5.15.31：每日任务刷新时间回填（两个小框）
  if (typeof _setRefreshTime === 'function') _setRefreshTime(settings.daily_refresh_time || '00:00');
  document.getElementById('setPairingStatus').textContent = settings.pairing ? '已配对：' + settings.pairing.url : '未配对';
  // 配对区条件显示：已配对时隐藏扫描设备 + 手动配对两行（防误解可同时连多个手机）
  const paired = !!settings.pairing;
  document.querySelectorAll('.ss-pair-hidden-on-pair').forEach(el => el.classList.toggle('ss-pair-hidden', paired));
  // v5.14h.17：设置页轮询（每次都查 setPairingStatus，没找到就跳过 — 找到即更新）
  if (!window._pairConnTimer) {
    window._pairConnTimer = setInterval(async () => {
      const ps = document.getElementById('setPairingStatus');
      if (!ps) return;  // 设置页未打开就不更新（设置区不在 DOM）
      try {
        const connected = await call('is_ws_connected');
        const peer = await call('get_ws_peer');
        if (connected) ps.textContent = '已配对 · 实时连接中：' + (peer || settings.pairing?.url || '');
        // v5.15.19：不再写"点击下方扫描设备重连" —— 现在会自动扫描重连，
        //   提示改成"正在自动重连"，避免 boss 以为必须手点（旧文案正是误解来源）。
        else if (settings.pairing) ps.textContent = '已配对（缓存）· 当前未连接 — 正在自动扫描重连…';
        else ps.textContent = '未配对';
      } catch(e) { /* 启动期 ignore */ }
    }, 3000);
  }
  // 解除配对按钮：未配对时禁用 + 灰
  const unpair = document.getElementById('setUnpair');

  // v5.14d：设置里只保留简单默认配置（方式 4 选 + 范围 4 选），跟手机端对齐
  //   端→方式→时间的条件显示在新建/编辑任务 modal 里（见 openAdd 后面）
  ['setRemindNotif','setRemindVibrate','setRemindSound','setRemindLoop'].forEach(id => {
    const el = document.getElementById(id);
    if (!el) return;
    el.checked = settings[id] !== false;   // 默认勾选
    el.onchange = () => { settings[id] = el.checked; saveSettings(); };
  });
  // 范围 4 选（默认 both）—— 只匹配 setRange*（v5.14g 高级默认区另有 setDev* 8 个按钮不能互相污染）
  const RANGE_KEY = 'reminder_default_range';
  const range = settings[RANGE_KEY] || 'both';
  document.querySelectorAll('#settingsOverlay [id^=setRange]').forEach(b => {
    b.classList.toggle('active', b.dataset.device === range);
    b.onclick = () => {
      settings[RANGE_KEY] = b.dataset.device;
      saveSettings();
      applySettingsToUi();
    };
  });
  // v5.14g：高级默认提醒（端→方式→时间 完整配置面板，不条件隐藏）
  const DEV_KEY = 'reminder_default_device';
  const LEAD_KEY = 'reminder_default_lead_min';
  const advDev = settings[DEV_KEY] || 'both';
  document.querySelectorAll('#settingsOverlay [id^=setDev]').forEach(b => {
    b.classList.toggle('active', b.dataset.device === advDev);
    b.onclick = () => {
      settings[DEV_KEY] = b.dataset.device;
      saveSettings();
      applySettingsToUi();
    };
  });
  // v5.15.14：默认提醒方式按端拆开 —— 手机端（通知栏/振动/响铃）｜电脑端（弹窗/全屏/应用内）
  ['setAdvNotif','setAdvVibrate','setAdvSound'].forEach((id, i) => {
    const el = document.getElementById(id);
    if (!el) return;
    const key = 'rem_adv_' + ['notif','vibrate','sound'][i];
    el.checked = settings[key] !== false;
    el.onchange = () => { settings[key] = el.checked; saveSettings(); };
  });
  ['setPcAdvPopup','setPcAdvFull','setPcAdvInApp'].forEach((id, i) => {
    const el = document.getElementById(id);
    if (!el) return;
    const key = 'rem_pc_adv_' + ['popup','fullscreen','inapp'][i];
    // 默认：弹窗 + 应用内 开，全屏 关
    el.checked = (settings[key] !== undefined) ? !!settings[key] : (key !== 'rem_pc_adv_fullscreen');
    el.onchange = () => { settings[key] = el.checked; saveSettings(); };
  });
  const lead = String(settings[LEAD_KEY] !== undefined ? settings[LEAD_KEY] : 15);
  document.querySelectorAll('#settingsOverlay .rem-time-btn').forEach(b => {
    b.classList.toggle('active', String(b.dataset.min) === lead);
    b.onclick = () => {
      settings[LEAD_KEY] = parseInt(b.dataset.min, 10);
      saveSettings();
      // v5.15.12：选中预设时把「自定义」自动填成对应数值 + 单位
      //   （boss：点「1 小时前」应该自动补 1 / 小时，方便在此基础上微调）
      const cust = document.getElementById('setLeadCustom');
      const unit = document.getElementById('setLeadUnit');
      const mm = parseInt(b.dataset.min, 10);
      if (cust && unit) {
        if (mm <= 0) { cust.value = ''; unit.value = '1'; }
        else if (mm % 1440 === 0) { cust.value = mm / 1440; unit.value = '1440'; }
        else if (mm % 60 === 0) { cust.value = mm / 60; unit.value = '60'; }
        else { cust.value = mm; unit.value = '1'; }
      }
      applySettingsToUi();
    };
  });
  // v5.14h.3：自定义提前时间（不在预设中时显示原值）
  // v5.15.7：支持 分钟/小时/天 三种单位（原来只能填分钟，boss 反馈"不能只以分钟为单位"）
  const setLeadCustom = document.getElementById('setLeadCustom');
  const setLeadUnit = document.getElementById('setLeadUnit');
  if (setLeadCustom) {
    const presetValues = ['0', '15', '60', '1440'];
    const n = parseInt(lead, 10);
    if (!isNaN(n) && n > 0 && !presetValues.includes(lead)) {
      // 显示时换算成最"整"的单位：4320 → 3 天；180 → 3 小时；45 → 45 分钟
      let u = 1;
      if (n % 1440 === 0) u = 1440;
      else if (n % 60 === 0) u = 60;
      setLeadCustom.value = n / u;
      if (setLeadUnit) setLeadUnit.value = String(u);
    }
    const commitLead = () => {
      const v = parseInt(setLeadCustom.value, 10);
      const u = setLeadUnit ? (parseInt(setLeadUnit.value, 10) || 1) : 1;
      if (isNaN(v) || v < 0) { setLeadCustom.value = ''; return; }
      settings[LEAD_KEY] = Math.min(43200, v * u);   // 内部统一存分钟，上限 30 天
      saveSettings();
      applySettingsToUi();
    };
    setLeadCustom.onchange = commitLead;
    if (setLeadUnit) setLeadUnit.onchange = commitLead;
  }
  // 持久化到后端 settings
  if (!settings._remSynced) { settings._remSynced = true; }
  if (unpair) {
    // v5.14h.12：未配对时隐藏 unpair 按钮（之前显示 + disabled 红框让 boss 觉得逻辑反）
    //   配对流程用 .ss-pair-hidden-on-pair 类的"扫描设备 / 手动配对"区显示
    if (settings.pairing) {
      unpair.style.display = '';
      unpair.disabled = false;
      unpair.classList.remove('disabled');
      unpair.title = '解除与 ' + settings.pairing.url + ' 的配对';
    } else {
      unpair.style.display = 'none';
    }
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
      daily_refresh_time: settings.daily_refresh_time || '00:00',   // v5.15.31：双端刷新口径要一致
      night_notify: settings.night_notify ? '1' : '0',
      nickname: settings.nickname || '',
      nickname_custom: settings.nickname_custom || '',   // v5.15.29 L5：昵称来源标志一并落库
      avatar_idx: settings.avatar_idx != null ? settings.avatar_idx : 0,
      avatar_img: settings.avatar_img || ''   // v5.14h.13：头像 base64 同步到后端 settings 表（防清 WebView2 缓存丢头像）
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
    if (nn != null) settings.nickname = nn;
    // v5.15.29 L5：昵称来源标志 + 老库迁移
    //   老库里 nickname 还停在默认值（boss / 历练者）→ 视为「没编辑过」，改成跟随等级名
    const nc = await call('get_setting', { key: 'nickname_custom' });
    if (nc === '1') settings.nickname_custom = '1';
    else {
      const oldN = (settings.nickname || '').trim();
      if (!oldN || oldN === 'boss' || oldN === '历练者') { settings.nickname = ''; settings.nickname_custom = ''; }
      else settings.nickname_custom = '1';
    }
    const ai = await call('get_setting', { key: 'avatar_idx' });
    if (ai != null && !isNaN(parseInt(ai))) settings.avatar_idx = parseInt(ai);
    const aimg = await call('get_setting', { key: 'avatar_img' });   // v5.14h.13：头像图从后端恢复
    if (aimg) settings.avatar_img = aimg;
  } catch (e) {}
}

// =============== 渲染主流程 ===============
async function fetchAll() {
  const [tasksResp, archiveResp, pointsResp, levelResp, progressResp, trackResp] = await Promise.all([
    call('get_today_tasks'),
    call('get_archive', {}),
    call('get_total_points'),
    call('get_level', {}).catch(() => null),
    call('get_daily_progress', {}).catch(() => null),
    call('get_track_cards', {}).catch(() => null)
  ]);
  tasks = tasksResp || [];
  catTasks = {};   // v5.15.11：数据变化后分类缓存失效，下次进分类页重新取
  archive = archiveResp || [];
  points = typeof pointsResp === 'number' ? pointsResp : (pointsResp && pointsResp.points) || 0;
  level = levelOf(points);   // v5.13g：忽略后端 get_level 硬编码旧 5 级曲线（Rust 端未同步升级），用前端新 10 级 LEVELS
  progressInfo = progressResp || progressInfo;
  trackCards = trackResp || [];
  if (levelResp && levelResp.style) settings.progress_style = levelResp.style;
  // v5.14g：升级检测（points 跨过 LEVELS 阈值 → 弹升级提示）——渲染前检测避免弹窗被 render 打断
  detectLevelUp();
}
// v5.14g：等级变化检测 —— oldLevelPoints 在首次启动/等级变更时更新，跨级弹窗庆祝
let _lastLvMin = -1;   // 上次记录到的等级门槛分数（0=历练学徒起点）
function detectLevelUp() {
  const lv = level || levelOf(points);
  const curMin = lv ? lv.min : 0;
  if (_lastLvMin === -1) { _lastLvMin = curMin; return; }        // 首次：只记录不弹
  if (curMin > _lastLvMin) {                                       // 真的升级了
    _lastLvMin = curMin;
    // 延迟到当前 render 完成后再弹，避免覆盖首次渲染
    setTimeout(() => {
      const bonus = (points || 0) > 0 ? 0 : 0;   // 升级不额外给分，只是庆祝
      showBless({ mode: 'levelup', title: '升级到 ' + (lv.name || '') + '！', points: points });
    }, 600);
  } else {
    _lastLvMin = curMin;   // 同等级更新基准（防止降级时误判）
  }
}
// v5.14h.9：侧边栏追踪角标实时显示（之前只在 renderTrackingView 里更新 → 其他 tab 一直显示初始 0）
function updateTrackingBadge() {
  const cnt = document.getElementById('trackingCount');
  if (!cnt) return;
  const n = (typeof tasks !== 'undefined' ? tasks : []).filter(t => isTracking(t) && !t.done && t.deleted !== 1).length;
  cnt.textContent = n;
  cnt.classList.toggle('zero', n === 0);
}
/** v5.15.18 P0：渲染数据签名 —— 只有数据真的变了才重建 DOM。
 *  背景：原先 `setInterval(render, 15000)` 每 15 秒**无条件**整页重建，
 *  在 transparent 窗口上每次都是一次肉眼可见的闪
 *  （boss：「现在就算我什么都没做也有闪屏问题」）。
 *  有了签名，静止期不再触发任何 DOM 重建 = 空闲闪根治。 */
function _dataSig() {
  try {
    return JSON.stringify([
      (tasks || []).map(t => [t.uuid, t.trackStatus, t.done, t.doneCount, t.progress, t.updatedAt, t.title]),
      (archive || []).length,
      points,
      level ? level.lv : 0,
      (trackCards || []).length
    ]);
  } catch (e) {
    return 'sig-err-' + Date.now();   // 出错就当"变了"，宁可多渲染也不要不刷新
  }
}

async function render() {
  // v5.15.18：删掉原来"给 #app 加 .fading + await 30ms"的写法。
  //   那段是**死代码** —— style.css 里只有 `.widget.fading`，根本没有 `#app.fading` 规则，
  //   所谓"防闪过渡"从未生效，只白白拖慢 30ms。
  //   真正的闪来自"整块 DOM 重建"，改由下面的内容区过渡 + 上面的数据签名一起解决。
  const lvEl = document.getElementById('listView');
  try {
    await fetchAll();
    document.title = '[' + points + '分/' + (level?level.name:'无') + '] 任务栏';
    const tpEl = document.getElementById('totalPoints');
    if (tpEl) tpEl.textContent = points;
    renderLevelBadge();
    renderSideNav();
    updateTrackingBadge();   // v5.14h.9：每次 render 都更新追踪角标（不依赖 nav='tracking'）
    // 内容区轻过渡：只做 3px 上移，**刻意不动 opacity**
    //   （透明窗上做透明度渐变会露出窗口底色，反而又变成一次闪）
    if (lvEl) {
      lvEl.classList.remove('view-enter');
      void lvEl.offsetWidth;   // 强制重排，让动画能重放
      lvEl.classList.add('view-enter');
    }
    if (nav === 'overview') renderOverview();
    else if (nav === 'today') renderTodayView();
    else if (nav === 'tracking') renderTrackingView();
    else renderListView();
    renderDashboard();
    checkEmergency();
  } catch (e) {
    console.error('render 失败', e);
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
// v5.15.26 P4（boss：把每个分类都弄成一个下拉栏，可以收起来；电脑端也这样干）——
//   收起状态存这里（Set），整块 innerHTML 重绘后依然保留（不能靠 DOM class 存状态）。
// ⚠️ C-021 教训：这段必须放**顶层**。第一次误插进了 renderSideNav() 内部 → 变成局部作用域，
//   顶层的 renderTodayView 调用 _cg 时直接 ReferenceError（界面右上角弹红字 "render err"）。
const _collapsedGroups = new Set();
function _cg(k) { return _collapsedGroups.has(k); }
function toggleCatGroup(k) {
  const on = !_collapsedGroups.has(k);
  if (on) _collapsedGroups.add(k); else _collapsedGroups.delete(k);
  // v5.15.27 P5（boss：「电脑端的分类的展开和收起会导致页面抖动」）——
  //   旧实现是 `render()` **整页重渲染**：重建 innerHTML → 滚动位置被重置 + 闪一下 = 抖动。
  //   现在只就地切 `.collapsed` 类 + 换箭头字形，**完全不重渲染** → 不抖、不跳、不闪。
  //   （折叠状态仍写进 _collapsedGroups，下次 render 时保持一致）
  document.querySelectorAll('.cat-group[data-cg="' + k + '"]').forEach(g => {
    g.classList.toggle('collapsed', on);
    const a = g.querySelector('.cg-arrow');
    if (a) a.textContent = on ? '\u25B8' : '\u25BE';
  });
}
window.toggleCatGroup = toggleCatGroup;

function renderSideNav() {
  document.querySelectorAll('.nav-item').forEach(n => {
    n.classList.toggle('active', n.dataset.nav === nav);
  });
}
// v5.14g：追踪任务视图（boss 要求侧边栏显示追踪任务）—— 显示所有 track_status='tracking' 的任务
function renderTrackingView() {
  const lvEl = document.getElementById('listView');
  if (!lvEl) return;
  const trackTasks = tasks.filter(t => isTracking(t) && !t.done && t.deleted !== 1);
  // v5.14h.9：角标统一由 render() 里 updateTrackingBadge() 更新（此处不再写）
  // v5.15.12：空态改成"中间一句话"（boss：这个页面文字很拥挤，没有看的欲望）
  let html = `
    <div class="list-view-header">
      <span class="vh-title">追踪任务</span>
      <span class="vh-meta">${trackTasks.length} 项进行中</span>
    </div>
  `;
  if (trackTasks.length === 0) {
    html += `<div class="track-empty">
      <div class="te-ico">${svgIcon('crosshair', 22, 1.9)}</div>
      <div class="te-title">还没有追踪中的任务</div>
      <div class="te-sub">在任务详情页点「追踪任务」即可开始</div>
    </div>`;
  } else {
    // v5.15.29 P1（boss：「电脑端的追踪中任务栏 UI 显示在各个分类里显示不统一」）——
    //   本页原来用的是另一套写法：标题叫「追踪中」、且**没有 .cg-body 容器**
    //   → 没有浅金底/描边/下圆角，和「今日待办」里同组的「正在追踪」长得不一样。
    //   现在与 renderTodayView 的置顶组完全对齐：标题「正在追踪」+ .cg-body 大框。
    html += `<div class="cat-group">
      <div class="cat-group-head"><span class="ico ico-tracking" data-icon="crosshair" data-icon-size="13"></span><span>正在追踪</span><span class="gh-count">${trackTasks.length}</span></div>
      <div class="cg-body">${trackTasks.map(catTaskHtml).join('')}</div>
    </div>`;
  }
  lvEl.innerHTML = html;
  if (typeof initIcons === 'function') initIcons(lvEl);   // v5.15.14：分组头等动态图标
  lvEl.style.display = '';
  const overviewEl = document.getElementById('overviewView');
  if (overviewEl) overviewEl.style.display = 'none';
}
// v5.14d：今日视图（boss 要求打开客户端默认看到今日待办）
function renderTodayView() {
  const lvEl = document.getElementById('listView');
  if (!lvEl) return;
  // 今日：所有 cat 任务（包括未完成 + 今日截止的 time-limited）
  const todayTasks = tasks.filter(t => !t.done && t.deleted !== 1);
  const grouped = { 'daily': [], 'time-limited': [], 'once': [], 'goal': [] };
  // v5.15.13 P0：必须走 catOf()（它把 habit/repeat/note/milestone 和手机端的自定义
  //   category 都映射到 4 类）。旧实现直接读 t.category —— 手机端同步过来的任务
  //   category 是"学习/生活/工作"，不在 4 类里 → 整条被丢掉，boss 看到"24 项只显示 11 个"。
  // v5.15.18 K1：需要置顶时，把追踪中的任务从各分类里摘出来，单独放到页面最上方
  const pinnedTasks = _pinTracking ? todayTasks.filter(t => isTracking(t)) : [];
  const pinnedSet = new Set(pinnedTasks.map(t => t.uuid));
  todayTasks.forEach(t => {
    if (pinnedSet.has(t.uuid)) return;   // 已置顶 → 不在分类里重复出现
    const k = catOf(t);
    if (grouped[k]) grouped[k].push(t);
    else grouped.once.push(t);
  });
  // v5.15.16：每个分组内把"追踪中"排到最前（boss：电脑端追踪任务也要置顶）
  Object.keys(grouped).forEach(k => grouped[k].sort((a, b) => (isTracking(b) ? 1 : 0) - (isTracking(a) ? 1 : 0)));
  const titleMap = { daily:'每日任务', 'time-limited':'限时任务', once:'次数任务', goal:'目标任务' };
  const iconMap = { daily:'daily', 'time-limited':'lim', once:'once', goal:'goal' };
  let html = `
    <div class="list-view-header today-view-header">
      <span class="vh-title">今日待办</span>
      <span class="vh-meta">${todayTasks.length} 项未完成</span>
    </div>
    <div class="vh-hint">早一点完成就多一点余裕</div>
  `;
  if (pinnedTasks.length) {
    // v5.15.28 P3（boss：「正在追踪不用收起功能」）—— 与手机端一致：
    //   标题不可点、不显示三角、永远展开（追踪中的任务是最需要随手看到的一组）
    html += `<div class="cat-group pinned-tracking" data-cg="__pin">
      <div class="cat-group-head"><span class="ico ico-tracking" data-icon="crosshair" data-icon-size="13"></span><span>正在追踪</span><span class="gh-count">${pinnedTasks.length}</span></div>
      <div class="cg-body">${pinnedTasks.map(catTaskHtml).join('')}</div>
    </div>`;
  }
  ['daily','time-limited','once','goal'].forEach(cat => {
    const list = grouped[cat];
    if (list.length === 0) return;
    const c = _cg(cat);
    html += `<div class="cat-group ${c ? 'collapsed' : ''}" data-cg="${cat}">
      <div class="cat-group-head" onclick="toggleCatGroup('${cat}')"><span class="ico ico-${iconMap[cat]}" data-icon="${iconMap[cat]}" data-icon-size="13"></span><span>${titleMap[cat]}</span><span class="gh-count">${list.length}</span><span class="cg-arrow">${c ? '\u25B8' : '\u25BE'}</span></div>
      <div class="cg-body">${list.map(catTaskHtml).join('')}</div>
    </div>`;
  });
  if (todayTasks.length === 0) {
    html += `<div class="empty-tip">今日所有任务都已完成 ✦</div>`;
  }
  lvEl.innerHTML = html;
  if (typeof initIcons === 'function') initIcons(lvEl);   // v5.15.14：今日待办的分组头图标
  lvEl.style.display = '';   // v5.14d 修：原本 listView display:none，renderTodayView 写进去看不见
  // 同步隐藏 overviewView（中间列表区的 4 分类卡）
  const overviewEl = document.getElementById('overviewView');
  if (overviewEl) overviewEl.style.display = 'none';
  // 右侧主区 dashboard 永远显示（boss 反馈"之前的页面呢 不要了吗"——"晚上好 boss + 进度条"就是 dashboard）
  // render() 主流程已经无条件 renderDashboard()，这里不再 hide
}
document.querySelectorAll('.nav-item').forEach(n => {
  n.addEventListener('click', async () => {
    // 历史任务 = 独立弹窗（不占用主视图）
    if (n.dataset.nav === 'archive') { openArchiveModal(); return; }
    nav = n.dataset.nav;
    // v5.15.18 K1：重新进入"今日待办/总览" → 恢复追踪置顶（当次点追踪的"不置顶"只影响那一次）
    if (nav === 'today' || nav === 'overview') _pinTracking = true;
    selectedUuid = null;
    document.getElementById('detailView').style.display = 'none';
    await render();   // v5.13：先 render() 触发 fetchAll 刷新 tasks 数据，再找 first
                      // v5.15.31：原来没有 await → 紧接着读 tasks 拿的是**上一轮**数据
    // v5.15.31（boss：点总览 / 今日 / 每日任务时，都应该默认选中第一个任务并看它的详情）——
    //   原实现只覆盖"分类页"，总览和今日漏了；统一改走 pickDefaultTask()
    const first = pickDefaultTask(nav);
    if (first) {
      selectedUuid = first.uuid;
      openDetail(selectedUuid);
    }
  });
});
// v5.15.31：默认选中哪一条 —— 追踪中 > 未完成 > 第一条；分类页只在**同分类**里挑
function pickDefaultTask(navKey) {
  if (typeof tasks === 'undefined' || !tasks || !tasks.length) return null;
  const alive = tasks.filter(t => t.deleted !== 1);
  if (!alive.length) return null;
  const CAT_FILTER = { daily: 'daily', goal: 'goal', 'time-limited': 'time-limited', once: 'once' };
  const cat = CAT_FILTER[navKey];
  const pool = cat ? alive.filter(t => catOf(t) === cat) : alive;
  if (!pool.length) return null;
  return pool.find(t => isTracking(t) && !t.done) || pool.find(t => !t.done) || pool[0];
}

document.getElementById('archiveSearch').addEventListener('input', renderArchiveModal);
document.querySelectorAll('[data-close-overlay="archiveOverlay"]').forEach(b => b.addEventListener('click', closeArchiveModal));

// =============== 总览页（4 分类） ===============
function renderOverview() {
  document.getElementById('overviewView').style.display = '';
  document.getElementById('listView').style.display = 'none';
  const groups = { 'daily': [], 'goal': [], 'time-limited': [], 'once': [] };
  // v5.15.18 K1：置顶的追踪任务单独放最上面那张卡，不进分类卡
  const ovPinned = _pinTracking ? tasks.filter(t => isTracking(t)) : [];
  const ovPinnedSet = new Set(ovPinned.map(t => t.uuid));
  for (const t of tasks) {
    if (ovPinnedSet.has(t.uuid)) continue;
    groups[catOf(t)].push(t);
  }
  const trCard = document.getElementById('catCardTracking');
  const trBox = document.getElementById('listTracking');
  if (trCard && trBox) {
    if (ovPinned.length) {
      trCard.style.display = '';
      trBox.innerHTML = ovPinned.map(t => catTaskHtml(t)).join('');
      if (typeof initIcons === 'function') initIcons(trBox);
    } else {
      trCard.style.display = 'none';
    }
  }

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
      if (typeof initIcons === 'function') initIcons(box);   // v5.15.14：动态节点的 data-icon 需要重新注入
    }
  }
}
function catMapId(cat) {
  return { 'daily':'Daily', 'goal':'Goal', 'time-limited':'Lim', 'once':'Once' }[cat];
}
function catTaskHtml(t) {
  // v5.15.11：任务名优先（独占整行，超长省略号），时间只留一个胶囊，逾期换成"逾期 N"标记；
  //   右侧三个圆形动作键（取消追踪/推进/完成），键下带功能文字
  // v5.15.21 P4（boss："任务栏右侧放一个小型动态的东西 星星 或者涟漪 现在怎么没有了"）：
  //   菱形 .ct-diamond 的**渲染代码在某次重构时丢了**（CSS 一直在，元素没人生成 → 空转）。
  //   这里补回来：仅 `.tracking` 时由 CSS 显示蓝色菱形 + rippleBreath 涟漪呼吸。
  return `<div class="cat-task ${isTracking(t) ? 'tracking' : ''} ${isOverdue(t) ? 'overdue' : ''}" data-uuid="${t.uuid}">
    <div class="lt-main" onclick="openDetail('${t.uuid}')">
      <span class="ct-title">${esc(t.title)}</span>
      ${timeMetaHtml(t)}
    </div>
    ${rowActsHtml(t)}
    <span class="ct-diamond" aria-hidden="true"></span>
  </div>`;
}

// =============== 列表视图（每日任务 / 限时任务 / 次数任务） ===============
// v5.15.11：分类页数据源 —— 旧实现从「今日任务」里筛，非今天的同分类任务在分类页里看不到
//   （boss：点侧边栏某一类什么都没有，右侧详情却有这个任务）。改为后端按分类全量取，前端缓存。
let catTasks = {};
function prefetchCatTasks(cat) {
  // v5.15.21 D5 修复（boss 第二次反馈）：旧实现 `if (catTasks[cat]) return;` —— 空数组是 truthy，
  //   于是"请求失败被 catch 写成 []"或"首次返回空"之后**永远不再重试**，分类页永久空白。
  //   现在：只在拿到**非空**结果时才缓存；失败/空一律不写缓存 → 下次进入该分类会重新拉取。
  const cached = catTasks[cat];
  if (cached && cached.length) return;
  call('get_tasks_by_category', { category: cat })
    .then(list => {
      if (list && list.length) catTasks[cat] = list;   // 只缓存有数据的结果
      else delete catTasks[cat];                       // 空结果不缓存（可能是瞬时/后端未就绪）
      if (nav === cat) renderListView();
    })
    .catch(() => { delete catTasks[cat]; });           // 失败不缓存，允许重试
}
function isTodayTask(t) {
  if (t.category === 'daily' || t.category === 'once') return true;
  if (t.category === 'time-limited' && t.due_at) {
    if (typeof t.due_at === 'number') return isToday(t.due_at);
  }
  return false;
}
function renderListView() {
  document.getElementById('overviewView').style.display = 'none';
  const listEl = document.getElementById('listView');
  listEl.style.display = '';
  // v5.14h.16：自重建 listView 结构（防 renderTodayView/renderTrackingView innerHTML 覆盖后结构变化——
  //   boss 反馈"点任务分类全跳追踪页"就是这个 bug，nav 切 daily/goal/once 时 listViewBody 找不到 → early return → 残留上次 view 文字）
  let box = document.getElementById('listViewBody');
  if (!box) {
    listEl.innerHTML = '<div class="view-head"><span class="vh-title" id="listViewTitle">任务</span><span class="vh-hint" id="listViewHint"></span><span class="vh-spacer"></span></div><div class="lv-body" id="listViewBody"></div>';
    box = document.getElementById('listViewBody');
  }
  let arr = [];
  const titleMap = { daily:'每日任务', goal:'目标任务', 'time-limited':'限时任务', once:'次数任务' };
  const titleEl = document.getElementById('listViewTitle');
  if (titleEl) titleEl.textContent = titleMap[nav] || '任务';
  const hintEl = document.getElementById('listViewHint');

  if (['daily', 'goal', 'time-limited', 'once'].includes(nav)) prefetchCatTasks(nav);
  // v5.15.21 D5：空数组视为"没数据"→ 回退到本地筛；且必须用 catOf()（会把自定义分类按 type
  //   推断归类，与总览页同口径），不能再读 t.category 字段（自定义分类会被漏掉）。
  const catList = (catTasks[nav] && catTasks[nav].length) ? catTasks[nav] : null;
  if (nav === 'daily') {
    arr = catList || tasks.filter(t => catOf(t) === 'daily');
    if (hintEl) hintEl.textContent = '每日 0 点自动刷新';
  } else if (nav === 'goal') {
    arr = catList || tasks.filter(t => catOf(t) === 'goal');
    if (hintEl) hintEl.textContent = '为目标坚持推进';
  } else if (nav === 'time-limited') {
    arr = catList || tasks.filter(t => catOf(t) === 'time-limited');
    if (hintEl) hintEl.textContent = '到期前记得完成';
  } else if (nav === 'once') {
    arr = catList || tasks.filter(t => catOf(t) === 'once');
    if (hintEl) hintEl.textContent = '次数任务可重复完成';
  }

  // v5.15.16：追踪中的排最前
  arr = arr.slice().sort((a, b) => (isTracking(b) ? 1 : 0) - (isTracking(a) ? 1 : 0));
  if (arr.length === 0) {
    box.innerHTML = `<div class="empty-line">这一类暂时没有任务</div>`;
  } else {
    box.innerHTML = arr.map(t => `
      <div class="list-task ${isTracking(t)?'tracking':''} ${isOverdue(t)?'overdue':''}" data-uuid="${t.uuid}">
        <div class="lt-main" onclick="openDetail('${t.uuid}')">
          <span class="ct-title">${esc(t.title)}</span>
          ${timeMetaHtml(t)}
        </div>
        ${rowActsHtml(t)}
        <span class="ct-diamond" aria-hidden="true"></span>
      </div>`).join('');
    if (typeof initIcons === 'function') initIcons(box);
  }
}

// =============== v5.15.11：任务行统一的时间/逾期表达 + 三个圆形动作键 ===============
/** 是否逾期：未完成 且 已过 due_at/deadline */
function isOverdue(t) {
  if (!t || t.done || t.track_status === 'done') return false;
  const d = (typeof t.deadline === 'number') ? t.deadline : t.due_at;
  return typeof d === 'number' && d < Date.now();
}
/** 行上的时间区：逾期 → 只给"逾期 N 天/小时"（具体时间放 tooltip，不再挤任务名）；正常 → 一个时间胶囊 */
function timeMetaHtml(t) {
  const due = t.due_at || t.deadline;
  if (isOverdue(t)) {
    const diff = Date.now() - ((typeof t.deadline === 'number') ? t.deadline : t.due_at);
    const txt = diff >= 86400000 ? ('逾期 ' + Math.floor(diff / 86400000) + ' 天')
      : diff >= 3600000 ? ('逾期 ' + Math.floor(diff / 3600000) + ' 小时')
        : '已逾期';
    return `<span class="ct-over" title="原定 ${fmtDue(due)}">${svgIcon('alert', 10, 2.2)}${txt}</span>`;
  }
  if (due) return `<span class="ct-meta" title="${fmtDue(due)}">${svgIcon('clock', 10, 2.1)}${fmtDue(due)}</span>`;
  if (t.category === 'once' && t.count > 1) return `<span class="ct-meta">${svgIcon('target', 10, 2.1)}次数 ${t.done_count || 0}/${t.count}</span>`;
  return '';
}
/** 三个圆形动作键（下面带功能文字）：取消追踪 / 推进 / 完成 */
function rowActsHtml(t) {
  // v5.15.18 P1（boss）：任务行里的三个小按键（追踪/推进/完成）**不再显示** ——
  //   老板反馈列表里操作易误触，且和挂件/详情页的同步链路慢，"把按键留在任务详情和挂件就好了"。
  //   详情页（.dva）与挂件（widget.html 自带 .acts）的按键**保留**。
  //   要恢复旧行为：删掉下面这行 return 即可。
  return '';
  // eslint-disable-next-line no-unreachable
  const tracking = isTracking(t);
  const noStep = !(t.step_total > 0);
  const first = tracking
    ? `<div class="act" data-act="stop" title="取消追踪"><span class="b">${svgIcon('targetFill', 11)}</span><span class="l">取消</span></div>`
    : `<div class="act track" data-act="track" title="开始追踪"><span class="b">${svgIcon('goal', 11, 2.3)}</span><span class="l">追踪</span></div>`;
  return `<div class="acts">
    ${first}
    <div class="act ${noStep ? 'disabled' : ''}" data-act="advance" title="${noStep ? '没有步骤，无法推进' : '推进到下一步'}">
      <span class="b">${svgIcon('arrow', 12, 2.6)}</span><span class="l">推进</span>
    </div>
    <div class="act done" data-act="done" title="完成任务">
      <span class="b">${svgIcon('check', 12, 3)}</span><span class="l">完成</span>
    </div>
  </div>`;
}
/** 逾期文案（详情页/行内共用） */
function overdueText(t) {
  const d = (typeof t.deadline === 'number') ? t.deadline : t.due_at;
  if (typeof d !== 'number' || d >= Date.now()) return '';
  const diff = Date.now() - d;
  if (diff >= 86400000) return '逾期 ' + Math.floor(diff / 86400000) + ' 天';
  if (diff >= 3600000) return '逾期 ' + Math.floor(diff / 3600000) + ' 小时';
  return '已逾期';
}
/** 行内三个键的点击处理（事件委托到 document，避免 innerHTML 重建后失效） */
document.addEventListener('click', async function (ev) {
  const actEl = ev.target.closest('.act[data-act]');
  if (!actEl) return;
  const row = actEl.closest('[data-uuid]');
  if (!row) return;
  ev.stopPropagation();
  const uuid = row.dataset.uuid;
  const what = actEl.dataset.act;
  if (actEl.classList.contains('disabled')) {
    if (what === 'stop') showToast('这个任务当前没有在追踪');
    else if (what === 'advance') showToast('这个任务还没有步骤，没有可推进的进度');
    return;
  }
  try {
    if (what === 'stop') {
      await call('stop_tracking', { taskUuid: uuid });
      showToast('已取消追踪');
    } else if (what === 'track') {
      const trackingCount = tasks.filter(isTracking).length;
      if (trackingCount >= settings.tracking_max) {
        showToast('已达追踪上限 ' + settings.tracking_max + ' 个，请先取消其它追踪');
        return;
      }
      await call('start_tracking', { taskUuid: uuid });
      showToast('已开始追踪');
    } else if (what === 'advance') {
      const d = await call('get_task_detail', { taskUuid: uuid });
      const steps = (d && d.steps) || [];
      const next = steps.find(s => s.status !== 'done');
      if (!next) { showToast('这个任务还没有步骤，可直接点「完成」'); return; }
      await call('advance_step', { stepUuid: next.uuid, taskUuid: uuid, status: 'done' });
      showToast('已推进：' + next.title);
    } else if (what === 'done') {
      const before = (typeof tasks !== 'undefined' ? tasks : []).find(t => t.uuid === uuid);
      const basePts = (before && before.reward_points) || 0;
      const r = await call('complete_task', { taskUuid: uuid });
      // v5.15.16：原来这里调用的 celebrateCompletion 根本不存在（typeof 检查静默跳过）→ 点完成没任何反馈
      if (r && r.already_done) {
        showToast('今日已完成 ✓');
      } else if (r && r.partial === true) {
        showToast('进度 +1 · 已完成 ' + r.done_count + ' / ' + r.count + ' 次');
      } else {
        const pts = (r && r.habit && !r.final_exp) ? 5 : ((r && r.final_exp) || basePts);
        // v5.15.21 P1（boss：次数任务完成后应该只弹出一个**不用点击**的积分获得提示）——
        //   次数任务（category=once 且 count>1）每次完成都很频繁，弹需要点击/带遮罩的
        //   大弹窗会打断操作；改用自动消失的轻提示条。
        const isCountTask = !!(before && before.category === 'once' && (before.count || 1) > 1);
        if (isCountTask) {
          showToast('✓ ' + ((before && before.title) || '任务') + ' 完成 · +' + pts + ' 积分');
        } else {
          showBless({ mode: 'reward', title: (before && before.title) || '任务完成', points: pts, isCritical: !!(r && r.is_critical) });
        }
      }
    }
    await render();
  } catch (e) {
    showToast('操作失败：' + (e.message || e));
  }
});

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
  // v5.15.11：历史改成**记账式流水**（boss 参考图：日历 or 记账 二选一，我选记账 ——
  //   桌面窄栏里按天分组 + 当日小计最易读，也天然体现"每天完成了多少、拿了多少分"）。
  const byDay = new Map();
  arr.forEach(t => {
    const ts = t.done_at || t.updated_at || t.created_at || 0;
    const d = new Date(ts);
    const key = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    if (!byDay.has(key)) byDay.set(key, []);
    byDay.get(key).push(t);
  });
  const days = [...byDay.entries()].sort((a, b) => b[0].localeCompare(a[0]));
  box.innerHTML = days.map(([key, items]) => {
    items.sort((a, b) => ((b.done_at || b.updated_at || 0) - (a.done_at || a.updated_at || 0)));
    const pts = items.reduce((sum, t) => sum + (t.reward_points || 0), 0);
    return `<div class="lg-day">
      <div class="lg-head">
        <span class="lg-date">${archiveDayLabel(key)}</span>
        <span class="lg-sum">完成 ${items.length} 项 · <b>+${pts}</b> 分</span>
      </div>
      <div class="lg-rows">${items.map(ledgerRowHtml).join('')}</div>
    </div>`;
  }).join('');
}

/** 日期标题：今天 / 昨天 / 9月8日 周一 */
function archiveDayLabel(key) {
  const [y, m, d] = key.split('-').map(Number);
  const dt = new Date(y, m - 1, d);
  const pad = n => String(n).padStart(2, '0');
  const todayK = (() => { const n = new Date(); return n.getFullYear() + '-' + pad(n.getMonth() + 1) + '-' + pad(n.getDate()); })();
  const yK = (() => { const n = new Date(Date.now() - 86400000); return n.getFullYear() + '-' + pad(n.getMonth() + 1) + '-' + pad(n.getDate()); })();
  const md = `${m}月${d}日`;
  if (key === todayK) return `今天 · ${md}`;
  if (key === yK) return `昨天 · ${md}`;
  return `${md} 周${'日一二三四五六'[dt.getDay()]}`;
}

/** 记账式一行：完成时间 · 任务名 · 分类 · 得分 */
function ledgerRowHtml(t) {
  const ts = t.done_at || t.updated_at || 0;
  const time = ts ? new Date(ts).toTimeString().slice(0, 5) : '';
  const cat = CAT_LABEL[catOf(t)] || '';
  // v5.15.16：历史任务左键不再打开"外面"的任务详情（boss：历史任务不该跳当前详情）
  //   改为右键菜单「恢复任务 / 取消」；悬停时给个提示
  return `<div class="lg-row" data-uuid="${t.uuid}" title="右键可恢复任务">
    <span class="lg-time">${time}</span>
    <span class="lg-title" title="${esc(t.title)}">${esc(t.title)}</span>
    <span class="lg-cat">${cat}</span>
    <span class="lg-pts">${t.reward_points ? '+' + t.reward_points : ''}</span>
  </div>`;
}


function arcItemHtml(t) {
  // 完成时间容错：后端可能只给 track_status=done 而 done_at 为空，退回 updated_at
  const doneTs = t.done_at || (t.track_status === 'done' ? t.updated_at : null);
  return `
    <div class="arc-item" data-uuid="${t.uuid}" title="右键 = 恢复此任务到今日待办">
      <span class="arc-ico cat-${catOf(t)}">${doneTs ? svgIcon('check',11,2.8) : '<i class="arc-dot"></i>'}</span>
      <div class="arc-main">
        <div class="arc-title">${esc(t.title)}</div>
        <div class="arc-sub">${CAT_LABEL[catOf(t)] || '未分类'} · 完成于 ${doneTs ? fmtDue(doneTs) : '—'} · 右键恢复</div>
      </div>
      <span class="arc-points">+${t.reward_points || 10}</span>
    </div>`;
}
// v5.13e 历史任务右键 = 自绘弹层菜单（含"恢复"+"取消"——boss 反馈右键直接恢复没保护）
let _restoreMenu = null;
function showRestoreMenu(x, y, uuid) {
  hideRestoreMenu();
  const m = document.createElement('div');
  m.className = 'ctx-menu';
  m.id = '_restoreMenu';
  // 防止超出右/下边界
  const W = 120, H = 70;
  const px = Math.min(x, window.innerWidth - W - 4);
  const py = Math.min(y, window.innerHeight - H - 4);
  m.style.left = px + 'px';
  m.style.top = py + 'px';
  m.innerHTML = '<button data-act="restore">恢复任务</button><button data-act="cancel">取消</button>';
  document.body.appendChild(m);
  m.addEventListener('click', (e) => {
    const act = e.target.dataset && e.target.dataset.act;
    hideRestoreMenu();
    if (act === 'restore') doRestore(uuid);
  });
  _restoreMenu = m;
}
function hideRestoreMenu() {
  if (_restoreMenu) { _restoreMenu.remove(); _restoreMenu = null; }
}
async function doRestore(uuid) {
  try {
    await call('restore_task', { taskUuid: uuid });
    showToast('已恢复到今日任务');
  } catch (e) { showToast('恢复失败'); }
  await fetchAll();
  render();
  renderArchiveModal();
}
document.getElementById('archiveBody').addEventListener('contextmenu', (e) => {
  // v5.15.16：记账式流水行是 .lg-row（旧结构 .arc-item 已不用）→ 两种都支持
  const item = e.target.closest('.lg-row') || e.target.closest('.arc-item');
  if (!item) return;
  e.preventDefault();
  hideRestoreMenu();
  showRestoreMenu(e.clientX, e.clientY, item.dataset.uuid);
});
// 点别处 / Esc 关闭菜单
document.addEventListener('click', (e) => {
  if (_restoreMenu && !e.target.closest('#_restoreMenu')) hideRestoreMenu();
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') hideRestoreMenu();
});

// =============== 右侧今日概览仪表盘 ===============
function renderDashboard() {
  // 问候语（动态昵称，不再固定"冒险者"）
  const h = new Date().getHours();
  let greet = '夜深了';
  if (h < 6) greet = '夜深了'; else if (h < 11) greet = '早上好'; else if (h < 14) greet = '中午好'; else if (h < 18) greet = '下午好'; else greet = '晚上好';
  const nick = activeNickname();   // v5.15.29 L5：没编辑过昵称就显示当前等级名
  document.getElementById('dashGreetText').textContent = greet + '，' + nick;
  // v5.14h.1：完整身份卡副行 = 等级名 + 当前积分 + 距下一级
  const lv2 = level || levelOf(points);
  const next2 = nextLevelOf(points);
  const greetName = document.getElementById('dashGreetName');
  const greetPoints = document.getElementById('dashGreetPoints');
  const greetRemain = document.getElementById('dashGreetRemain');
  if (greetName) greetName.textContent = lv2 ? lv2.name : '历练学徒';
  if (greetPoints) greetPoints.textContent = points + ' 分';
  if (greetRemain) {
    if (next2) greetRemain.textContent = '距 ' + next2.name + ' 还差 ' + Math.max(0, next2.min - points) + ' 分';
    else greetRemain.textContent = '已至巅峰';
  }

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
  // v5.15.15：右上角改为语义图标（见 index.html 的 .stat-ico）——
  //   原来这里是数字徽章，跟卡片中间的大数字重复（11 项 / 11），boss 反馈"不应该是现在这样"
  const statIcons = document.querySelectorAll('.stat-card .stat-ico');
  if (statIcons.length && typeof initIcons === 'function') initIcons(document.getElementById('statRow') || document.body);

  // v5.15.22 D4（boss：电脑端也加点 UI）：近 7 日完成热力条（纯静态，不占 CPU）
  renderWeekStrip();

  // boss 反馈：22:00 提醒提示很没必要 → tipCard 已删整块（HTML+JS），不再渲染
}

/** v5.15.22 D4：近 7 日完成热力条（归档 done_at 按天计数；纯静态、无动画） */
function renderWeekStrip() {
  const el = document.getElementById('weekStripBars');
  if (!el) return;
  const now = new Date();
  const days = [];
  for (let i = 6; i >= 0; i--) days.push(new Date(now.getFullYear(), now.getMonth(), now.getDate() - i));
  const counts = days.map(d => {
    const s = d.getTime(), e = s + 86400000;
    return (archive || []).filter(t => typeof t.done_at === 'number' && t.done_at >= s && t.done_at < e).length;
  });
  const max = Math.max(1, ...counts);
  const week = ['日', '一', '二', '三', '四', '五', '六'];
  el.innerHTML = days.map((d, i) => {
    const h = Math.round(10 + (counts[i] / max) * 38);
    const isToday = i === days.length - 1;
    return `<div class="ws-col" title="${d.getMonth() + 1}月${d.getDate()}日 · 完成 ${counts[i]} 项">
      <div class="ws-bar ${counts[i] ? '' : 'zero'} ${isToday ? 'today' : ''}" style="height:${h}px"></div>
      <div class="ws-num">${counts[i] || ''}</div>
      <div class="ws-day">${week[d.getDay()]}</div>
    </div>`;
  }).join('');
  const sum = document.getElementById('weekStripSum');
  if (sum) sum.textContent = counts.reduce((a, b) => a + b, 0) + ' 项';
}

function renderLevelBadge() {
  const lv = level || levelOf(points);
  // v5.14h.1：顶栏元素已删除（身份卡整张搬到 dashboard greeting）
  // v5.14h.1：勋章 v2 搬到 dashboard 旁头像 — 进度环 / Lv 角标 / 稀有度宝石
  const ringEl = document.getElementById('dashGreetRing');
  if (ringEl) {
    const next = nextLevelOf(points);
    let pct = 0;
    if (next) pct = Math.max(0, Math.min(100, ((points - lv.min) / Math.max(1, next.min - lv.min)) * 100));
    else pct = 100;
    ringEl.style.setProperty('--ring-pct', pct + '%');
    const greetTag = document.getElementById('greetLvTag');
    if (greetTag) greetTag.textContent = 'Lv' + (lv.lv || 1);
    // v5.15.26 P6：给身份卡挂等级 class（装饰用，仅影响边框/光晕）
    const greetCard = document.getElementById('dashGreeting');
    if (greetCard) {
      greetCard.className = greetCard.className.replace(/\s*rank-lv\d+/g, '') + ' rank-lv' + (lv.lv || 1);
    }
    const gem = document.getElementById('greetGem');
    if (gem) {
      const gemPalette = [
        '#B0A890', '#D8D2C0', '#C9A227', '#8CE0C8', '#5BA3D0',  // lv1-5：灰/银/金/青/蓝
        '#B49BE0', '#E07BD0', '#FF8A5B', '#FFE68A', '#FFD97A'   // lv6-10：紫/粉/橙/亮金/炽金
      ];
      const c = gemPalette[(lv.lv || 1) - 1] || '#C9A227';
      gem.style.setProperty('--gem-color', c);
      gem.style.setProperty('--gem-glow', c + 'cc');
    }
  }
  // v5.15.29 L4：徽章改为写进**原头像位**（身份卡左侧环内），不再单列在右侧
  const embSlot = document.getElementById('dashGreetEmblem');
  if (embSlot) {
    embSlot.innerHTML = levelEmblemSvg(lv.lv || 1);
    const embWrap = document.getElementById('dashGreetGfx');
    if (embWrap) embWrap.className = 'dash-greet-avatar dash-greet-emb ' + emblemFxClass(lv.lv || 1);
    embSlot.title = (lv.name || '') + ' · Lv.' + (lv.lv || 1);
  }
  // v5.15.29 L1：Lv.10 专属星空粒子（提速参数 + 流星随机轨迹）
  renderGreetParticles(lv.lv || 1);
  // 头像渲染（profile + dashboard 走 renderUserAvatar）
  renderUserAvatar();
}
function renderLevelCard() {
  const lv = level || levelOf(points);
  const next = nextLevelOf(points);
  // 总览大卡
  // v5.13j：总览大卡设分档 class
  const lch = document.getElementById('levelChar');
  lch.className = 'level-character rank-lv' + (lv.lv || 1);
  document.getElementById('levelChar').innerHTML = svgIcon(lv.ico || 'lv1', 30, 1.7);
  document.getElementById('levelName').textContent = lv.name;
  // 同步 user 头像到三处（顶栏/下午好/profile）
  renderUserAvatar();
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
    { const pp = document.getElementById('profileExpPct'); if (pp) pp.textContent = Math.round(pct) + '%'; }
    // 0 分时空态给引导文案；>0 时显示距下一级具体分数
    if (phint) phint.textContent = points <= 0
      ? '开始你的第一项任务吧'
      : '距下一级（' + next.name + '）还差 ' + Math.max(0, next.min - points) + ' 分';
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
  const _dv = document.getElementById('detailView');
  const _dash = document.getElementById('dashboardView');
  const body = document.getElementById('detailBody');
  // v5.15.28 P10（boss：「点任务看详情时候 详情页面会有一点闪烁」）——
  //   根因：这里先把 detailBody 换成「加载中…」、同时切视图，再 await 异步取数，
  //   数据回来又把整整一屏换掉 → 中间那一帧（空占位）就是肉眼看到的闪。
  //   改成：**先取数、再切视图**，数据和 DOM 全部就绪后一次性替换 → 视觉上零闪烁。
  //   （get_task_detail 是本机调用，几十毫秒内返回，不会让点击显得没反应。）

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

  // v5.15.28 P10：数据已在手，此刻才切视图 + 填内容（一次成帧，不再闪）
  _dv.style.display = '';
  _dash.style.display = 'none';
  document.getElementById('detailTitle') && (document.getElementById('detailTitle').textContent = '任务详情');
  const _editBtn = document.getElementById('detailEdit');
  if (_editBtn) _editBtn.onclick = function () { openEdit(uuid); };
  const _delBtn = document.getElementById('detailDelete');
  if (_delBtn) _delBtn.onclick = function () { deleteTaskWithConfirm(uuid, t.title); };
  document.getElementById('detailName').textContent = t.title;
  document.getElementById('detailSub').innerHTML = `${CAT_LABEL[catOf(t)] || '未分类'}${t.due_at ? ' · 截止 ' + fmtDue(t.due_at) : ''}${t.deadline && t.deadline !== t.due_at ? ' · 期限 ' + fmtDue(t.deadline) : ''}`;
  document.getElementById('detailChips').innerHTML = `
    <span class="chip cat-${catOf(t)}">${CAT_LABEL[catOf(t)] || ''}</span>
    <span class="chip prio-${(t.priority||'m').charAt(0)}">${PRIO_LABEL[t.priority] || ''}</span>
    ${isTrk ? '<span class="chip tracking">追踪中</span>' : ''}
    ${isOverdue(t) ? `<span class="chip overdue">${svgIcon('alert', 10, 2.2)}${overdueText(t)}</span>` : ''}
    ${t.count > 1 ? `<span class="chip" id="dvCountChip">次数 ${t.done_count || 0}/${t.count}</span>` : ''}
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
        ${totalSteps > 0 ? `<span class="gh-progress">${doneSteps} / ${totalSteps} 已完成</span>` : ''}
      </div>
      ${totalSteps === 0 ? '<div id="goalAddEntry" class="goal-item" onclick="addStep(\'' + t.uuid + '\')"><span class="g-play">' + svgIcon('plus',13,2.2) + '</span><span class="g-text" style="opacity:0.7">（尚未拆解步骤 · 点此添加）</span></div>' :
        steps.map(s => `<div class="goal-item ${s.status==='done'?'done':''}" onclick="toggleStep('${t.uuid}','${s.uuid}','${s.status}')" title="点击切换完成状态">
          <span class="g-play ${s.status==='done'?'done':''}">${s.status==='done'?svgIcon('check',13,2.7):svgIcon('ring',13,1.8)}</span>
          <span class="g-text">${esc(s.title)}</span>
          ${s.attr_value ? `<span class="g-attr">${esc(s.attr_label||'')}: ${esc(s.attr_value)}</span>` : ''}
        </div>`).join('')}
      ${totalSteps === 0 ? "" : `
        <div class="goal-add" id="goalAddBtn" onclick="addStep('${t.uuid}')">${svgIcon('plus',13,2.2)} 添加步骤</div>
      </div>`}
    </div>

    ${t.desc ? `<div class="detail-desc">${esc(t.desc)}</div>` : ''}

    <div class="divider"></div>

    <div class="reward-title">完成任务可获得</div>
    <div class="reward-row">
      <div class="reward-item hl"><span class="reward-ico">${svgIcon('coin',20,1.8)}</span><span class="reward-num">+${t.reward_points||10} 积分</span></div>
      <div class="reward-item"><span class="reward-ico">${svgIcon('trend',20,1.9)}</span><span class="reward-num">推进进度</span></div>
    </div>

    <div class="dv-acts">
      ${isTrk
        ? `<div class="dva" data-dva="stop" onclick="untrackTask('${t.uuid}')">
             <span class="dva-b stop">${svgIcon('targetFill', 19)}</span><span class="dva-l">取消追踪</span></div>`
        : `<div class="dva ${canTrack ? 'disabled' : ''}" data-dva="track" ${canTrack ? '' : `onclick="trackTask('${t.uuid}')"`}>
             <!-- v5.15.22 D2（boss：追踪按键图标要和追踪页/侧边栏一致）play 三角 → goal -->
             <span class="dva-b track">${svgIcon('goal', 19, 2.1)}</span>
             <span class="dva-l">${canTrack ? '追踪已满' : '追踪任务'}</span></div>`}
      ${!hasStep
        /* v5.15.21 D8（boss：无步骤的任务直接舍弃中间的推进键，但其他两个键的位置不变）
           —— 用不可见占位保住中间那一格，左右两键不会因少一个键而位移。 */
        ? `<div class="dva ghost" aria-hidden="true"></div>`
        : remainingSteps === 1
          /* v5.15.21 D7（boss：点推进到最后一个步骤时，「推进」和「完成」合成一个椭圆按键，
             按键范围和大小采用两个按键本来的位置）——
             宽度 126px = 48(键) × 2 + 30(gap)，占位与原来两键完全一致，总宽不变。
             点击 = 推进最后一步 + 直接完成任务（一步到位，不用点两次）。 */
          ? `<div class="dva-merged" data-dva="done" onclick="advanceAndComplete('${t.uuid}')" title="这是最后一步：点此推进并完成任务">
               <span class="dva-b">${svgIcon('arrow', 17, 2.4)}${svgIcon('check', 17, 2.8)}</span>
               <span class="dva-l">推进并完成</span></div>`
          : `<div class="dva" data-dva="advance" onclick="advanceFromDetail('${t.uuid}')">
               <span class="dva-b adv">${svgIcon('arrow', 19, 2.3)}</span>
               <span class="dva-l">推进步骤</span></div>`}
      ${(hasStep && remainingSteps === 1) ? '' : `
      <div class="dva ${(hasStep && !allStepDone) ? 'disabled' : ''}" data-dva="done"
           ${(hasStep && !allStepDone) ? '' : `onclick="completeTask('${t.uuid}')"`}
           title="${(hasStep && !allStepDone) ? '还有 ' + remainingSteps + ' 个步骤未完成' : '完成任务'}">
        <span class="dva-b done">${svgIcon((hasStep && !allStepDone) ? 'lock' : 'check', 19, 2.6)}</span>
        <span class="dva-l">${(hasStep && !allStepDone) ? '还需 ' + remainingSteps + ' 步' : '完成任务'}</span></div>`}
    </div>
  `;
};

/** v5.15.21 D7：合并键「推进并完成」—— 先把最后一步标记完成，再完成整个任务。
 *  分成两步是因为后端 advance_step 只改步骤状态、不结算任务；
 *  即便推进失败也继续尝试完成任务（避免卡死）。 */
window.advanceAndComplete = async function (uuid) {
  try {
    const d = await call('get_task_detail', { taskUuid: uuid });
    const steps = (d && d.steps) || [];
    const next = steps.find(s => s.status !== 'done');
    if (next) await call('advance_step', { stepUuid: next.uuid, taskUuid: uuid, status: 'done' });
  } catch (e) { /* 推进失败不阻断完成 */ }
  await completeTask(uuid);
};

/** 详情页「推进步骤」：把第一个未完成步骤置为 done */
window.advanceFromDetail = async function (uuid) {
  const d = await call('get_task_detail', { taskUuid: uuid });
  const steps = (d && d.steps) || [];
  const next = steps.find(s => s.status !== 'done');
  if (!next) { showToast('这个任务还没有步骤，没有可推进的进度'); return; }
  await call('advance_step', { stepUuid: next.uuid, taskUuid: uuid, status: 'done' });
  showToast('已推进：' + next.title);
  await render();
  openDetail(uuid);
};

// v5.15.16：删除任务（带二次确认，防误删）
window.deleteTaskWithConfirm = async function (uuid, title) {
  if (!window.confirm('确定删除「' + (title || '这个任务') + '」吗？\n删除后无法恢复。')) return;
  try {
    await call('delete_task', { taskUuid: uuid });
    showToast('已删除');
  } catch (e) { showToast('删除失败：' + (e.message || e)); return; }
  if (typeof closeDetail === 'function') closeDetail();
  await render();
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
  _pinTracking = false;   // v5.15.18 K1：当次不置顶（下次进页面才挪上去）
  await render();
  openDetail(uuid);
};
window.untrackTask = async function(uuid) {
  await call('stop_tracking', { taskUuid: uuid });
  // v5.15.21 P3（boss）：取消追踪也**不要当场把它挪回分类区** ——
  //   与 trackTask 的"当次不置顶"对称：这一下只让它脱离置顶卡（视觉上消失追踪态），
  //   位置保持到**下次进入该页面**才重排，否则用户点完取消，任务突然跳到别处会以为丢了。
  _pinTracking = false;
  await render();
  openDetail(uuid);
};
window.completeTask = async function(uuid) {
  // 提前取 title/points（render 之后可能从 today 列表过滤掉已完成任务）
  const before = (typeof tasks !== 'undefined' ? tasks : []).find(t => t.uuid === uuid);
  const title = before ? before.title : '';
  const basePoints = (before && before.reward_points) || 0;
  const r = await call('complete_task', { taskUuid: uuid });
  // v5.14h.4：次数任务未满（partial=true）→ 不关详情页（boss：用户可能一次点多次完成，要能连续点）
  const isPartial = r && r.partial === true && r.done_count !== undefined && r.count && r.done_count < r.count;
  if (!isPartial) closeDetail();
  if (isPartial) {
    // v5.15.18 B1（boss）：「次数任务完成中间次数的时候，任务详情会刷新一下，不要这一下刷新，
    //   弹个获得积分提示就行」。
    //   原实现是 await render()（整页重建）+ openDetail()（再建一次详情）→ 详情肉眼可见地闪一下。
    //   改为：只静默取数（不碰 DOM）+ 就地改详情里的「次数 N/M」文本 + 弹提示。
    await fetchAll();
    const tpEl2 = document.getElementById('totalPoints');
    if (tpEl2) tpEl2.textContent = points;
    const chipEl = document.getElementById('dvCountChip');
    if (chipEl) chipEl.textContent = '次数 ' + (r.done_count || 0) + '/' + r.count;
    showToast('进度 +1 · 已完成 ' + r.done_count + ' / ' + r.count + ' 次');
    return;
  }
  // 刷新主面板（积分/进度/列表）
  await render();
  // v5.12 P0：今日已打卡（already_done=true，防刷分）→ 不弹奖励、不写今日奖励
  if (r && r.already_done) {
    showToast('今日已完成 ✓');
    return;
  }
  // boss 要"完成任务弹奖励提示"（原神风）：每次真正完成（partial=false）都弹
  if (r && r.partial === false) {
    // v5.11.8 即时庆祝+暴击：后端返回 final_exp（暴击时为 base×2），前端按此弹
    const habitPts = (r.habit && !r.final_exp) ? 5 : (r.final_exp || basePoints);
    showBless({ mode: 'reward', title: title, points: habitPts, isCritical: !!r.is_critical });
    // boss D：把今日奖励存 sessionStorage，设置页"查看今日奖励"可回放
    try {
      const key = 'todayRewards_' + new Date().toDateString();
      const list = JSON.parse(sessionStorage.getItem(key) || '[]');
      list.push({ title, points: habitPts, at: Date.now() });
      sessionStorage.setItem(key, JSON.stringify(list));
    } catch(e) {}
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
  } else if (opts.mode === 'levelup') {
    // v5.14g：等级升级庆祝（金色光晕 + 徽章特效）
    const lv = level || levelOf(points);
    const overlay = document.getElementById('blessOverlay');
    overlay.classList.add('bless-levelup');
    tEl.textContent = opts.title || '恭喜升级！';
    sEl.textContent = (lv ? 'Lv.' + lv.lv + ' · ' + lv.name : '') + ' · 当前积分 ' + points;
    const lvIcon = lv && lv.ico ? svgIcon(lv.ico || 'lv1', 44, 1.4) : svgIcon('star', 40, 1.5);
    mEl.innerHTML = '<div class="bless-lv-icon rank-lv' + (lv.lv || 1) + '">' + lvIcon + '</div>' +
      '<b>新的等级已解锁</b><br>' + (lv.title ? '称号：' + lv.title : '继续加油，冲击下一级') +
      '<div class="bless-crit-badge">✦ 升级奖励 ×' + Math.min(10, (lv.lv || 1)) + ' 积分</div>';
    setTimeout(() => overlay.classList.remove('bless-levelup'), 2500);
  } else if (opts.mode === 'reward') {
    // 单任务完成奖励（原神风：+N 经验）
    const pts = opts.points || 0;
    const title = opts.title || '本回合';
    const isCrit = !!opts.isCritical;
    tEl.textContent = '+ ' + pts + ' 经验';
    sEl.textContent = title;
    const critBadge = isCrit ? '<div class="bless-crit-badge">✨ ×2 暴击！</div>' : '';
    // v5.14d：boss 反馈啰嗦"这一小步已被记下" → 简化文案为单行"任务已完成"
    mEl.innerHTML = '<b>任务已完成</b>' + critBadge;
    // v5.11.8 即时庆祝：暴击时弹金色光晕 + 10 颗小金粒散开
    const overlay = document.getElementById('blessOverlay');
    if (isCrit) {
      overlay.classList.add('bless-critical');
      spawnBlessParticles(overlay);
      setTimeout(() => overlay.classList.remove('bless-critical'), 1500);
    } else {
      overlay.classList.remove('bless-critical');
    }
  } else {
    tEl.textContent = '完成';
    sEl.textContent = '';
    mEl.innerHTML = '继续保持';
  }
  document.getElementById('blessOverlay').style.display = '';
}
// v5.11.8 暴击粒子：10 颗小金粒从中心向四周飞散 + 缩放 + 渐隐，0.9s 后移除
function spawnBlessParticles(container) {
  for (let i = 0; i < 10; i++) {
    const p = document.createElement('div');
    p.className = 'bless-particle';
    const angle = (Math.PI * 2 * i) / 10 + (Math.random() * 0.4);
    const dist = 90 + Math.random() * 70;
    p.style.setProperty('--tx', Math.cos(angle) * dist + 'px');
    p.style.setProperty('--ty', Math.sin(angle) * dist + 'px');
    p.style.animationDelay = (Math.random() * 80) + 'ms';
    container.appendChild(p);
    setTimeout(() => p.remove(), 1500);
  }
}
document.getElementById('blessClose').addEventListener('click', () => {
  document.getElementById('blessOverlay').style.display = 'none';
});

// 步骤推进
window.toggleStep = async function(taskUuid, stepUuid, curStatus) {
  const next = curStatus === 'done' ? 'todo' : 'done';
  await call('advance_step', { taskUuid, stepUuid, status: next });
  // v5.13：步骤完成切回主页——全部步骤都 done 时视同任务完成，
  // 走 completeTask 路径（弹奖励 + closeDetail + render 回主页）
  if (next === 'done') {
    const d = await call('get_task_detail', { taskUuid });
    const steps = d.steps || [];
    const allDone = steps.length > 0 && steps.every(s => s.status === 'done');
    if (allDone) {
      const r = await call('complete_task', { taskUuid });
      closeDetail();
      await render();
      if (r && r.already_done) { showToast('今日已完成 ✓'); return; }
      if (r && r.partial === false) {
        const before = (typeof tasks !== 'undefined' ? tasks : []).find(t => t.uuid === taskUuid);
        const basePoints = (before && before.reward_points) || 0;
        const habitPts = (r.habit && !r.final_exp) ? 5 : (r.final_exp || basePoints);
        showBless({ mode: 'reward', title: before ? before.title : '本回合', points: habitPts, isCritical: !!r.is_critical });
      }
      return;
    }
  }
  openDetail(taskUuid);
  render();
};
// 添加步骤 inline 版：原生 prompt 在 Tauri WebView2 里被禁用，会出现"按了没反应"的假死，
// 改成在目标任务的步骤列表里就地插入一行输入框，按回车 / 点"添加"提交，Esc/取消收起。
// v5.13：boss 反馈无步骤任务的 +（尚未拆解步骤）入口点了没反应——
//   原代码依赖 .goal-tools，但无步骤时该容器可能不在视口或被 openDetail 异步时序遮蔽；
//   改为"找到入口元素 → 直接插到它后面"（不依赖 .goal-tools 一定存在）
window.showInlineAddStep = function(taskUuid) {
  if (document.getElementById('inlineStepRow')) return;          // 已展开就别重开
  // 1) 找到 + 入口元素（detailHtml 里"（尚未拆解步骤 · 点此添加）"那条 goal-item 标了 id）
  //    或找 .goal-tools 入口按钮（已有步骤时的 + 添加入口）
  let anchor = document.getElementById('goalAddEntry') || document.getElementById('goalAddBtn');
  if (!anchor) {
    // 兜底：找 .goal-tools 作为最后退路（兼容原行为）
    anchor = document.querySelector('#detailView .goal-tools');
  }
  if (!anchor) return;  // 找不到任何锚点（detail view 还没渲染好）— 直接放弃
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
  anchor.parentNode.insertBefore(row, anchor.nextSibling);
  const inp = document.getElementById('inlineStepInput');
  inp.focus();
  inp.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); submitInlineStep(taskUuid); }
    else if (e.key === 'Escape') { cancelInlineStep(); }
  });
  // boss：JSON 入口改用 addEventListener。v5.14h.13：点 JSON 小字只开弹窗、不收起行
  //   （之前 openJsonImport + cancelInlineStep 同步执行，若弹窗未及渲染视觉上=点了没反应/像收起）
  const entry = row.querySelector('.gap-json-entry');
  if (entry) entry.addEventListener('click', (ev) => {
    ev.stopPropagation();
    try { openJsonImport(taskUuid); } catch (e) { console.error('openJsonImport failed:', e); }
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
  // v5.15.16：不再预填示例（boss：示例应该当灰字提示，一输入就消失、清空又出现）
  area.value = '';
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

// =============== 新建 / 编辑任务表单（v5.15.12 重做） ===============
// boss 反馈整批：① 类型切换后不该再让用户选无关项（限时任务别出现次数、目标任务默认不限时…）
// ② 提醒方式要按端区分（选「仅电脑端」下面却在显示手机端的振动/铃声）
// ③ 次数任务直接填次数，不要「默认 N 次 · 修改」两段式
// ④ 新建/编辑都要能写备注（手机端早就有，桌面端一直缺）
const addOverlay = document.getElementById('addOverlay');
let draftCat = null;
let draftDdl = 'none';
let draftPrio = 'medium';
let editingUuid = null;        // null = 新建，非空 = 编辑该任务
let draftDevice = 'none';      // none / mobile / desktop / both
let draftRemMin = 15;          // 提前多少分钟提醒

const DDL_OPTS = [
  ['5min', '5 分钟'], ['60min', '1 小时'], ['6h', '6 小时'], ['1d', '1 天'],
  ['3d', '3 天'], ['7d', '7 天'], ['30d', '30 天'], ['custom', '自定义']
];
const CAT_TIP = {
  'daily': '每天固定时间提醒，第二天自动回到待办',
  'goal': '为目标坚持推进；默认不限时，也可以设定限时',
  'time-limited': '必须选择限时时长，到期前会提醒',
  'once': '每完成一次记一次数，满次数结算'
};

/** 限时时长按钮：限时任务不含「不限」；目标任务含「不限」且默认选中 */
function renderDdlRow(forCat) {
  const wrap = document.getElementById('addDdlWrap');
  const row = document.getElementById('addDdlRow');
  const label = document.getElementById('addDdlLabel');
  const opts = (forCat === 'time-limited') ? DDL_OPTS : [['none', '不限']].concat(DDL_OPTS);
  if (forCat === 'time-limited' && draftDdl === 'none') draftDdl = '60min';
  row.innerHTML = opts.map(function (o) {
    return '<button type="button" class="ddl-btn ' + (draftDdl === o[0] ? 'active' : '') + '" data-ddl="' + o[0] + '">' + o[1] + '</button>';
  }).join('');
  label.innerHTML = (forCat === 'time-limited') ? '限时时长 <span class="must">*</span>' : '限时时长（可选）';
  wrap.style.display = '';
  const custom = document.getElementById('addDdlCustom');
  if (custom) custom.classList.toggle('hidden', draftDdl !== 'custom');
  row.querySelectorAll('.ddl-btn').forEach(function (b) {
    b.addEventListener('click', function () {
      draftDdl = b.dataset.ddl;
      row.querySelectorAll('.ddl-btn').forEach(function (x) { x.classList.toggle('active', x === b); });
      const c = document.getElementById('addDdlCustom');
      if (c) c.classList.toggle('hidden', draftDdl !== 'custom');
    });
  });
}

/** v5.15.31：每日提醒时间改为「时」「分」两个输入框 —— 读写与校验都收在这里 */
function _clamp2(v, max) {
  const n = parseInt(String(v).replace(/[^0-9]/g, ''), 10);
  if (isNaN(n)) return '';
  return String(Math.min(max, Math.max(0, n))).padStart(2, '0');
}
function _setDailyTime(h, m) {
  const eh = document.getElementById('addDailyHour');
  const em = document.getElementById('addDailyMin');
  if (!eh || !em) return;
  eh.value = String(Math.min(23, Math.max(0, h | 0))).padStart(2, '0');
  em.value = String(Math.min(59, Math.max(0, m | 0))).padStart(2, '0');
}
function _readDailyTime() {
  const eh = document.getElementById('addDailyHour');
  const em = document.getElementById('addDailyMin');
  const h = eh ? parseInt(eh.value, 10) : NaN;
  const m = em ? parseInt(em.value, 10) : NaN;
  return [isNaN(h) ? 9 : Math.min(23, Math.max(0, h)), isNaN(m) ? 0 : Math.min(59, Math.max(0, m))];
}
/** 只在首次进入「每日任务」时绑一次：失焦即补零并夹到合法范围；时填满 2 位自动跳到分 */
let _dailyTimeBound = false;
function _bindDailyTimeInput() {
  if (_dailyTimeBound) return;
  const eh = document.getElementById('addDailyHour');
  const em = document.getElementById('addDailyMin');
  if (!eh || !em) return;
  _dailyTimeBound = true;
  const norm = function (el, max) {
    el.addEventListener('focus', function () { el.select(); });
    el.addEventListener('input', function () {
      el.value = el.value.replace(/[^0-9]/g, '').slice(0, 2);
      if (el === eh && el.value.length === 2 && parseInt(el.value, 10) <= 2) em.focus();
    });
    el.addEventListener('blur', function () {
      const v = _clamp2(el.value, max);
      el.value = (v === '') ? '' : v;
    });
  };
  norm(eh, 23);
  norm(em, 59);
}

/** 类型联动：按类型显示/隐藏 时长 / 每日提醒时间 / 次数 */
function applyCatLayout() {
  const cat = draftCat;
  const ddlWrap = document.getElementById('addDdlWrap');
  document.getElementById('addCatHint').textContent = CAT_TIP[cat] || '';
  if (cat === 'time-limited') renderDdlRow('time-limited');
  else if (cat === 'goal') renderDdlRow('goal');
  else ddlWrap.style.display = 'none';
  document.getElementById('addDailyWrap').style.display = (cat === 'daily') ? '' : 'none';
  document.getElementById('addCountWrap').style.display = (cat === 'once') ? '' : 'none';
  // v5.15.18 G2（boss 确认）：每日任务已有"每天提醒时间"（固定点循环提醒），
  //   下面那套通用「提醒」（选端/方式/提前量）语义重复 → 类型=每日任务时整块隐藏。
  const remWrap = document.getElementById('addRemindWrap');
  if (remWrap) remWrap.style.display = (cat === 'daily') ? 'none' : '';
  // v5.15.18 G1：每天提醒时间的"可输入"绑定（只绑一次）
  if (cat === 'daily' && typeof _bindDailyTimeInput === 'function') _bindDailyTimeInput();
}

function addSelectCat(cat) {
  draftCat = cat;
  // v5.15.28 P8（boss：「目标任务 无论是编辑还是新建都默认不限时」）——
  //   切到「目标」时把时长重置为「不限」（切换类型时不再沿用上一个类型选过的时长）
  if (cat === 'goal') draftDdl = 'none';
  document.querySelectorAll('#addCatRow .cat-btn').forEach(function (x) { x.classList.toggle('active', x.dataset.cat === cat); });
  document.getElementById('addSubmit').disabled = false;
  // 限时任务：禁掉「不提醒」（按钮变淡 + 不可点）
  const noneBtn = document.querySelector('#addOverlay .rem-device-btn[data-device="none"]');
  if (noneBtn) {
    const ban = (cat === 'time-limited');
    noneBtn.classList.toggle('banned', ban);
    noneBtn.title = ban ? '限时任务必须提醒' : '';
  }
  if (cat === 'time-limited' && draftDevice === 'none') _addRemSet('mobile');
  applyCatLayout();
}

function _addRemSet(dev) {
  // v5.15.13：限时任务必须提醒（有时限却没人提醒就没意义）
  if (dev === 'none' && draftCat === 'time-limited') {
    showToast('限时任务需要提醒，已为你保留提醒设置');
    return;
  }
  draftDevice = dev;
  document.querySelectorAll('#addOverlay .rem-device-btn').forEach(function (x) { x.classList.toggle('active', x.dataset.device === dev); });
  const show = dev !== 'none';
  document.getElementById('addRemStrength').style.display = show ? '' : 'none';
  document.getElementById('addRemTime').style.display = show ? '' : 'none';
  // v5.15.12：提醒方式按端区分（boss：选电脑端/双端时下面却只有手机端的振动、铃声）
  document.getElementById('addRemMobileGroup').style.display = (dev === 'mobile' || dev === 'both') ? '' : 'none';
  document.getElementById('addRemPcGroup').style.display = (dev === 'desktop' || dev === 'both') ? '' : 'none';
  settings.reminder_default_range = dev;
}

function _addRemTimeSet(min) {
  draftRemMin = min;
  document.querySelectorAll('#addOverlay .rem-time-btn').forEach(function (x) {
    x.classList.toggle('active', parseInt(x.dataset.min, 10) === min);
  });
  // v5.15.12：预设与「自定义」联动填充（点 1 小时前 → 自定义 = 1 / 小时前）
  const num = document.getElementById('addRemCustomNum');
  const unit = document.getElementById('addRemCustomUnit');
  if (num && unit) {
    if (min <= 0) { num.value = ''; unit.value = '60'; }
    else if (min % 1440 === 0) { num.value = min / 1440; unit.value = '1440'; }
    else if (min % 60 === 0) { num.value = min / 60; unit.value = '60'; }
    else { num.value = min; unit.value = '1'; }
  }
}

function resetAddForm() {
  draftCat = null; draftDdl = 'none'; draftPrio = 'medium';
  document.getElementById('addTitle').value = '';
  document.getElementById('addDesc').value = '';
  document.getElementById('addCount').value = settings.count_default || 5;
  _setDailyTime(9, 0);
  document.querySelectorAll('#addCatRow .cat-btn').forEach(function (b) { b.classList.remove('active'); });
  document.querySelectorAll('.prio-btn').forEach(function (b) { b.classList.toggle('active', b.dataset.prio === 'medium'); });
  ['addRemNotif', 'addRemVibrate', 'addRemSound', 'addRemPopup', 'addRemFull', 'addRemInApp'].forEach(function (id) {
    const el = document.getElementById(id); if (el) el.checked = false;
  });
  document.getElementById('addRemStrength').style.display = 'none';
  document.getElementById('addRemTime').style.display = 'none';
  document.getElementById('addDdlWrap').style.display = 'none';
  document.getElementById('addDailyWrap').style.display = 'none';
  document.getElementById('addCountWrap').style.display = 'none';
  document.getElementById('addDdlCustom').classList.add('hidden');
  _addRemTimeSet(15);
}

function openAdd() {
  resetAddForm();
  editingUuid = null;
  document.getElementById('addModalTitle').textContent = '新建任务';
  document.getElementById('addSubmit').textContent = '创建';
  document.getElementById('addCatHint').textContent = '';
  document.getElementById('addSubmit').disabled = true;
  // v5.15.14：按设置里的「手机端/电脑端默认提醒方式」预勾选（不再一律空着）
  const _d = (k, def) => (settings[k] !== undefined ? !!settings[k] : def);
  document.getElementById('addRemNotif').checked = _d('rem_adv_notif', true);
  document.getElementById('addRemVibrate').checked = _d('rem_adv_vibrate', true);
  document.getElementById('addRemSound').checked = _d('rem_adv_sound', true);
  document.getElementById('addRemPopup').checked = _d('rem_pc_adv_popup', true);
  document.getElementById('addRemFull').checked = _d('rem_pc_adv_fullscreen', false);
  document.getElementById('addRemInApp').checked = _d('rem_pc_adv_inapp', true);
  _addRemSet(settings.reminder_default_range || 'none');
  addOverlay.style.display = '';
  setTimeout(function () { document.getElementById('addTitle').focus(); }, 50);
}

/** 解析 reminder_strength：{"ch":"notify,popup","scope":"both","min":60} */
function parseReminderConfig(raw) {
  const out = { ch: [], device: 'none', min: 15 };
  if (!raw) return out;
  try {
    const o = (typeof raw === 'string') ? JSON.parse(raw) : raw;
    if (o && o.ch) out.ch = String(o.ch).split(',').filter(Boolean);
    if (o && o.scope) out.device = o.scope;
    if (o && typeof o.min === 'number') out.min = o.min;
  } catch (e) { }
  return out;
}

/** 编辑已有任务：同一个弹窗回填字段 */
async function openEdit(uuid) {
  const d = await call('get_task_detail', { taskUuid: uuid });
  const t = d && d.task; if (!t) return;
  resetAddForm();
  editingUuid = uuid;
  document.getElementById('addModalTitle').textContent = '编辑任务';
  document.getElementById('addSubmit').textContent = '保存';
  document.getElementById('addSubmit').disabled = false;
  document.getElementById('addTitle').value = t.title || '';
  document.getElementById('addDesc').value = t.desc || '';
  draftPrio = t.priority || 'medium';
  document.querySelectorAll('.prio-btn').forEach(function (b) { b.classList.toggle('active', b.dataset.prio === draftPrio); });
  draftCat = catOf(t);
  document.querySelectorAll('#addCatRow .cat-btn').forEach(function (b) { b.classList.toggle('active', b.dataset.cat === draftCat); });
  if (t.count > 1) document.getElementById('addCount').value = t.count;
  const dl = t.deadline || t.due_at;
  if (draftCat === 'daily') draftDdl = 'none';
  else if (dl) {
    const left = dl - Date.now();
    const cands = [['5min', 300000], ['60min', 3600000], ['6h', 21600000], ['1d', 86400000], ['3d', 259200000], ['7d', 604800000], ['30d', 2592000000]];
    let best = 'custom', bd = Infinity;
    cands.forEach(function (c) { const dd = Math.abs(c[1] - left); if (dd < bd) { bd = dd; best = c[0]; } });
    draftDdl = (bd < 3600000) ? best : 'custom';
    if (draftDdl === 'custom') {
      document.getElementById('ddlCustomNum').value = Math.max(1, Math.round(left / 3600000));
      document.getElementById('ddlCustomUnit').value = 'hour';
    }
  } else draftDdl = 'none';
  if (draftCat === 'daily' && t.due_at) {
    const dt = new Date(t.due_at);
    _setDailyTime(dt.getHours(), dt.getMinutes());
  }
  applyCatLayout();
  const cfg = parseReminderConfig(t.reminder_strength);
  _addRemSet(cfg.device);
  document.getElementById('addRemNotif').checked = cfg.ch.indexOf('notify') >= 0;
  document.getElementById('addRemVibrate').checked = cfg.ch.indexOf('vibrate') >= 0;
  document.getElementById('addRemSound').checked = (cfg.ch.indexOf('ring') >= 0 || cfg.ch.indexOf('beep') >= 0);
  document.getElementById('addRemPopup').checked = cfg.ch.indexOf('popup') >= 0;
  document.getElementById('addRemFull').checked = cfg.ch.indexOf('fullscreen') >= 0;
  document.getElementById('addRemInApp').checked = cfg.ch.indexOf('inapp') >= 0;
  if ([0, 15, 60, 1440].indexOf(cfg.min) >= 0) _addRemTimeSet(cfg.min);
  else {
    draftRemMin = cfg.min;
    document.getElementById('addRemCustomNum').value = cfg.min;
    document.getElementById('addRemCustomUnit').value = '1';
    document.querySelectorAll('#addOverlay .rem-time-btn').forEach(function (x) { x.classList.remove('active'); });
  }
  addOverlay.style.display = '';
}
window.openEdit = openEdit;

function closeAdd() { addOverlay.style.display = 'none'; }
document.getElementById('fabAdd').addEventListener('click', openAdd);
document.querySelectorAll('[data-close-overlay="addOverlay"]').forEach(function (b) { b.addEventListener('click', closeAdd); });

// v5.14e：主窗+挂件拖动 JS 兜底（lib.rs win_start_dragging IPC 已加，data-tauri-drag-region 在 WebView2 偶发失效）
//   mousedown 时调 IPC，OS 进入 native 拖动循环（Win10/11 适配）
function bindDragFallback(selector, label) {
  const el = document.querySelector(selector);
  if (!el) return;
  let downX, downY, downT;
  el.addEventListener('mousedown', (e) => {
    downX = e.screenX; downY = e.screenY; downT = Date.now();
  });
  el.addEventListener('mouseup', (e) => {
    if (Date.now() - downT > 150) return;          // 长按说明真在拖，不算 click
    const dx = Math.abs(e.screenX - downX), dy = Math.abs(e.screenY - downY);
    if (dx < 4 && dy < 4) {
      // 短按 = 点击，不走拖动
    }
  });
  // 真正的拖动触发：mousedown 后 100ms 内若 mousedown 还在 + mousemove > 3px，调 IPC
  el.addEventListener('mousedown', (e) => {
    const startX = e.screenX, startY = e.screenY;
    const timer = setTimeout(() => {
      const onMove = (mv) => {
        if (Math.abs(mv.screenX - startX) > 3 || Math.abs(mv.screenY - startY) > 3) {
          clearTimeout(timer);
          document.removeEventListener('mousemove', onMove);
          if (window.__TAURI__ && window.__TAURI__.core) {
            window.__TAURI__.core.invoke('win_start_dragging', { label }).catch(() => {});
          }
        }
      };
      document.addEventListener('mousemove', onMove);
    }, 100);
    const cleanup = () => clearTimeout(timer);
    el.addEventListener('mouseup', cleanup, { once: true });
    el.addEventListener('mouseleave', cleanup, { once: true });
  });
}
bindDragFallback('#topbar', 'main');
// widget 单独绑（widget.html 也在 ui/ 下但脚本不同）

// =============== 表单交互与提交（v5.15.12） ===============
document.querySelectorAll('#addCatRow .cat-btn').forEach(function (b) {
  b.addEventListener('click', function () { addSelectCat(b.dataset.cat); });
});
document.querySelectorAll('.prio-btn').forEach(function (b) {
  b.addEventListener('click', function () {
    draftPrio = b.dataset.prio;
    document.querySelectorAll('.prio-btn').forEach(function (x) { x.classList.toggle('active', x === b); });
  });
});
document.querySelectorAll('#addOverlay .rem-time-btn').forEach(function (b) {
  b.addEventListener('click', function () { _addRemTimeSet(parseInt(b.dataset.min, 10)); });
});
function syncRemCustom() {
  const n = Math.max(1, parseInt(document.getElementById('addRemCustomNum').value, 10) || 0);
  const f = parseInt(document.getElementById('addRemCustomUnit').value, 10) || 1;
  if (n > 0) {
    draftRemMin = n * f;
    document.querySelectorAll('#addOverlay .rem-time-btn').forEach(function (x) { x.classList.remove('active'); });
  }
}
document.getElementById('addRemCustomNum').addEventListener('input', syncRemCustom);
document.getElementById('addRemCustomUnit').addEventListener('change', syncRemCustom);

function collectReminder() {
  const ch = [];
  if (draftDevice === 'mobile' || draftDevice === 'both') {
    if (document.getElementById('addRemNotif').checked) ch.push('notify');
    if (document.getElementById('addRemVibrate').checked) ch.push('vibrate');
    if (document.getElementById('addRemSound').checked) ch.push('ring');
  }
  if (draftDevice === 'desktop' || draftDevice === 'both') {
    if (document.getElementById('addRemPopup').checked) ch.push('popup');
    if (document.getElementById('addRemFull').checked) ch.push('fullscreen');
    if (document.getElementById('addRemInApp').checked) ch.push('inapp');
  }
  return JSON.stringify({ ch: ch.join(','), scope: draftDevice, min: draftRemMin });
}

/** 每日任务 → 今天（已过则明天）的 HH:mm 时间戳 */
function collectDailyDueAt() {
  const hm = _readDailyTime();
  const d = new Date();
  d.setHours(hm[0], hm[1], 0, 0);
  if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1);
  return d.getTime();
}

document.getElementById('addSubmit').addEventListener('click', async function () {
  const title = document.getElementById('addTitle').value.trim();
  // v5.15.13：缺标题/类型时给明确提示（原来点了没反应，用户不知道差什么）
  if (!title) { showToast('给任务起个名字吧'); document.getElementById('addTitle').focus(); return; }
  if (!draftCat) { showToast('请先选择任务类型'); return; }
  let deadlineKey = 'none';
  if (draftCat === 'time-limited' || draftCat === 'goal') deadlineKey = draftDdl;
  if (draftCat === 'time-limited' && deadlineKey === 'none') { showToast('限时任务必须选择限时时长'); return; }
  if (deadlineKey === 'custom') {
    const num = Math.max(1, parseInt(document.getElementById('ddlCustomNum').value, 10) || 0);
    if (num <= 0) { showToast('请填写自定义时长'); return; }
    const unit = document.getElementById('ddlCustomUnit').value;
    const factor = unit === 'day' ? 86400000 : unit === 'hour' ? 3600000 : 60000;
    deadlineKey = 'custom:' + (num * factor);
  }
  const submitCount = (draftCat === 'once') ? Math.max(1, parseInt(document.getElementById('addCount').value, 10) || 1) : 1;
  const payload = {
    title: title, category: draftCat, deadlineKey: deadlineKey, priority: draftPrio,
    count: submitCount,
    desc: document.getElementById('addDesc').value.trim(),
    reminder: collectReminder(),
    remindMin: draftRemMin,
    dueAt: (draftCat === 'daily') ? collectDailyDueAt() : null
  };
  try {
    if (editingUuid) await call('update_task', Object.assign({ taskUuid: editingUuid }, payload));
    else await call('add_task', payload);
  } catch (e) { showToast('保存失败：' + (e.message || e)); return; }
  const keep = editingUuid;
  closeAdd();
  editingUuid = null;
  await render();
  if (keep) openDetail(keep);
});

// =============== 设置抽屉 ===============
document.getElementById('settingsBtn').addEventListener('click', () => {
  applySettingsToUi();
  document.getElementById('settingsOverlay').style.display = '';
  // v5.15.12：打开设置页时若已配对但未连接，短时间内自动扫描重连
  if (settings.pairing) autoScanAndReconnect(3);
  bindSetting('setEtaShort', 'eta_short_pct', v => Math.max(1, Math.min(100, parseInt(v) || 30)));
  bindSetting('setEtaLong', 'eta_long_h', v => Math.max(1, Math.min(240, parseInt(v) || 36)));
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
// v5.13k：把'字符占位'升级为'lucide 内置头像'（8 个真实矢量图形，冒险者主题）。
//   上传的自定义图（settings.avatar_img base64）仍优先于内置头像。
//   选头像 → settings.avatar_idx；上传图 → settings.avatar_img。
// v5.14h.14：融合手机端 emoji 动物库（8 lucide + 8 emoji = 16 个随机池）
const AVATAR_GLYPHS = [
  { name: '火焰', kind: 'svg',  ico: 'flame'    },
  { name: '星光', kind: 'svg',  ico: 'star'     },
  { name: '王冠', kind: 'svg',  ico: 'crown'    },
  { name: '闪光', kind: 'svg',  ico: 'sparkles' },
  { name: '轨道', kind: 'svg',  ico: 'orbit'    },
  { name: '盾牌', kind: 'svg',  ico: 'shield'   },
  { name: '剑',   kind: 'svg',  ico: 'sword'    },
  { name: '罗盘', kind: 'svg',  ico: 'compass'  },
  // 手机端 8 动物 emoji（v5.15.6 头像选择器已用）+ emoji 速记字符直接渲染
  { name: '狐狸', kind: 'emoji', ico: '\uD83E\uDD8A' },  // 🦊
  { name: '老虎', kind: 'emoji', ico: '\uD83D\uDC2F' },  // 🐯
  { name: '猫头鹰', kind: 'emoji', ico: '\uD83E\uDD89' },  // 🦉
  { name: '狼',   kind: 'emoji', ico: '\uD83D\uDC3A' },  // 🐺
  { name: '熊猫', kind: 'emoji', ico: '\uD83D\uDC3C' },  // 🐼
  { name: '狮子', kind: 'emoji', ico: '\uD83E\uDD81' },  // 🦁
  { name: '龙',   kind: 'emoji', ico: '\uD83D\uDC09' },  // 🐲
  { name: '老鹰', kind: 'emoji', ico: '\uD83E\uDD85' }   // 🦅
];
let avatarIdx = 0;
function pickAvatarGlyph() { return AVATAR_GLYPHS[avatarIdx % AVATAR_GLYPHS.length]; }

function openProfileModal() {
  const lv = level || levelOf(points);
  const next = nextLevelOf(points);
  // v5.13j：profile 大徽章设分档 class（10 档配色）
  // v5.15.31：profileLevelIcon 已随「身份卡+等级卡融合」删除 —— 原先是**裸访问**，
  //   元素没了这里会 throw，直接导致整个个人信息弹窗打不开（铁律 2b）。加空保护。
  const pcIc = document.getElementById('profileLevelIcon');
  if (pcIc) {
    pcIc.className = 'pc-level-icon rank-lv' + (lv.lv || 1);
    pcIc.innerHTML = svgIcon(lv.ico || 'lv1', 36, 1.5);
  }
  document.getElementById('profileLevelName').textContent = lv.name;
  // 同步处理 title 空串隐藏（与上方 renderLevelCard 一致）
  const _pt2 = document.getElementById('profileLevelTitle');
  if (_pt2) { _pt2.textContent = lv.title || ''; _pt2.style.display = lv.title ? '' : 'none'; }
  document.getElementById('profilePointsNum').textContent = points;
  // v5.14h.14：头像选择网格渲染（lucide 用 svgIcon，emoji 用 Text）
  const avatarGrid = document.getElementById('avatarGrid');
  if (avatarGrid && !avatarGrid.dataset.rendered) {
    avatarGrid.innerHTML = AVATAR_GLYPHS.map((a, i) => {
      const icon = a.kind === 'emoji' ? `<span style="font-size:16px;line-height:1">${a.ico}</span>` : svgIcon(a.ico, 18, 1.8);
      return `<div class="ph-av" data-idx="${i}" title="${a.name}">${icon}</div>`;
    }).join('');
    avatarGrid.addEventListener('click', e => {
      const cell = e.target.closest('.ph-av');
      if (!cell) return;
      const i = parseInt(cell.dataset.idx, 10);
      avatarIdx = i;
      settings.avatar_idx = i;
      const g = AVATAR_GLYPHS[i];
      if (g.kind === 'svg') delete settings.avatar_img;        // 选 lucide 时清掉上传图；选 emoji 不清
      saveSettings();
      // v5.14h.15：选 emoji 头像时推送手机同步（手机端 prefs avatar_emoji 监听即时刷新）
      if (g.kind === 'emoji') call('push_avatar_emoji', { emoji: g.ico }).catch(()=>{});
      renderUserAvatar();
      updateAvatarGridActive();
    });
    avatarGrid.dataset.rendered = '1';
  }
  // 同步当前选中的高亮
  if (typeof updateAvatarGridActive === 'function') updateAvatarGridActive();
  // 经验条
  let pct = 100;
  if (next) {
    pct = Math.min(100, Math.max(0, ((points - lv.min) / Math.max(1, lv.max - lv.min)) * 100));
    // 0 分时空态给引导文案；>0 时显示距下一级具体分数
    document.getElementById('profileExpHint').textContent = points <= 0
      ? '开始你的第一项任务吧'
      : '距下一级（' + next.name + '）还差 ' + Math.max(0, next.min - points) + ' 分';
  } else {
    pct = 100;
    document.getElementById('profileExpHint').textContent = '已至巅峰，满级成就达成';
    { const pp = document.getElementById('profileExpPct'); if (pp) pp.textContent = '100%'; }
  }
  document.getElementById('profileExpFill').style.width = pct + '%';
  // v5.15.31：进度百分比落在进度条右上角
  { const pp = document.getElementById('profileExpPct'); if (pp) pp.textContent = Math.round(pct) + '%'; }
  // 配对
  document.getElementById('profilePairStatus').textContent =
    settings.pairing ? ('已配对：' + settings.pairing.url) : '未配对';
  // 昵称（v5.15.29 L5：没编辑过就显示当前等级名）
  document.getElementById('profileNickname').value = activeNickname();
  { const nt = document.getElementById('profileNickText'); if (nt) nt.textContent = activeNickname(); }
  // v5.15.31：常态只显示昵称文字 + 一枚很小的「修改」键，输入框平时藏起来
  _nickExitEdit();
  // v5.15.29 L6：打开时先清掉上一次的提示
  refreshNickWarn(document.getElementById('profileNickname'), document.getElementById('profileNickWarn'));
  // 头像：先用用户自定义图，没有再回退字符
  avatarIdx = (settings.avatar_idx != null) ? settings.avatar_idx : 0;
  renderProfileAvatar();
  // v5.15.14：直接用内存里的每日进度（progressInfo 来自 get_daily_progress，口径与总览一致）；
  //   原来调 get_progress 只统计"近 24h 有截止时间的任务"，界面会显示 0 / 0
  {
    const doneEl = document.getElementById('profileTodayDone');
    const fillEl = document.getElementById('profileTodayFill');
    const hintEl = document.getElementById('profileTodayHint');
    if (doneEl) {
      const dt = (typeof progressInfo !== 'undefined' && progressInfo.done) || 0;
      const tt = (typeof progressInfo !== 'undefined' && progressInfo.total) || 0;
      doneEl.textContent = dt + ' / ' + tt;
      if (fillEl) fillEl.style.width = (tt > 0 ? Math.round(dt / tt * 100) : 0) + '%';
      if (hintEl) hintEl.textContent = tt === 0 ? '今天还没有任务'
        : (dt >= tt ? '今天的任务全部完成' : '还有 ' + (tt - dt) + ' 项待完成');
    }
  }
  document.getElementById('profileOverlay').style.display = '';
}
function closeProfileModal() { document.getElementById('profileOverlay').style.display = 'none'; }
window.openProfileModal = openProfileModal;
window.closeProfileModal = closeProfileModal;
document.querySelectorAll('[data-close-overlay="profileOverlay"]').forEach(b => b.addEventListener('click', closeProfileModal));

// 昵称保存：回车或失焦
const _profileNick = document.getElementById('profileNickname');
// 昵称保存（v5.15.29 L5/L6）
//   · 非空 → 记为用户自定义（nickname_custom='1'）
//   · 清空 → 回到「跟随当前等级名」（nickname_custom=''）
//   · 输入值是**高于当前等级**的等级名 → 卡片内给一句提示，但**照常保存**
function commitNickname() {
  const warnEl = document.getElementById('profileNickWarn');
  const raw = (_profileNick.value || '').trim().slice(0, 12);
  if (!raw) {
    settings.nickname = '';
    settings.nickname_custom = '';
    _profileNick.value = activeNickname();
    saveSettings();
    call('set_setting', { key: 'nickname', value: '' }).catch(() => {});
    call('set_setting', { key: 'nickname_custom', value: '' }).catch(() => {});
    refreshNickWarn(_profileNick, warnEl);
    _nickExitEdit();
    render();
    return;
  }
  settings.nickname = raw;
  settings.nickname_custom = '1';
  saveSettings();
  call('set_setting', { key: 'nickname', value: raw }).catch(() => {});
  call('set_setting', { key: 'nickname_custom', value: '1' }).catch(() => {});
  refreshNickWarn(_profileNick, warnEl);
  _nickExitEdit();
  render();
}
// L6：边输入边提示（只在这张卡里出现）
if (_profileNick) _profileNick.addEventListener('input', () => refreshNickWarn(_profileNick, document.getElementById('profileNickWarn')));
_profileNick.addEventListener('change', commitNickname);
_profileNick.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); _profileNick.blur(); } });
// 昵称"保存"按钮：触发 blur → change → commit
const _profileNickSave = document.getElementById('profileNickSave');
if (_profileNickSave) _profileNickSave.addEventListener('click', () => { if (_profileNick) _profileNick.blur(); });
// v5.15.31：值没改动时 change 不触发 → 用 blur 兜底退出编辑态，避免「输入框一直挂在那儿」
if (_profileNick) _profileNick.addEventListener('blur', () => {
  setTimeout(() => {
    if (document.activeElement !== _profileNick) {
      const i = document.getElementById('profileNickname');
      if (i && i.style.display !== 'none' && typeof _nickExitEdit === 'function') _nickExitEdit();
    }
  }, 160);
});

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

// 头像：自定义图（base64）优先；没有则用 lucide 内置图标（按 avatar_idx）。
// v5.15.29 L3/L4：自定义头像已**存档下架**（UI 不再展示）——
//   个人信息页的原头像位改放等级徽章；数据层字段保留（见 renderUserAvatar 注释）。
function renderProfileAvatar() {
  renderProfileEmblem();
}

// v5.15.29 L4：个人信息页「原头像位」= 等级徽章本体（无外壳，与身份卡环内同一枚）
function renderProfileEmblem() {
  const box = document.getElementById('profileEmblem');
  if (!box) return;
  const lv = (typeof level !== 'undefined' && level) ? level : levelOf(points);
  const n = (lv && lv.lv) || 1;
  box.innerHTML = levelEmblemSvg(n);
  box.className = 'ph-emb ' + emblemFxClass(n);
  const tag = document.getElementById('profileEmblemLv');
  if (tag) tag.textContent = 'Lv.' + n;
  box.title = ((lv && lv.name) || '') + ' · Lv.' + n;
}

// 统一头像渲染：profile .ph-avatar + dashboard 勋章环（顶栏无头像）
// 优先级：settings.avatar_img（上传图） > settings.avatar_idx（lucide 内置） > 等级图标
function renderUserAvatar() {
  // v5.15.29 L3/L4：自定义头像（上传图 settings.avatar_img / 内置头像 settings.avatar_idx）
  //   已**存档下架** —— 顶栏、身份卡、个人信息页三处头像渲染全部停用，原位统一改放等级徽章：
  //     · 身份卡环内（原头像位）→ 由 renderLevelBadge 写入徽章
  //     · 个人信息页（原头像位）→ 由 renderProfileEmblem 写入徽章
  //   数据层（avatar_img / avatar_emoji / avatar_idx 字段 + 同步通道）**完整保留**，
  //   恢复时重贴 UI 即可（下架代码全文见 design/存档-自定义头像-UI代码-v1.md）。
  renderProfileEmblem();
}
function updateAvatarGridActive() {
  document.querySelectorAll('#avatarGrid .ph-av').forEach(c => {
    c.classList.toggle('active', parseInt(c.dataset.idx, 10) === avatarIdx);
  });
}
// v5.14d："换"按钮切换 8 头像选择器折叠（避免 profile modal 拉得很长）
// v5.14g：头像点击 = 换下一个内置头像；长按（800ms）= 展开 8 头像网格；上传走头像下方 hint（无独立按钮）
//   旧"换/图"两小按钮已从 HTML 移除 → 绑定改到头像本体 + 提示条
const _phAvatarEl0 = document.getElementById('profileAvatar');
if (_phAvatarEl0) {
  _phAvatarEl0.addEventListener('click', () => {
    if (settings.avatar_img) return;        // 有图时点击不切（避免误清除）
    avatarIdx = (avatarIdx + 1) % AVATAR_GLYPHS.length;
    settings.avatar_idx = avatarIdx;
    saveSettings();
    renderProfileAvatar();
    renderLevelBadge();
  });
}
// 上传自己的图片：点 hint 文字 → 选文件（head 内嵌按钮删除后，用 hint 上的隐藏 file 触发器）
const _fileTrigger = document.getElementById('profileAvatarHint');
if (_fileTrigger) {
  _fileTrigger.addEventListener('click', () => {
    const f = document.getElementById('profileAvatarFile');
    if (f) f.click();
  });
}
// v5.14d：长按头像（800ms）→ 显示/隐藏 8 头像选择器（boss 反馈"长按头像可改头像"）
let _avatarLongPressTimer = null;
const _profileAvatarEl = document.getElementById('profileAvatar');
if (_profileAvatarEl) {
  _profileAvatarEl.addEventListener('mousedown', () => {
    _avatarLongPressTimer = setTimeout(() => {
      const picker = document.getElementById('avatarPicker');
      if (picker) picker.style.display = picker.style.display === 'none' ? '' : 'none';
    }, 800);
  });
  ['mouseup','mouseleave','touchend','touchcancel'].forEach(ev => {
    _profileAvatarEl.addEventListener(ev, () => {
      if (_avatarLongPressTimer) { clearTimeout(_avatarLongPressTimer); _avatarLongPressTimer = null; }
    });
  });
}
// 上传自己的图片 → 打开裁剪器（v5.14g：head 按钮移除后仍保留该能力，file input change 触发）
// ⚠️ v5.15.29 L3：头像 UI 已**存档下架**（元素从 HTML 移除）→ 这里**必须空保护**：
//   顶层无保护的事件绑定一旦拿到 null 就抛 TypeError → **顶层代码从此中断**，
//   后面的 `DOMContentLoaded` 注册不会执行 → startApp 永远不被调用 → 整页停在静态 HTML
//   （症状：问候语/积分一直是 index.html 里的死值、点什么都没反应）。本版真踩到过。
const _phFileEl = document.getElementById('profileAvatarFile');
if (_phFileEl) _phFileEl.addEventListener('change', e => {
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
      _cropState = { img, scale: s0, s0, x: 0, y: 0 };
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
  // ★ boss #20 修复核心：CDP 实测 srcLen=0 —— 图片从未赋给 DOM img，只存内存里
  //   导致裁剪窗永远空图 + 旧黑背景 = 全黑看不清。必须先赋 src。
  if (el.getAttribute('src') !== c.img.src) el.setAttribute('src', c.img.src);
  // ★ 边界 clamp：boss 反映"不会用"另一层含义——图可拖出圆窗露出白底
  //   限制 x/y 让图片始终覆盖圆窗（不小于 stage 圆直径 = 280）
  //   ★ boss #36 修：去掉 scale >= s0 clamp，让滚轮缩小真正有效（用户主动看更多图）
  const stage = 280;
  const w = c.img.width * c.scale;
  const h = c.img.height * c.scale;
  c.x = Math.max(-(w - stage) / 2, Math.min((w - stage) / 2, c.x));
  c.y = Math.max(-(h - stage) / 2, Math.min((h - stage) / 2, c.y));
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

// 滚轮缩放（boss #36 修：去掉 s0 clamp 让缩小有效；步进 1.06 倍每滚轮一档；scale 范围 [0.1, 8]）
// 之前 v=v77 bug：s0 clamp 让 scale 不能小于 cover 圆尺寸 → 用户感觉"下滑缩小没反应"，
//   且步进 1.1 倍过大 → 稍微一滚就放大多倍
if (_cropFrame) _cropFrame.addEventListener('wheel', e => {
  if (!_cropState || !_cropState.img) return;
  e.preventDefault();
  // WebView2 precision 滚轮一次性触发多个 wheel event；用 Math.exp 让符号与步进一致
  const sign = e.deltaY < 0 ? 1 : -1;
  const factor = Math.exp(sign * 0.06);   // 每档约 6%
  _cropState.scale = Math.max(0.1, Math.min(8, _cropState.scale * factor));
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
  const code = (document.getElementById('setPairCode').value || '').trim();
  if (!url) return;
  // v5.16.0 安全加固：配对必须带手机端显示的 6 位码
  if (!/^\d{6}$/.test(code)) {
    alert('请先在手机端「设置 → 配对」里查看 6 位配对码，填入后再连接');
    document.getElementById('setPairCode').focus();
    return;
  }
  const full = url.startsWith('http') ? url : 'http://' + url;
  try {
    const r = await call('pair_with_code', { url: full, code: code, deviceId: '' });
    if (r !== 'ok') { alert(String(r).replace(/^error:/, '')); return; }
    settings.pairing = { url: full, deviceId: '' };
    saveSettings();
    persistSettingsServer();
    document.getElementById('setPairingStatus').textContent = '已配对：' + full;
    document.getElementById('setPairCode').value = '';
  } catch (e) { alert('连接失败：' + e.message); }
});

// mDNS 自动发现手机
/** v5.15.12：连上网后短时间内自动扫描并重连（boss：「应该有连上网后短时间内自动扫描的功能」）
 *  已配对 + 当前未连接时：每 2.5s 扫一次，发现即自动配对；最多 rounds 轮。
 *  v5.15.19：rounds 语义放宽 —— 传 0/负数表示无限重试（由 autoConnectWatchdog 常驻调用）。 */
async function autoScanAndReconnect(rounds) {
  if (window._autoScanBusy) return;
  window._autoScanBusy = true;
  const infinite = !(rounds > 0);
  try {
    for (let i = 0; infinite || i < rounds; i++) {
      try { if (await call('is_ws_connected')) return true; } catch (e) { }
      try {
        const list = await call('discover_devices', { timeoutMs: 2500 });
        if (list && list.length) {
          // v5.15.19：优先匹配已配对的 deviceId（同一台手机换了 IP 也能连回原配对的）
          const wantDid = (settings.pairing && settings.pairing.deviceId) || '';
          const d = (wantDid && list.find(x => x.deviceId === wantDid)) || list[0];
          await call('connect_server', { url: d.url, deviceId: d.deviceId || '' });
          settings.pairing = { url: d.url, deviceId: d.deviceId || '' };
          saveSettings();
          persistSettingsServer();
          const _nm = String(d.deviceName || d.name || d.addr || '').split('._')[0].replace(/\.$/, '');
          // v5.15.23 D1（boss：「连接成功弹一次就行了，现在一直弹出来很烦」）——
          //   一次运行只提示一次；之后连接状态变化交给角落的常驻指示胶囊。
          if (!window._autoConnectToastShown) {
            window._autoConnectToastShown = true;
            showToast('已自动连接手机端' + (_nm ? '：' + _nm : ''));
          }
          updateLinkPill(true, _nm || '');
          const ps = document.getElementById('setPairingStatus');
          if (ps) ps.textContent = '已配对：' + d.url;
          return true;
        }
      } catch (e) { }
      await new Promise(function (r) { setTimeout(r, 2500); });
    }
  } finally { window._autoScanBusy = false; }
  return false;
}
window.autoScanAndReconnect = autoScanAndReconnect;

// v5.15.19 根因修复（boss：「连上网后短时间自动扫描，手机也连上同一 WiFi 就自动连上；
//   现在反而弹提示要我手动点扫描设备」）：
//   旧实现的自动扫描只在【启动后 3s】和【打开设置页】两个时机各跑一轮，跑完就彻底不跑了。
//   于是「启动时手机还没连上 WiFi / 电脑中途断网再恢复 / 手机WiFi重连」这些场景
//   —— 也就是 boss 遇到的全部场景 —— 都不在扫描时机内，只剩一句「正在后台自动重连」的空提示。
//   改为常驻看门狗：只要【已配对 && 未连接】，就持续自动扫描重连，直到连上为止（连上即静默停手）。
//   注意：不与 Rust 侧 auto_reconnect_loop 冲突 —— autoScan 只用 mDNS 改 URL，
//   真正连不上的判定仍由 Rust ping 决定，这里只是把「发现」这一步补上并前置。
let _autoScanWatchTimer = null;
/** v5.15.23 D2（boss：现在很卡，怀疑一直扫描连手机）——
 *  看门狗加**退避 + 后台不扫**：前 3 次失败每轮扫；之后每 3 轮扫一次；连败 8 次后每 8 轮扫一次。
 *  窗口不可见（最小化/切到别的应用）时完全不扫 —— 这套 mDNS 扫描是最主要的 CPU/网络开销源。 */
let _scanFailStreak = 0;
let _scanTick = 0;
/** v5.15.23 D1：角落常驻「已连接/未连接」胶囊（弹窗只弹一次，状态看这里） */
function updateLinkPill(connected, peer) {
  const pill = document.getElementById('linkPill');
  if (!pill) return;
  // v5.15.26 P2（boss：配对手机之后再显示出来）—— 没配对就整块不显示
  const hasPair = !!(settings.pairing && (settings.pairing.url || settings.pairing.host));
  pill.classList.toggle('hidden', !hasPair);
  if (!hasPair) return;
  pill.classList.toggle('on', !!connected);
  // v5.15.28 P4（boss：「鼠标放到已连接上看到的端口信息不全」）——
  //   原来把地址放在第二行、且带 http:// 前缀，系统原生 tooltip 一长就被截断。
  //   现在把「IP:端口」放到最前面并去掉协议前缀 —— 一行短短的就够，绝不会看不到端口。
  const _addr = (settings.pairing && settings.pairing.url)
    ? String(settings.pairing.url).replace(/^https?:\/\//, '')
    : '';
  pill.title = _addr
    ? (_addr + (connected ? ' · 已连接手机端' : ' · 未连接 — 正在自动重连'))
    : (connected ? '已连接手机端' : '未配对（去设置里配对手机端）');
  const t = pill.querySelector('.lp-text');
  if (t) t.textContent = connected ? '已连接' : '未连接';
}
window.updateLinkPill = updateLinkPill;

async function autoScanWatchdog() {
  // 没配对 → 什么都不做（避免无谓的 mDNS 组播打扰网络）
  if (!settings.pairing) { updateLinkPill(false); return; }
  // 窗口不可见时不扫描（最小化/切走时省 CPU；回来下一次 tick 立即补上）
  if (document.hidden) return;
  let connected = false;
  try { connected = await call('is_ws_connected'); } catch (e) { return; }
  updateLinkPill(connected);
  if (connected) { _scanFailStreak = 0; _scanTick = 0; return; }
  _scanTick += 1;
  const every = _scanFailStreak < 3 ? 1 : (_scanFailStreak < 8 ? 3 : 8);
  if (_scanTick % every !== 0) return;
  // 未连接 → 拿起扫描（autoScanAndReconnect 内部有 busy 锁，不会叠加）
  let ok = false;
  try { ok = await autoScanAndReconnect(1); } catch (e) { ok = false; }
  _scanFailStreak = ok ? 0 : _scanFailStreak + 1;
}
function startAutoScanWatchdog() {
  if (_autoScanWatchTimer) return;
  _autoScanWatchTimer = setInterval(autoScanWatchdog, 8000);   // v5.15.23 D2：5s → 8s（配合退避）
  autoScanWatchdog();                                          // 启动立即查一次
}

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
        // v5.16.0：配对需要手机端显示的 6 位码 —— 这里只把地址填好，
        //   由用户看手机屏幕输入配对码后点「连接」完成（不再能一键配对）。
        document.getElementById('setPairInput').value = d.url.replace(/^https?:\/\//, '');
        window._pendingPairDeviceId = d.deviceId || '';
        box.innerHTML = '← 已填入地址。请在手机端「设置 → 配对」查看 6 位码，填入后点「连接」';
        document.getElementById('setPairCode').focus();
        document.getElementById('setPairingStatus').textContent = '待输入配对码';
        return;
        // eslint-disable-next-line no-unreachable
        await call('connect_server', { url: d.url, deviceId: d.deviceId || '' });
        settings.pairing = { url: d.url, deviceId: d.deviceId || '' };
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

// v5.13c 独立桌面挂件：点击设置里的"开启挂件"→ 打开独立 widget 小窗 + 隐藏主窗
// （boss：桌面挂件不用客户端在 —— 独立窗口常驻桌面，主窗关掉挂件仍在）
document.getElementById('setWidgetMode').addEventListener('click', () => {
  document.getElementById('settingsOverlay').style.display = 'none';
  if (!isTauriEnv) {
    // mock 预览环境：退化为旧 CSS 折叠态（右下角浮条），方便 preview-server 调试
    const app = document.getElementById('app');
    app.classList.add('widget-mode');
    document.getElementById('overviewView').style.display = 'none';
    document.getElementById('listView').style.display = 'none';
    document.getElementById('detailView').style.display = 'none';
    document.getElementById('dashboardView').style.display = 'none';
    document.getElementById('foldedView').style.display = '';
    renderFolded();
    return;
  }
  // Tauri 真环境：显示独立 widget 窗口（widget.html），主窗隐藏
  call('show_widget').catch(()=>{});
  call('win_hide').catch(()=>{});
});

// 等级徽章点击 → 个人信息（昵称 / 头像 / 经验条都在那）
// v5.14h.1：dashboard greeting 身份卡点击 → 个人信息（顶栏 levelBadge 已删除）
document.getElementById('dashGreeting').addEventListener('click', () => {
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

// v5.13g 顶栏挂件开关：直接调用 show_widget / hide_widget（不再进设置点）
const _widgetToggle = document.getElementById('widgetToggle');
if (_widgetToggle) {
  _widgetToggle.addEventListener('click', () => {
    if (!isTauriEnv) { showToast('预览环境无 widget'); return; }
    // 轮询：当前 widget 不可见就显示，可见就隐藏（get_webview_window + is_visible）
    (async () => {
      try {
        const { invoke } = window.__TAURI__.core;
        const w = invoke('get_widget_visible', {});
        const visible = await w;
        if (visible) { await invoke('hide_widget'); }
        else { await invoke('show_widget'); await invoke('win_hide'); }
      } catch (e) { showToast('挂件切换失败'); }
    })();
  });
}

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
  // boss #38：退出 widget 不恢复窗口尺寸（现在 widget 模式不缩窗，仅切 CSS）
}

// 折叠态今日已完成列表（按日期 key，跨天自动重置 —— boss 反映"刷分"问题）
function _foldedDateKey() { return 'foldedDone_' + new Date().toDateString(); }
function getFoldedDone() {
  try { return JSON.parse(sessionStorage.getItem(_foldedDateKey()) || '[]'); } catch(e) { return []; }
}
function addFoldedDone(uuid) {
  const list = getFoldedDone();
  if (!list.includes(uuid)) { list.push(uuid); sessionStorage.setItem(_foldedDateKey(), JSON.stringify(list)); }
}

function renderFolded() {
  // v5.12 桌面挂件重做：只显示当前追踪任务 + 当前步骤（无完成/关闭按钮）
  // 用 trackCards 数据源（含 task + current_step + done_steps + total_steps）
  const top = trackCards[0];   // 后端按 updated_at DESC 排序，第一个 = 最新追踪
  const doneList = getFoldedDone();
  // 已打卡过 + 无步骤任务（无 current_step 字段）则隐藏 widget（"今日已完成"）
  const hiddenByDone = top && !top.current_step && (top.done_steps || 0) > 0 && doneList.includes(top.task.uuid);
  const bar = document.getElementById('foldedBar');
  if (!bar) return;
  const pct = progressInfo.total > 0 ? Math.min(100, (progressInfo.done / progressInfo.total) * 100) : 0;
  bar.querySelector('.fb-fill').style.width = pct + '%';
  const textEl = bar.querySelector('.fb-text');
  const stepEl = bar.querySelector('.fb-step');
  if (!top) {
    textEl.textContent = '暂无追踪任务';
    stepEl.textContent = '点击展开唤起主窗口';
    return;
  }
  if (hiddenByDone) {
    textEl.textContent = '今日已完成 ' + doneList.length + ' 项';
    stepEl.textContent = '点击展开唤起主窗口';
    return;
  }
  textEl.textContent = top.task.title;
  const total = top.total_steps || 0;
  const done = top.done_steps || 0;
  const step = top.current_step;
  if (total > 0) {
    // 有步骤：显示"步骤 N/M · 当前步骤名"
    stepEl.textContent = '步骤 ' + (done + 1) + '/' + total + (step ? ' · ' + step.title : '');
  } else {
    // 无步骤任务：显示分类/到期提示
    if (top.task.deadline || top.task.due_at) {
      const due = top.task.deadline || top.task.due_at;
      if (typeof due === 'number') stepEl.textContent = fmtRemaining(due - Date.now());
      else stepEl.textContent = '追踪中';
    } else {
      stepEl.textContent = '追踪中';
    }
  }
}

// v5.12 删 fbNext / fbComplete 事件绑定 —— widget 不再有完成/关闭按钮（只读追踪卡片）

// boss #37：简易 inline toast（widget 模式不能用 alert，给用户即时反馈）
function showToast(msg) {
  let el = document.getElementById('foldedToast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'foldedToast';
    el.className = 'folded-toast';
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => el.classList.remove('show'), 1800);
}
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
  // 启动时把昵称 / 头像（含自定义图）从后端拉到本地，再调一次 render 让 dashboard 显示。
  // v5.15.10 P0：**必须先 hydrate 再 persistSettingsServer** ——
  //   两者原来并行执行，persistSettingsServer 会把 localStorage 里的旧头像/昵称写回后端并打新时间戳，
  //   于是"手机端刚上传并同步过来的新头像"会被桌面端一份陈旧缓存盖掉（LWW 被破坏）。
  hydrateUserProfile()
    .then(() => { render(); persistSettingsServer().catch(() => {}); })
    .catch(() => render());
  // 首次启动 seed 5 条示例任务（后端去重：seed_v4 标记 + 已有任务时跳过）
  call('seed_default_tasks', {}).then(r => {
    if (r && r.seeded) render();
  }).catch(() => {});
  setTimeout(render, 250);
  setTimeout(render, 1000);
  // v5.15.12：已配对但当前未连接 → 启动后短时间内自动扫描重连（不用手动点「扫描设备」）
  if (settings.pairing) setTimeout(() => autoScanAndReconnect(2), 3000);
  // v5.15.19：常驻自动扫描看门狗 —— 只要"已配对且未连接"就持续自动扫描，
  //   覆盖「启动时手机还没上网 / 中途断网恢复 / 手机 WiFi 重连」等全部场景。
  //   这是 boss 要的"连上网后短时间内自动扫描并自动连接"的真正落点。
  startAutoScanWatchdog();
  // v5.15.18 P0：15s 轮询改为"**数据变了才重绘**"。
  //   原先是无条件 render() → 每 15 秒整块 DOM 重建一次，透明窗上就是一次可见的闪
  //   （boss：「就算我什么都没做也有闪屏问题」—— 就是这里）。
  //   现在先只取数据、比对签名；没变化直接返回，静止期零 DOM churn = 空闲闪根治。
  setInterval(async () => {
    try {
      if (typeof fetchAll !== 'function') return;
      const before = _dataSig();
      await fetchAll();                 // 只读数据，不碰 DOM
      if (_dataSig() === before) return; // 数据没变 → 什么都不做
      render();
    } catch (e) { /* 单次轮询失败不打扰用户，下轮再来 */ }
  }, 15000);
  // v5.15.7：手机端改了数据 → Rust ws_loop 落库后 emit "sync-applied" → 立刻刷新
  //   （否则要等上面 15s 轮询；同时把手机端选的 emoji 头像映射成桌面 avatar_idx 实现头像互见）
  if (isTauriEnv && window.__TAURI__.event && window.__TAURI__.event.listen) {
    window.__TAURI__.event.listen('sync-applied', onRemoteSyncApplied).catch(() => {});
  }
  setInterval(checkEmergency, 60000);
  // v5.15.27 M9：手机端改了「用户名」→ 桌面端问候语要立刻跟上。
//   实测：Rust 侧收到 setting 变更会落库，但**不会**触发 onRemoteSyncApplied（它只在任务/积分变更时被调），
//   而 settings.nickname 只在启动时读一次 → 手机端改完，桌面端必须重启才看得见（boss 会以为"没生效"）。
//   这里做个 5s 的轻量对账：只有真的变了才重渲染仪表盘，开销可忽略；未连接时静默。
setInterval(async () => {
  try {
    const nn = await call('get_setting', { key: 'nickname' });
    const nc = await call('get_setting', { key: 'nickname_custom' });
    const nnS = (nn == null ? '' : nn);
    const ncS = (nc == null ? '' : nc);
    if (nnS !== settings.nickname || ncS !== settings.nickname_custom) {
      settings.nickname = nnS;
      settings.nickname_custom = ncS;
      if (typeof renderDashboard === 'function') renderDashboard();
      const pn = document.getElementById('profileNickname');
      if (pn && document.activeElement !== pn) pn.value = activeNickname();
    }
  } catch (e) { /* 忽略 */ }
}, 5000);

// v5.14g：配对断连提示（auto_reconnect 在 Rust 侧写 settings.reconnect_fail，前端 15s 轮询提示）
  setInterval(checkReconnectFail, 15000);
  checkReconnectFail();
  checkNightNotify();                    // 启动即查一次（22 点后开机也能补提醒）
  setInterval(checkNightNotify, 60000);
}
let _lastReconnectToast = 0;

// v5.15.7：手机端变更落库后（完成任务/打卡/积分/头像）立即刷新界面
let _syncAppliedTimer = null;
function onRemoteSyncApplied() {
  if (_syncAppliedTimer) return;              // 800ms 内的多次变更合并成一次
  _syncAppliedTimer = setTimeout(async () => {
    _syncAppliedTimer = null;
    // 1) 头像互见：手机端选了 emoji / 改了昵称 → 桌面同步过来
    try {
      const aimg = await call('get_setting', { key: 'avatar_img' });
      const aidx = await call('get_setting', { key: 'avatar_idx' });
      const aemo = await call('get_setting', { key: 'avatar_emoji' });
      const nn = await call('get_setting', { key: 'nickname' });
      let avChanged = false;
      const tsImg = parseInt(await call('get_setting', { key: 'sync_ts_avatar_img' }) || '0', 10) || 0;
      const tsEmo = parseInt(await call('get_setting', { key: 'sync_ts_avatar_emoji' }) || '0', 10) || 0;
      const imgNow = aimg || '';
      // v5.15.10：头像按"最新一次意图"生效 —— emoji 时间戳更新才用 emoji（并清掉旧图），
      //   否则以自定义图为准。旧实现只要 settings 里存在 emoji 就删图，
      //   手机端刚推过来的自定义头像会被一条陈旧 emoji 直接抹掉。
      if (aemo && tsEmo > tsImg) {
        const gi = AVATAR_GLYPHS.findIndex(g => g.kind === 'emoji' && g.ico === aemo);
        if (gi >= 0 && settings.avatar_idx !== gi) { settings.avatar_idx = gi; avatarIdx = gi; avChanged = true; }
        if (settings.avatar_img) { delete settings.avatar_img; avChanged = true; }
      } else {
        if (imgNow !== (settings.avatar_img || '')) {
          if (imgNow) settings.avatar_img = imgNow; else delete settings.avatar_img;
          avChanged = true;
        }
        if (aidx != null && !isNaN(parseInt(aidx)) && parseInt(aidx) !== settings.avatar_idx) {
          settings.avatar_idx = parseInt(aidx); avatarIdx = settings.avatar_idx;
          avChanged = true;
        }
      }
      if (nn != null) settings.nickname = nn;
      // v5.15.29 L5：手机端把昵称清空/改回默认时要能同步"未编辑"状态
      const ncNow = await call('get_setting', { key: 'nickname_custom' });
      if (ncNow != null) settings.nickname_custom = ncNow;
      if (avChanged) {
        if (typeof renderUserAvatar === 'function') renderUserAvatar();
        if (typeof renderProfileAvatar === 'function') renderProfileAvatar();
      }
    } catch (e) { /* 忽略 */ }
    // 2) 任务/积分/追踪变化 → 重渲染（render 内部会重新 fetchAll）
    render();
  }, 800);
}

// v5.14g：配对断连提示（auto_reconnect 在 Rust 侧写 settings.reconnect_fail，前端 15s 轮询提示）
// v5.15.19：文案改为"正在自动重连"（不再暗示需要手动操作），并把提示间隔放宽到 60s。
async function checkReconnectFail() {
  try {
    const v = await call('get_setting', { key: 'reconnect_fail' });
    if (v === '1') {
      // v5.15.23 D1（boss：「现在一直弹出来很烦」）—— 同一个断连周期只提示一次；
      //   之后状态看右下角常驻胶囊，不再反复弹 toast。
      if (!window._offlineToastShown) {
        window._offlineToastShown = true;
        showToast('手机暂时不在网络，正在后台自动重连（状态见右下角）');
      }
    } else {
      window._offlineToastShown = false;   // 连上了 → 允许下次断连再提示一次
    }
  } catch (e) { /* 忽略 */ }
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


/* ==========================================================================
   v5.15.31 · 主题（白天 / 夜间 / 跟随系统）
   ⚠️ 主题**不进 settings 同步通道** —— 它是每台设备自己的显示偏好，
      同步过去会把另一端的设置覆盖掉。只存 localStorage。
   ⚠️ 铁律：DOM 就绪才绑定 —— 本文件在 body 末尾加载，顶层绑定安全。
   ========================================================================== */
const THEME_KEY = 'taskbar.theme';
function currentThemeChoice() {
  const v = localStorage.getItem(THEME_KEY);
  return (v === 'light' || v === 'dark' || v === 'auto') ? v : 'auto';
}
function resolveTheme(choice) {
  if (choice === 'auto') {
    return (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
  }
  return choice;
}
function applyTheme(choice) {
  const c = choice || currentThemeChoice();
  document.documentElement.setAttribute('data-theme', resolveTheme(c));
  document.querySelectorAll('#setThemeRow .seg-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.theme === c);
  });
}
function setTheme(choice) {
  localStorage.setItem(THEME_KEY, choice);
  applyTheme(choice);
}
document.querySelectorAll('#setThemeRow .seg-btn').forEach(b => {
  b.addEventListener('click', () => setTheme(b.dataset.theme));
});
if (window.matchMedia) {
  const _mq = window.matchMedia('(prefers-color-scheme: dark)');
  const _onSys = () => { if (currentThemeChoice() === 'auto') applyTheme('auto'); };
  if (_mq.addEventListener) _mq.addEventListener('change', _onSys);
  else if (_mq.addListener) _mq.addListener(_onSys);   // 老内核兜底
}
applyTheme();

/* ==========================================================================
   v5.15.31 · 每日任务刷新时间（时 : 分 两个小框）
   字段 daily_refresh_time（'HH:MM'，默认 '00:00'）—— 与旧字段 daily_refresh(bool) 并存：
   bool 管「要不要自动刷新」，本字段管「几点刷新」。走 settings 同步通道。
   ========================================================================== */
function _fmt2(n) { return (n < 10 ? '0' : '') + n; }
function _setRefreshTime(v) {
  const m = /^(\d{1,2}):(\d{1,2})$/.exec(String(v || '').trim());
  const h = m ? Math.min(23, parseInt(m[1], 10)) : 0;
  const mi = m ? Math.min(59, parseInt(m[2], 10)) : 0;
  const eh = document.getElementById('setDailyRefreshHour');
  const em = document.getElementById('setDailyRefreshMin');
  if (eh) eh.value = _fmt2(h);
  if (em) em.value = _fmt2(mi);
}
function _readRefreshTime() {
  const eh = document.getElementById('setDailyRefreshHour');
  const em = document.getElementById('setDailyRefreshMin');
  let h = parseInt(((eh && eh.value) || '').trim(), 10);
  let mi = parseInt(((em && em.value) || '').trim(), 10);
  if (isNaN(h)) h = 0;
  if (isNaN(mi)) mi = 0;
  h = Math.max(0, Math.min(23, h));
  mi = Math.max(0, Math.min(59, mi));
  return _fmt2(h) + ':' + _fmt2(mi);
}
function _commitRefreshTime() {
  const v = _readRefreshTime();
  _setRefreshTime(v);
  if (settings.daily_refresh_time === v) return;
  settings.daily_refresh_time = v;
  saveSettings();
  call('set_setting', { key: 'daily_refresh_time', value: v }).catch(() => {});
}
(function bindRefreshTime() {
  const eh = document.getElementById('setDailyRefreshHour');
  const em = document.getElementById('setDailyRefreshMin');
  if (!eh || !em) return;
  [eh, em].forEach(el => {
    el.addEventListener('focus', () => el.select());
    el.addEventListener('input', () => { el.value = el.value.replace(/\D/g, '').slice(0, 2); });
    el.addEventListener('blur', _commitRefreshTime);
    el.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); el.blur(); return; }
      if (el === eh && eh.value.length === 2 && e.key >= '0' && e.key <= '9') em.focus();
    });
  });
})();
_setRefreshTime(settings.daily_refresh_time || '00:00');

/* ==========================================================================
   v5.15.31 · 昵称「修改键」—— 常态只显示昵称文字 + 一枚很小的「修改」键
   （boss：昵称应该是以修改键形式，而不是常态输入框；修改键也不用太大）
   ========================================================================== */
function _nickEnterEdit() {
  const t = document.getElementById('profileNickText');
  const b = document.getElementById('profileNickEdit');
  const i = document.getElementById('profileNickname');
  const s = document.getElementById('profileNickSave');
  if (t) t.style.display = 'none';
  if (b) b.style.display = 'none';
  if (i) { i.style.display = ''; i.value = activeNickname(); i.focus(); i.select(); }
  if (s) s.style.display = '';
}
function _nickExitEdit() {
  const t = document.getElementById('profileNickText');
  const b = document.getElementById('profileNickEdit');
  const i = document.getElementById('profileNickname');
  const s = document.getElementById('profileNickSave');
  if (i) i.style.display = 'none';
  if (s) s.style.display = 'none';
  if (t) { t.textContent = activeNickname(); t.style.display = ''; }
  if (b) b.style.display = '';
}
(function bindNickEdit() {
  const b = document.getElementById('profileNickEdit');
  if (b) b.addEventListener('click', _nickEnterEdit);
})();
