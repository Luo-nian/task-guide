// 用法: node cdp-eval.mjs <wsUrl> <表达式文件|->  （表达式从文件读，避免转义地狱）
import fs from 'fs';
const [,, wsUrl, exprFile] = process.argv;
const expr = fs.readFileSync(exprFile, 'utf8');
const ws = new WebSocket(wsUrl);
let id = 0;
const pending = new Map();
function send(method, params) {
  return new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
}
ws.onopen = async () => {
  await send('Runtime.enable', {});
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  const v = r.result?.result;
  if (v?.type === 'object' && 'value' in v) console.log(JSON.stringify(v.value, null, 1));
  else console.log(JSON.stringify(r.result ?? r, null, 1));
  ws.close(); process.exit(0);
};
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
ws.onerror = (e) => { console.error('WS ERROR', e.message ?? e); process.exit(1); };
setTimeout(() => { console.error('TIMEOUT'); process.exit(2); }, 15000);
