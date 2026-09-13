(async () => {
  const row = document.querySelector('.list-task') || document.querySelector('.cat-task');
  if (!row) return { err: 'no row' };
  row.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  await new Promise(r => setTimeout(r, 700));
  const dvaB = document.querySelector('.dva-b');
  const dvaL = document.querySelector('.dva-l');
  const detailVisible = document.getElementById('detailView')?.style.display;
  const icon = dvaB ? dvaB.innerHTML.slice(0, 70) : null;
  const goalPath = (typeof ICONS !== 'undefined' && ICONS.goal) ? ICONS.goal.slice(0, 70) : null;
  document.getElementById('detailBack')?.click();
  return { detailVisible, dvaClass: dvaB?.className, icon, isGoal: !!(icon && goalPath && icon.startsWith(goalPath.slice(0, 30))), dvaLabel: dvaL?.textContent };
})()
