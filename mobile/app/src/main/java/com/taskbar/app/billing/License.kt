package com.taskbar.app.billing

import android.content.Context
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 任务栏 · 离线买断授权码（客户端验签）
 * ============================================================
 * **不需要服务器**：公钥编译进 App，在本地离线验签。
 *
 * 授权码格式：`base64url(payload) . base64url(signature)`
 *   payload   = `TB1|tier|issuedAt(YYYYMMDD)|orderId|to`
 *   signature = ECDSA P-256 / SHA-256，由作者电脑上的**私钥**产生
 *
 * 为什么不能伪造：App 里只有公钥，而 ECDSA 的签名**无法由公钥反推**。
 *   所以源码公开也不影响防伪 —— 拿到公钥也造不出合法签名。
 *
 * 生成/签发工具：`src/tools/license/gen-license.mjs`
 *   node gen-license.mjs keygen            生成密钥对（只需一次，私钥在 D:\TaskBar-project\secrets\）
 *   node gen-license.mjs issue --to 张三   签发授权码
 *
 * ⚠️ 私钥文件 **不在 git 仓库里**（`secrets/` 在 `src/` 之外），丢了就无法再签发授权码。
 *
 * ── 当前接线状态（2026-09-20）──
 * 本文件只提供"验签 + 读写"能力，**尚未**接到 UI 与限制逻辑上：
 * 免费版的追踪上限取哪个值（1 还是 3）等 boss 拍板后再接线，避免行为提前变化。
 */
object License {

    /**
     * 公钥（ECDSA P-256，SPKI DER，Base64）。
     * 由 `node gen-license.mjs keygen` 生成，与 `secrets/license-private.pem` 配对。
     */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOkUHDHUum21fcSdrYqGeeTNDZm9SJyyiKuFBOV7IE7EuXfDC1ac9DtDFAlf8g9eko/tr/+pjHBzL4/332fVwiA=="

    private const val PREFS = "taskguide_prefs"
    private const val PREF_CODE = "license_code"
    private const val PREF_TIER = "license_tier"

    /** 免费版同时追踪上限（boss 若选"3 个"就改这里 —— 待 A3 拍板后接线） */
    const val FREE_TRACK_LIMIT = 1

    const val TIER_PRO = "pro"

    /**
     * 支持/购买页（爱发电主页）。**这是全项目唯一的付款入口**，改地址只改这里。
     * 未认证状态下爱发电不能发动态，所以买断说明写在「方案奖励详情」里。
     */
    const val PAY_URL = "https://afdian.com/a/nexttask"

    /** 授权码里带的全部信息 */
    data class Info(val tier: String, val issuedAt: String, val order: String, val to: String) {
        val isPro get() = tier == TIER_PRO
    }

    /** 进程内缓存：验签只做一次，避免 UI 每帧重复算 */
    @Volatile
    private var cached: Info? = null
    @Volatile
    private var cacheLoaded = false

    // ── 纯函数：验签 ────────────────────────────────────────
    /**
     * 校验授权码。**任何异常都返回 null**（不抛，调用方不用 try）。
     * 与 `tools/license/InteropCheck.java` 逐行对应 —— 那边过了这边必然过。
     */
    fun verify(code: String?): Info? {
        if (code.isNullOrBlank()) return null
        return try {
            val parts = code.trim().split('.')
            if (parts.size != 2) return null
            val payload = Base64.getUrlDecoder().decode(parts[0])
            val sig = Base64.getUrlDecoder().decode(parts[1])

            val pk: PublicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64)))
            val v = Signature.getInstance("SHA256withECDSA")
            v.initVerify(pk)
            v.update(payload)
            if (!v.verify(sig)) return null

            val f = String(payload, Charsets.UTF_8).split('|')
            if (f.size < 5 || f[0] != "TB1") return null
            Info(tier = f[1], issuedAt = f[2], order = f[3], to = f[4])
        } catch (e: Exception) {
            null
        }
    }

    // ── 读写 ───────────────────────────────────────────────
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 兑换：验签通过才落盘。返回 null 表示码无效 */
    fun redeem(ctx: Context, code: String): Info? {
        val info = verify(code) ?: return null
        prefs(ctx).edit()
            .putString(PREF_CODE, code.trim())
            .putString(PREF_TIER, info.tier)
            .apply()
        cached = info
        cacheLoaded = true
        return info
    }

    /** 当前已激活的信息（无则 null） */
    fun info(ctx: Context): Info? {
        if (!cacheLoaded) {
            synchronized(this) {
                if (!cacheLoaded) {
                    cached = verify(prefs(ctx).getString(PREF_CODE, null))
                    cacheLoaded = true
                }
            }
        }
        return cached
    }

    fun isPro(ctx: Context): Boolean = info(ctx)?.isPro == true

    /** 同时追踪上限：买断后不限 */
    fun trackLimit(ctx: Context): Int =
        if (isPro(ctx)) Int.MAX_VALUE else FREE_TRACK_LIMIT

    /** v5.25.0：原始授权码（导出备份时带上，换机后可一键恢复到新设备） */
    fun rawCode(ctx: Context): String? = prefs(ctx).getString(PREF_CODE, null)

    /** 撤销/换码 */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(PREF_CODE).remove(PREF_TIER).apply()
        cached = null
        cacheLoaded = true
    }
}
