(() => {
  const lc = document.querySelector('.level-card');
  const cs = lc ? getComputedStyle(lc) : null;
  const ch = document.querySelector('.level-character');
  const ccs = ch ? getComputedStyle(ch) : null;
  const statIco = document.querySelector('#statCardTrack .stat-ico');
  const bars = document.querySelectorAll('#weekStripBars .ws-col').length;
  const sum = document.getElementById('weekStripSum')?.textContent;
  return {
    levelCard: cs ? { padding: cs.padding, minHeight: cs.minHeight } : null,
    levelChar: ccs ? { w: ccs.width, h: ccs.height } : null,
    statTrackIcon: statIco ? statIco.innerHTML.slice(0, 80) : null,
    weekStripCols: bars,
    weekStripSum: sum
  };
})()
