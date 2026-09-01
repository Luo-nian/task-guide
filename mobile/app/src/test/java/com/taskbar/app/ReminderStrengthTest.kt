package com.taskbar.app

import com.taskbar.app.data.model.ReminderStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提醒方式：旧值迁移 + 人话标签 + 四通道解析/序列化 + 未受理升级链 */
class ReminderStrengthTest {

    // ===== 旧值迁移映射（DB 可能存 v4.7 之前的 standard/repeat/alarm） =====

    @Test
    fun `migrateLegacy maps new values as-is`() {
        assertEquals(ReminderStrength.INHERIT, ReminderStrength.migrateLegacy(""))
        assertEquals(ReminderStrength.INHERIT, ReminderStrength.migrateLegacy(null))
        assertEquals(ReminderStrength.NOTIFY, ReminderStrength.migrateLegacy(ReminderStrength.NOTIFY))
        assertEquals(ReminderStrength.VIBRATE, ReminderStrength.migrateLegacy(ReminderStrength.VIBRATE))
        assertEquals(ReminderStrength.BEEP, ReminderStrength.migrateLegacy(ReminderStrength.BEEP))
        assertEquals(ReminderStrength.RING, ReminderStrength.migrateLegacy(ReminderStrength.RING))
    }

    @Test
    fun `migrateLegacy maps legacy values`() {
        assertEquals(ReminderStrength.NOTIFY, ReminderStrength.migrateLegacy("standard"))   // 普通通知
        assertEquals(ReminderStrength.VIBRATE, ReminderStrength.migrateLegacy("repeat"))   // 重复 → 振动
        assertEquals(ReminderStrength.RING, ReminderStrength.migrateLegacy("alarm"))       // 闹钟 → 响铃
    }

    @Test
    fun `migrateLegacy passes through unknown values`() {
        assertEquals("custom_x", ReminderStrength.migrateLegacy("custom_x"))
    }

    // ===== 人话标签 =====

    @Test
    fun `label is human readable for all tiers`() {
        assertEquals("跟随默认设置", ReminderStrength.label(ReminderStrength.INHERIT))
        assertEquals("通知栏弹窗（顶部横幅 + 提示音）", ReminderStrength.label(ReminderStrength.NOTIFY))
        assertEquals("振动提醒（按自定义周期持续震动）", ReminderStrength.label(ReminderStrength.VIBRATE))
        assertEquals("提示音（系统默认提示音）", ReminderStrength.label(ReminderStrength.BEEP))
        assertEquals("响铃提醒（播放自定义铃声）", ReminderStrength.label(ReminderStrength.RING))
        // 旧值也显示人话
        assertEquals("通知栏弹窗（顶部横幅 + 提示音）", ReminderStrength.label("standard"))
    }

    @Test
    fun `shortLabel is compact`() {
        assertEquals("默认", ReminderStrength.shortLabel(""))
        assertEquals("通知", ReminderStrength.shortLabel(ReminderStrength.NOTIFY))
        assertEquals("振动", ReminderStrength.shortLabel(ReminderStrength.VIBRATE))
        assertEquals("提示音", ReminderStrength.shortLabel(ReminderStrength.BEEP))
        assertEquals("响铃", ReminderStrength.shortLabel(ReminderStrength.RING))
    }

    // ===== 配置解析/序列化（多通道 + 端选择） =====

    @Test
    fun `parseConfig handles null and legacy single values`() {
        val (ch0, sc0) = ReminderStrength.parseConfig(null)
        assertTrue(ch0.isEmpty()); assertEquals(ReminderStrength.SCOPE_MOBILE, sc0)

        val (ch1, sc1) = ReminderStrength.parseConfig("vibrate")
        assertEquals(listOf(ReminderStrength.VIBRATE), ch1); assertEquals(ReminderStrength.SCOPE_MOBILE, sc1)
    }

    @Test
    fun `serialize and parse round trip`() {
        val cfg = ReminderStrength.serializeConfig(listOf("notify", "ring"), ReminderStrength.SCOPE_BOTH)
        val (ch, sc) = ReminderStrength.parseConfig(cfg)
        assertEquals(listOf("notify", "ring"), ch)
        assertEquals(ReminderStrength.SCOPE_BOTH, sc)
    }

    @Test
    fun `parseConfig tolerates malformed json`() {
        val (ch, sc) = ReminderStrength.parseConfig("{bad json")
        assertTrue(ch.isEmpty())
        assertEquals(ReminderStrength.SCOPE_MOBILE, sc)
    }

    // ===== 最高档 + 未受理升级链（notify→vibrate→beep→ring，ring 到头） =====

    @Test
    fun `highest picks strongest channel`() {
        assertEquals(ReminderStrength.RING, ReminderStrength.highest(listOf("notify", "ring")))
        assertEquals(ReminderStrength.BEEP, ReminderStrength.highest(listOf("vibrate", "beep")))
        assertEquals(ReminderStrength.NOTIFY, ReminderStrength.highest(listOf("notify")))
    }

    @Test
    fun `escalateNext goes one step up from highest`() {
        // 只选通知栏 → 升到振动
        assertEquals(ReminderStrength.VIBRATE, ReminderStrength.escalateNext(listOf(ReminderStrength.NOTIFY)))
        // 同时选了通知栏+提示音（最高提示音）→ 升到铃声（只升一级）
        assertEquals(ReminderStrength.RING, ReminderStrength.escalateNext(listOf(ReminderStrength.NOTIFY, ReminderStrength.BEEP)))
        assertEquals(ReminderStrength.BEEP, ReminderStrength.escalateNext(listOf(ReminderStrength.VIBRATE)))
    }

    @Test
    fun `escalateNext ring does not escalate`() {
        assertNull(ReminderStrength.escalateNext(listOf(ReminderStrength.RING)))
        assertNull(ReminderStrength.escalateNext(listOf(ReminderStrength.BEEP, ReminderStrength.RING)))
    }

    @Test
    fun `escalateNext empty channels escalates to vibrate`() {
        assertEquals(ReminderStrength.VIBRATE, ReminderStrength.escalateNext(emptyList()))
    }
}
