package com.taskbar.app.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ══════════════════════════════════════════════════════════════════
 * v5.17.0 传输加密 + 请求签名（手机端）
 * ══════════════════════════════════════════════════════════════════
 *
 * 与桌面端 `desktop/src/tbcrypto.rs` 是**同一套算法的逐行镜像**：
 *   ChaCha20（流加密）+ HMAC-SHA256（认证），两把独立派生密钥，
 *   先加密再对密文签名（encrypt-then-MAC）。
 *
 * 为什么不用现成库：桌面端的编译环境是**离线**的，本地 registry 里没有
 * `aes-gcm` / `ring` 可用，所以两端统一采用"可逐行移植"的自实现，
 * 并在两端各带一份 RFC 8439 已知答案自检（[selfTest]）。
 *
 * 密钥体系（master 32 字节，配对时由手机端生成）
 *   master → HMAC(master,"tb-sign-v1") → signKey
 *          → HMAC(master,"tb-enc-v1")  → encKey
 *
 * 线格式
 *   请求头 X-TB-Token : base64(master)
 *          X-TB-Ts    : 毫秒时间戳（±180s）
 *          X-TB-Nonce : 16 位 hex（服务端去重，防重放）
 *          X-TB-Sig   : base64(HMAC(signKey,"METHOD\nPATH\nTS\nNONCE\nsha256(body).hex"))
 *   请求/响应体 : "E1:" + base64(nonce12 ‖ ChaCha20(encKey, nonce12, 明文))
 */
object TbCrypto {
    const val ENC_PREFIX = "E1:"

    private val rnd = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    // ─────────────── 哈希 / HMAC ───────────────

    fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

    fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(msg)
    }

    fun toHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    fun fromHex(s: String): ByteArray? {
        val t = s.trim()
        if (t.length % 2 != 0) return null
        val out = ByteArray(t.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(t[i * 2], 16)
            val lo = Character.digit(t[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 常量时间比较（防时序侧信道） */
    fun ctEq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
        return d == 0
    }

    // ─────────────── ChaCha20 ───────────────

    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
    }

    /**
     * ChaCha20（RFC 8439 §2.3，IETF 96 位 nonce + 32 位计数器）— 原地 XOR
     * @param counter0 起始块计数（本项目统一用 1）
     */
    fun chacha20Xor(key: ByteArray, nonce: ByteArray, counter0: Int, data: ByteArray) {
        require(key.size == 32) { "key 必须 32 字节" }
        require(nonce.size == 12) { "nonce 必须 12 字节" }
        val st = IntArray(16)
        st[0] = 0x61707865; st[1] = 0x3320646e; st[2] = 0x79622d32; st[3] = 0x6b206574
        for (i in 0 until 8) {
            val o = i * 4
            st[4 + i] = (key[o].toInt() and 0xFF) or
                ((key[o + 1].toInt() and 0xFF) shl 8) or
                ((key[o + 2].toInt() and 0xFF) shl 16) or
                ((key[o + 3].toInt() and 0xFF) shl 24)
        }
        st[12] = counter0
        for (i in 0 until 3) {
            val o = i * 4
            st[13 + i] = (nonce[o].toInt() and 0xFF) or
                ((nonce[o + 1].toInt() and 0xFF) shl 8) or
                ((nonce[o + 2].toInt() and 0xFF) shl 16) or
                ((nonce[o + 3].toInt() and 0xFF) shl 24)
        }

        var off = 0
        var blk = 0
        while (off < data.size) {
            val w = st.copyOf()
            w[12] = st[12] + blk
            repeat(10) {
                qr(w, 0, 4, 8, 12); qr(w, 1, 5, 9, 13); qr(w, 2, 6, 10, 14); qr(w, 3, 7, 11, 15)
                qr(w, 0, 5, 10, 15); qr(w, 1, 6, 11, 12); qr(w, 2, 7, 8, 13); qr(w, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = w[i] + st[i]
                for (j in 0 until 4) {
                    if (off >= data.size) return
                    val kb = (v shr (j * 8)) and 0xFF
                    data[off] = ((data[off].toInt() and 0xFF) xor kb).toByte()
                    off++
                }
            }
            blk++
        }
    }

    // ─────────────── 随机数 ───────────────

    fun randBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        rnd.nextBytes(b)
        return b
    }

    fun randNonceHex(): String = toHex(randBytes(8))

    // ─────────────── 密钥 ───────────────

    class Keys(val master: ByteArray) {
        val sign: ByteArray = hmacSha256(master, "tb-sign-v1".toByteArray())
        val enc: ByteArray = hmacSha256(master, "tb-enc-v1".toByteArray())
        fun masterHex(): String = toHex(master)
        fun token(): String = b64e.encodeToString(master)
    }

    fun keysOfHex(hex: String): Keys? {
        val m = fromHex(hex) ?: return null
        if (m.size != 32) return null
        return Keys(m)
    }

    fun newMasterHex(): String = toHex(randBytes(32))

    // ─────────────── 信封 / 签名 ───────────────

    fun seal(k: Keys, plain: String): String {
        val nb = randBytes(12)
        val buf = plain.toByteArray()
        chacha20Xor(k.enc, nb, 1, buf)
        val out = nb + buf
        return ENC_PREFIX + b64e.encodeToString(out)
    }

    /** 解信封；不带前缀按明文原样返回（兼容旧端） */
    fun open(k: Keys, wire: String): String? {
        if (!wire.startsWith(ENC_PREFIX)) return wire
        val raw = runCatching { b64d.decode(wire.substring(ENC_PREFIX.length)) }.getOrNull() ?: return null
        if (raw.size < 13) return null
        val nb = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        chacha20Xor(k.enc, nb, 1, ct)
        return runCatching { String(ct, Charsets.UTF_8) }.getOrNull()
    }

    fun sign(k: Keys, method: String, path: String, ts: Long, nonce: String, body: String): String {
        val bh = toHex(sha256(body.toByteArray()))
        val msg = method.uppercase() + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + bh
        return b64e.encodeToString(hmacSha256(k.sign, msg.toByteArray()))
    }

    /**
     * 配对时两端互校用的指纹。
     * 若两端实现不一致（例如一端是别的语言实现出了偏差），
     * **会在配对那一刻立刻暴露**，而不是等到同步失败才发现。
     */
    fun katProbe(): String {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val buf = "taskbar-crypto-probe-v1".toByteArray()
        chacha20Xor(key, nonce, 1, buf)
        return toHex(buf)
    }

    /**
     * RFC 8439 §2.4.2 已知答案自检。
     * 在 SyncService 启动时调用，结果写进日志 —— 万一实现被改坏，日志里能立刻看到。
     */
    fun selfTest(): Boolean {
        val key = ByteArray(32) { it.toByte() }
        val nonce = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only " +
            "one tip for the future, sunscreen would be it.").toByteArray()
        chacha20Xor(key, nonce, 1, pt)
        val head = toHex(pt.copyOfRange(0, 64))
        val want = "6e2e359a2568f98041ba0728dd0d6981" +
            "e97e7aec1d4360c20a27afccfd9fae0b" +
            "f91b65c5524733ab8f593dabcd62b357" +
            "1639d624e65152ab8f530c359f0861d8"
        return head == want
    }
}
