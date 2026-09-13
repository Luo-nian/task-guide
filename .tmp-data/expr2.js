(async () => {
  const goalPath = (typeof ICONS !== 'undefined' && ICONS.goal) ? ICONS.goal.slice(0, 60) : 'NO ICONS GOAL';
  const statSvg = document.querySelector('#statCardTrack .stat-ico svg')?.innerHTML.slice(0, 60);
  // 点开第一个任务详情（D2：详情页「追踪任务」键图标）
  const row = document.querySelector('.list-task') || document.querySelector('.cat-task');
  let detailIcon = null, detailLabel = null;
  if (row) {
    row.click();
    await new Promise(r => setTimeout(r, 600));
    const dva = document.querySelector('.dva-b.track');
    detailIcon = dva ? dva.innerHTML.slice(0, 60) : null;
    detailLabel = document.querySelector('.dva-l')?.textContent ?? null;
    // 返回列表，避免留在详情页
    document.getElementById('detailBack')?.click();
  }
  return { goalPath, statSvg, rowFound: !!row, detailIcon, detailLabel };
})()
