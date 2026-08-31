package com.taskbar.app

import com.taskbar.app.data.model.ReminderStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 提醒方式三档：旧值迁移 + 人话标签 + 未受理升级链 */
class ReminderStrengthTest {

    // ===== 旧值迁移映射（DB 可能存 v4.7 之前的 standard/repeat/alarm） =====

    @Test
    fun `migrateLegacy maps new values as-is`() {
        assertEquals(ReminderStrength.INHERIT, ReminderStrength.migrateLegacy(""))
        assertEquals(ReminderStrength.INHERIT, ReminderStrength.migrateLegacy(null))
        assertEquals(ReminderStrength.NOTIFY, ReminderStrength.migrateLegacy(ReminderStrength.NOTIFY))
        assertEquals(ReminderStrength.VIBRATE, ReminderStrength.migrateLegacy(ReminderStrength.VIBRATE))
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
        assertEquals("响铃提醒（播放自定义铃声）", ReminderStrength.label(ReminderStrength.RING))
        // 旧值也显示人话
        assertEquals("通知栏弹窗（顶部横幅 + 提示音）", ReminderStrength.label("standard"))
    }

    @Test
    fun `shortLabel is compact`() {
        assertEquals("默认", ReminderStrength.shortLabel(""))
        assertEquals("通知", ReminderStrength.shortLabel(ReminderStrength.NOTIFY))
        assertEquals("振动", ReminderStrength.shortLabel(ReminderStrength.VIBRATE))
        assertEquals("响铃", ReminderStrength.shortLabel(ReminderStrength.RING))
    }

    // ===== 未受理升级链（notify→vibrate→ring，ring 到头） =====

    @Test
    fun `escalateNext goes notify to vibrate`() {
        assertEquals(ReminderStrength.VIBRATE, ReminderStrength.escalateNext(ReminderStrength.NOTIFY))
    }

    @Test
    fun `escalateNext goes vibrate to ring`() {
        assertEquals(ReminderStrength.RING, ReminderStrength.escalateNext(ReminderStrength.VIBRATE))
    }

    @Test
    fun `escalateNext ring and inherit do not escalate`() {
        assertNull(ReminderStrength.escalateNext(ReminderStrength.RING))
        assertNull(ReminderStrength.escalateNext(ReminderStrength.INHERIT))
    }

    @Test
    fun `escalateNext legacy repeat escalates from vibrate tier`() {
        assertEquals(ReminderStrength.RING, ReminderStrength.escalateNext("repeat"))
    }
}
