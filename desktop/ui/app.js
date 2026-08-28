// 任务指南 桌面端前端逻辑（兼容 Tauri 与 Node 预览两种模式）
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
    const d = new Date(dueAt);
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const hm = String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
    return (sameDay ? '今日 ' : (d.getMonth()+1)+'月'+d.getDate()+'日 ') + hm;
  }
  return String(dueAt);
}

async function render() {
  try {
    const [cards, today, progress, reminder, habits] = await Promise.all([
      call('get_track_cards'),
      call('get_today_tasks'),
      call('get_progress'),
      call('get_next_reminder'),
      call('get_habits_status')
    ]);

    // ---------- 追踪任务卡（图2：横条 + 紫菱 + 上下两行） ----------
    const tl = document.getElementById('trackList');
    document.getElementById('trackCount').textContent = cards.length;
    if (!cards || cards.length === 0) {
      tl.innerHTML = '<div class="empty">暂无追踪任务</div>';
    } else {
      tl.innerHTML = cards.map(c => {
        const step = c.current_step;
        const stepLine = step ? step.title : '（未拆解步骤）';
        const attr = step && step.attr_value ? `${step.attr_label || '目标'}: ${step.attr_value}` : null;
        const pct = c.total_steps > 0 ? Math.round(c.done_steps / c.total_steps * 100) : 0;
        const nextUuid = step ? step.uuid : '';
        return `<div class="track-card" onclick="advance('${nextUuid}','${c.task.uuid}')">
          <div class="tc-icon"></div>
          <div class="tc-texts">
            <div class="tc-title">${esc(c.task.title)}</div>
            <div class="tc-step">▸ ${esc(stepLine)}${attr ? ` · ${esc(attr)}` : ''}</div>
          </div>
          <div class="tc-progress"><div style="width:${pct}%"></div></div>
        </div>`;
      }).join('');
    }

    // ---------- 今日任务列表（图1：分组 + 选中高亮 + 菱形标识） ----------
    const list = document.getElementById('taskList');
    const tasks = today || [];
    if (tasks.length === 0) {
      list.innerHTML = '<div class="empty">暂无任务</div>';
    } else {
      const tracking = tasks.filter(t => t.track_status === 'tracking');
      const pending = tasks.filter(t => t.track_status !== 'tracking');
      let html = '';
      const itemHtml = t => `
        <div class="task-item" data-uuid="${t.uuid}" onclick="selectTask(this)">
          <div class="ti-diamond ${t.track_status === 'tracking' ? 'tracking' : 'pending'}"></div>
          <div class="ti-texts">
            <div class="ti-title">${esc(t.title)}</div>
            <div class="ti-sub">${fmtDue(t.due_at) ? `<span class="dist">⏰ ${esc(fmtDue(t.due_at))}</span>` : esc(t.category || '')}${t.priority === 'high' ? ' · 高优先级' : ''}</div>
          </div>
        </div>`;
      if (tracking.length > 0) {
        html += `<div class="task-group-title">追踪中</div>${tracking.map(itemHtml).join('')}`;
      }
      if (pending.length > 0) {
        html += `<div class="task-group-title">待办</div>${pending.map(itemHtml).join('')}`;
      }
      list.innerHTML = html;
    }

    // ---------- 进度 ----------
    const p = progress || { done_today: 0, total_today: 0 };
    const pct = p.total_today > 0 ? Math.round(p.done_today / p.total_today * 100) : 0;
    document.getElementById('progressFill').style.width = pct + '%';
    document.getElementById('progressText').textContent = p.done_today + ' / ' + p.total_today;

    // ---------- 倒计时 ----------
    const rs = document.getElementById('reminderSection');
    if (reminder) {
      rs.style.display = '';
      document.getElementById('reminderBox').innerHTML =
        `<div class="r-title">${esc(reminder.title)}</div><div class="r-time">⏰ ${fmtRemaining(reminder.remaining_ms)}</div>`;
    } else {
      rs.style.display = 'none';
    }

    // ---------- 习惯 ----------
    const hl = document.getElementById('habitList');
    if (!habits || habits.length === 0) {
      hl.innerHTML = '<div class="empty">无习惯任务</div>';
    } else {
      hl.innerHTML = habits.map(h =>
        `<div class="habit-item"><span class="h-name">${esc(h.title)}</span>
         <span class="${h.checked ? 'h-done' : 'h-undo'}">${h.checked ? '✓ 已打卡' : '○ 未打卡'}</span></div>`
      ).join('');
    }
  } catch (e) {
    console.error('render 失败', e);
  }
}

// 点击追踪卡 → 推进当前步骤（无步骤则完成任务）
window.advance = async function(stepUuid, taskUuid) {
  if (stepUuid) await call('advance_step', { stepUuid });
  else await call('complete_task', { taskUuid });
  render();
};
window.complete = async function(taskUuid) {
  await call('complete_task', { taskUuid });
  render();
};

// 今日任务选中态（原神列表：米白高亮 + 白箭头）
window.selectTask = function(el) {
  document.querySelectorAll('.task-item').forEach(i => i.classList.remove('selected'));
  el.classList.add('selected');
};

// 模式切换
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
  } catch (e) {
    alert('连接失败: ' + e.message);
  }
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

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

render();
setInterval(render, 10000);
