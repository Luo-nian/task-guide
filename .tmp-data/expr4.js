(async () => {
  const t = await fetch(location.origin + '/app3.js').then(r => r.text());
  const i = t.indexOf('dva-b track');
  const ctx = i >= 0 ? t.slice(i - 60, i + 120) : 'NOT FOUND';
  const hasPlay = /dva-b track">\$\{svgIcon\('play'/.test(t);
  const hasGoal = /dva-b track">\$\{svgIcon\('goal'/.test(t);
  const css = await fetch(location.origin + '/style.css').then(r => r.text());
  const ws = css.includes('v5.15.22 D5');
  const strip = css.includes('week-strip .ws-title { font-size: 14.5px');
  return { hasGoal, hasPlay, ctx: ctx.replace(/\s+/g, ' '), cssD5: ws, weekStripCss: strip };
})()
