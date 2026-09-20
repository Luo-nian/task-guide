#!/usr/bin/env node
/**
 * 任务栏 · 离线买断授权码 —— 生成/签发/校验工具
 * ============================================================
 * 设计要点：**不需要任何服务器**。
 *   - 生成一对 ECDSA P-256 密钥（私钥只在你电脑上，永不外传）
 *   - 公钥（SPKI DER Base64）编译进 App，App 用它离线验签
 *   - 用户拿到授权码 → App 本地验签 → 解锁。全程不联网。
 *
 * 为什么能防伪造：授权码是**私钥签名**的。APK 里只有公钥，
 *   拿到公钥也**不能**造出合法签名（ECDSA 的不可伪造性）。
 *   所以源码公开也不影响这个方案。
 *
 * 用法：
 *   node gen-license.mjs keygen                       生成密钥对（只需一次）
 *   node gen-license.mjs pubkey                        打印公钥（贴进 Kotlin）
 *   node gen-license.mjs issue --to 张三 --order AF123  签发一个授权码
 *   node gen-license.mjs verify <授权码>                校验（自己查真伪）
 *
 * 密钥存放：D:\TaskBar-project\secrets\  —— **在 git 仓库之外，永远不会被提交**
 */

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const SECRETS = 'D:/TaskBar-project/secrets';
const PRIV_PEM = path.join(SECRETS, 'license-private.pem');
const PUB_B64  = path.join(SECRETS, 'license-public.b64');

const PREFIX = 'TB1';           // 版本前缀
const CURVE  = 'prime256v1';    // = P-256

// ── base64url（无 padding），放进 URL/文本都不怕 ──────────────
const b64u = (buf) => Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const b64ud = (s) => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');

function loadPriv() {
  if (!fs.existsSync(PRIV_PEM)) { console.error('✗ 还没有私钥，先跑：node gen-license.mjs keygen'); process.exit(1); }
  return crypto.createPrivateKey(fs.readFileSync(PRIV_PEM, 'utf8'));
}
function loadPubB64() {
  if (!fs.existsSync(PUB_B64)) { console.error('✗ 还没有公钥，先跑 keygen'); process.exit(1); }
  return fs.readFileSync(PUB_B64, 'utf8').trim();
}

// ── keygen ────────────────────────────────────────────────
function keygen() {
  fs.mkdirSync(SECRETS, { recursive: true });
  if (fs.existsSync(PRIV_PEM)) {
    console.log('⚠️ 私钥已存在，不覆盖（覆盖 = 已卖出的授权码全失效！）');
    console.log('   真要重来：手动删掉 ' + PRIV_PEM);
    return;
  }
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: CURVE });
  fs.writeFileSync(PRIV_PEM, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  const pubDer = publicKey.export({ type: 'spki', format: 'der' });
  const pubB64 = pubDer.toString('base64');
  fs.writeFileSync(PUB_B64, pubB64 + '\n');
  console.log('✓ 密钥对已生成（私钥永不外传）');
  console.log('   私钥：' + PRIV_PEM + '   ← ⚠️ 务必备份！丢了就没法再签发授权码');
  console.log('   公钥：' + PUB_B64);
  console.log('');
  console.log('公钥 Base64（贴进 App 的 License.kt）：');
  console.log(pubB64);
}

// ── payload：短、能人眼核对 ────────────────────────────────
//   格式：TB1|tier|issuedAt(YYYYMMDD)|orderId|to
function buildPayload(tier, order, to) {
  const d = new Date();
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
  const clean = (s) => String(s || '-').replace(/[|\n\r]/g, '').slice(0, 24);
  return [PREFIX, tier, ymd, clean(order), clean(to)].join('|');
}

function issue({ tier, order, to }) {
  const priv = loadPriv();
  const payload = buildPayload(tier, order, to);
  const sig = crypto.sign('SHA256', Buffer.from(payload, 'utf8'), priv);
  // 授权码 = base64url(payload) . base64url(sig)
  const code = b64u(Buffer.from(payload, 'utf8')) + '.' + b64u(sig);
  return { payload, code };
}

function verify(code) {
  const pubB64 = loadPubB64();
  const key = crypto.createPublicKey({ key: Buffer.from(pubB64, 'base64'), format: 'der', type: 'spki' });
  const [p, s] = String(code).trim().split('.');
  if (!p || !s) return { ok: false, why: '格式不对（应有 a.b 两段）' };
  const payload = b64ud(p).toString('utf8');
  const ok = crypto.verify('SHA256', Buffer.from(payload, 'utf8'), key, b64ud(s));
  return { ok, payload, why: ok ? '签名有效' : '签名无效（伪造或被改过）' };
}

// ── CLI ───────────────────────────────────────────────────
const [cmd, ...rest] = process.argv.slice(2);
const arg = (name, def) => {
  const i = rest.indexOf('--' + name);
  return i >= 0 ? rest[i + 1] : def;
};

if (cmd === 'keygen') keygen();
else if (cmd === 'pubkey') console.log(loadPubB64());
else if (cmd === 'issue') {
  const r = issue({ tier: arg('tier', 'pro'), order: arg('order', '-'), to: arg('to', '-') });
  console.log(r.code);
  console.error('  payload: ' + r.payload);
} else if (cmd === 'verify') {
  console.log(JSON.stringify(verify(rest[0]), null, 2));
} else {
  console.log(fs.readFileSync(new URL(import.meta.url), 'utf8').split('*/')[0]);
}
