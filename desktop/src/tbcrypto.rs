//! ══════════════════════════════════════════════════════════════════
//! v5.17.0 传输加密 + 请求签名（"明文传输"整改）
//! ══════════════════════════════════════════════════════════════════
//!
//! 为什么自己写而不用现成库：
//!   本项目编译环境是**离线**的（`cargo build --offline`），本地 registry 里
//!   没有 `aes-gcm` / `ring` 可用（ring 在 Windows 上还需要 nasm，本机没有）。
//!   可用的只有 `sha2`（纯 Rust）与 `base64`，所以采用
//!   **ChaCha20（流加密）+ HMAC-SHA256（认证）** 这一组合：
//!     - 两者都极简单、无平台依赖、可逐行移植到 Kotlin；
//!     - 加密与 MAC 用**两把独立派生密钥**（enc / sign），做的是
//!       「先加密、再对密文签名」= encrypt-then-MAC，安全性等价于 AEAD 的常见构造。
//!
//! 密钥体系（master 由手机端配对时生成，32 字节）：
//!   master  → HMAC(master,"tb-sign-v1") → sign_key  （签名 / 验签）
//!           → HMAC(master,"tb-enc-v1")  → enc_key   （加解密）
//!
//! 线格式：
//!   请求头 X-TB-Token  : base64(master)   身份
//!         X-TB-Ts     : 毫秒时间戳（允许 ±180s 偏差，防重放）
//!         X-TB-Nonce  : 16 位 hex 随机数（服务端 LRU 去重）
//!         X-TB-Sig    : base64(HMAC(sign_key, "METHOD\nPATH\nTS\nNONCE\nsha256(body).hex"))
//!   请求体（可选加密）："E1:" + base64(nonce12 ‖ ChaCha20(enc_key, nonce12, plaintext))
//!   响应体同样以 "E1:" 前缀 + X-TB-Sig 回签 → 电脑端验签，**双向认证**。
//!
//! 向后兼容：不带 "E1:" 前缀的 body 一律按明文处理（灰度期不会硬断）。

use base64::Engine;
use sha2::{Digest, Sha256};

const B64: base64::engine::general_purpose::GeneralPurpose =
    base64::engine::general_purpose::STANDARD;

/// 密文信封前缀（两端约定）
pub const ENC_PREFIX: &str = "E1:";

// ────────────────────────────── 哈希 / HMAC ──────────────────────────────

pub fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize().into()
}

pub fn to_hex(b: &[u8]) -> String {
    let mut s = String::with_capacity(b.len() * 2);
    for x in b {
        s.push_str(&format!("{:02x}", x));
    }
    s
}

pub fn from_hex(s: &str) -> Option<Vec<u8>> {
    let s = s.trim();
    if s.len() % 2 != 0 {
        return None;
    }
    let mut v = Vec::with_capacity(s.len() / 2);
    let b = s.as_bytes();
    let mut i = 0;
    while i < b.len() {
        let hi = (b[i] as char).to_digit(16)?;
        let lo = (b[i + 1] as char).to_digit(16)?;
        v.push((hi * 16 + lo) as u8);
        i += 2;
    }
    Some(v)
}

/// HMAC-SHA256（RFC 2104 标准实现，手写约 20 行，避免引入 hmac crate）
pub fn hmac_sha256(key: &[u8], msg: &[u8]) -> [u8; 32] {
    let mut k = [0u8; 64];
    if key.len() > 64 {
        k[..32].copy_from_slice(&sha256(key));
    } else {
        k[..key.len()].copy_from_slice(key);
    }
    let mut ipad = [0x36u8; 64];
    let mut opad = [0x5cu8; 64];
    for i in 0..64 {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }
    let mut inner = Vec::with_capacity(64 + msg.len());
    inner.extend_from_slice(&ipad);
    inner.extend_from_slice(msg);
    let ih = sha256(&inner);
    let mut outer = Vec::with_capacity(64 + 32);
    outer.extend_from_slice(&opad);
    outer.extend_from_slice(&ih);
    sha256(&outer)
}

/// 常量时间比较（防时序侧信道）
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut d = 0u8;
    for i in 0..a.len() {
        d |= a[i] ^ b[i];
    }
    d == 0
}

// ────────────────────────────── ChaCha20 ──────────────────────────────

#[inline]
fn qr(s: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    s[a] = s[a].wrapping_add(s[b]);
    s[d] ^= s[a];
    s[d] = s[d].rotate_left(16);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] ^= s[c];
    s[b] = s[b].rotate_left(12);
    s[a] = s[a].wrapping_add(s[b]);
    s[d] ^= s[a];
    s[d] = s[d].rotate_left(8);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] ^= s[c];
    s[b] = s[b].rotate_left(7);
}

/// ChaCha20（RFC 8439 §2.3，IETF 96 位 nonce + 32 位计数器）—— 原地 XOR 加解密
pub fn chacha20_xor(key: &[u8; 32], nonce: &[u8; 12], counter0: u32, data: &mut [u8]) {
    let mut st = [0u32; 16];
    st[0] = 0x6170_7865;
    st[1] = 0x3320_646e;
    st[2] = 0x7962_2d32;
    st[3] = 0x6b20_6574;
    for i in 0..8 {
        let mut w = [0u8; 4];
        w.copy_from_slice(&key[i * 4..i * 4 + 4]);
        st[4 + i] = u32::from_le_bytes(w);
    }
    st[12] = counter0;
    for i in 0..3 {
        let mut w = [0u8; 4];
        w.copy_from_slice(&nonce[i * 4..i * 4 + 4]);
        st[13 + i] = u32::from_le_bytes(w);
    }

    let mut off = 0usize;
    let mut blk: u32 = 0;
    while off < data.len() {
        let mut w = st;
        w[12] = st[12].wrapping_add(blk);
        for _ in 0..10 {
            qr(&mut w, 0, 4, 8, 12);
            qr(&mut w, 1, 5, 9, 13);
            qr(&mut w, 2, 6, 10, 14);
            qr(&mut w, 3, 7, 11, 15);
            qr(&mut w, 0, 5, 10, 15);
            qr(&mut w, 1, 6, 11, 12);
            qr(&mut w, 2, 7, 8, 13);
            qr(&mut w, 3, 4, 9, 14);
        }
        for i in 0..16 {
            let v = w[i].wrapping_add(st[i]).to_le_bytes();
            for j in 0..4 {
                if off < data.len() {
                    data[off] ^= v[j];
                    off += 1;
                }
            }
        }
        blk = blk.wrapping_add(1);
    }
}

// ────────────────────────────── 随机数 ──────────────────────────────
// 复用已有依赖 uuid v4（每次 122 位熵），避免新增 getrandom 依赖

pub fn rand_bytes(n: usize) -> Vec<u8> {
    let mut v: Vec<u8> = Vec::with_capacity(n + 16);
    while v.len() < n {
        v.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    }
    v.truncate(n);
    v
}

pub fn rand_nonce_hex() -> String {
    to_hex(&rand_bytes(8))
}

// ────────────────────────────── 密钥 ──────────────────────────────

#[derive(Clone)]
pub struct Keys {
    pub master: Vec<u8>,
    pub sign: [u8; 32],
    pub enc: [u8; 32],
}

impl Keys {
    pub fn from_master(master: Vec<u8>) -> Keys {
        let sign = hmac_sha256(&master, b"tb-sign-v1");
        let enc = hmac_sha256(&master, b"tb-enc-v1");
        Keys { master, sign, enc }
    }
    /// master 用 hex 字符串在 pairing.json / settings 里持久化
    pub fn from_master_hex(h: &str) -> Option<Keys> {
        let m = from_hex(h)?;
        if m.len() != 32 {
            return None;
        }
        Some(Keys::from_master(m))
    }
    pub fn master_hex(&self) -> String {
        to_hex(&self.master)
    }
    pub fn token(&self) -> String {
        B64.encode(&self.master)
    }
}

// ────────────────────────────── 信封与签名 ──────────────────────────────

/// 加密明文 → "E1:" + base64(nonce12 ‖ ct)
pub fn seal(k: &Keys, plain: &str) -> String {
    let mut nb = [0u8; 12];
    nb.copy_from_slice(&rand_bytes(12));
    let mut buf = plain.as_bytes().to_vec();
    chacha20_xor(&k.enc, &nb, 1, &mut buf);
    let mut out = nb.to_vec();
    out.extend_from_slice(&buf);
    format!("{}{}", ENC_PREFIX, B64.encode(out))
}

/// 解信封；不带前缀则按明文原样返回（兼容旧端）
pub fn open(k: &Keys, wire: &str) -> Option<String> {
    if !wire.starts_with(ENC_PREFIX) {
        return Some(wire.to_string());
    }
    let raw = B64.decode(&wire[ENC_PREFIX.len()..]).ok()?;
    if raw.len() < 13 {
        return None;
    }
    let mut nb = [0u8; 12];
    nb.copy_from_slice(&raw[..12]);
    let mut ct = raw[12..].to_vec();
    chacha20_xor(&k.enc, &nb, 1, &mut ct);
    String::from_utf8(ct).ok()
}

/// 请求/响应签名： METHOD \n PATH \n TS \n NONCE \n sha256(body).hex
pub fn sign(k: &Keys, method: &str, path: &str, ts: i64, nonce: &str, body: &str) -> String {
    let bh = to_hex(&sha256(body.as_bytes()));
    let msg = format!(
        "{}\n{}\n{}\n{}\n{}",
        method.to_uppercase(),
        path,
        ts,
        nonce,
        bh
    );
    B64.encode(hmac_sha256(&k.sign, msg.as_bytes()))
}

// ────────────────────────────── 自检 ──────────────────────────────

/// ChaCha20 已知答案自检（RFC 8439 §2.4.2 前 64 字节）。
/// 若本地 registry 换环境导致实现被改动，这里会在启动时立刻报错。
pub fn self_test() -> bool {
    let mut key = [0u8; 32];
    for i in 0..32 {
        key[i] = i as u8;
    }
    // ⚠️ RFC 8439 §2.4.2 的 nonce 是 00:00:00:00:00:00:00:4a:00:00:00:00
    //    （§2.3.2 块向量那份才是 00:00:00:09…，两者不同，已用 node/OpenSSL 交叉验证）
    let nonce: [u8; 12] = [0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0];
    let pt = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    let mut buf = pt.to_vec();
    chacha20_xor(&key, &nonce, 1, &mut buf);
    let head = to_hex(&buf[..64]);
    let want = concat!(
        "6e2e359a2568f98041ba0728dd0d6981",
        "e97e7aec1d4360c20a27afccfd9fae0b",
        "f91b65c5524733ab8f593dabcd62b357",
        "1639d624e65152ab8f530c359f0861d8"
    );
    head == want
}

/// 用固定向量算出一段指纹字符串，配对时两端互校用（若两端实现不一致会立刻暴露，
/// 而不是等到同步失败才发现）
pub fn kat_probe() -> String {
    let mut key = [0u8; 32];
    for i in 0..32 {
        key[i] = i as u8;
    }
    let nonce: [u8; 12] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    let mut buf = b"taskbar-crypto-probe-v1".to_vec();
    chacha20_xor(&key, &nonce, 1, &mut buf);
    to_hex(&buf)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chacha20_kat() {
        assert!(self_test(), "ChaCha20 与 RFC 8439 标准向量不一致");
    }

    #[test]
    fn seal_open_roundtrip() {
        let k = Keys::from_master(rand_bytes(32));
        let msg = "{\"hello\":\"世界\"}";
        let w = seal(&k, msg);
        assert!(w.starts_with(ENC_PREFIX));
        assert_eq!(open(&k, &w).unwrap(), msg);
        // 明文兼容
        assert_eq!(open(&k, "{\"a\":1}").unwrap(), "{\"a\":1}");
    }

    #[test]
    fn tamper_detected() {
        let k = Keys::from_master(rand_bytes(32));
        let s = sign(&k, "post", "/api/sync/changes", 1, "abc", "body");
        let s2 = sign(&k, "post", "/api/sync/changes", 1, "abc", "body2");
        assert_ne!(s, s2);
        assert_eq!(s, sign(&k, "POST", "/api/sync/changes", 1, "abc", "body"));
    }
}
