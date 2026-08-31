package com.taskbar.app

import com.taskbar.app.data.model.ChineseHolidays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 中国法定节假日识别单元测试：
 * - isHoliday(dateStr) / isHoliday(ts)
 * - nextWorkday(ts)：节假日顺延到下一工作日 9 点
 */
class ChineseHolidaysTest {

    private fun ts(y: Int, m: Int, d: Int, hour: Int = 12): Long {
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, hour, 0, 0)
        return c.timeInMillis
    }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    @Test
    fun `2026元旦是节假日`() {
        assertTrue(ChineseHolidays.isHoliday("2026-01-01"))
        assertTrue(ChineseHolidays.isHoliday("2026-01-02"))
        assertTrue(ChineseHolidays.isHoliday("2026-01-03"))
    }

    @Test
    fun `2026国庆前7天都是节假日`() {
        listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07")
            .forEach { assertTrue("$it 应为节假日", ChineseHolidays.isHoliday(it)) }
    }

    @Test
    fun `2026春节假期正确`() {
        assertTrue(ChineseHolidays.isHoliday("2026-02-15"))
        assertTrue(ChineseHolidays.isHoliday("2026-02-21"))
        assertFalse(ChineseHolidays.isHoliday("2026-02-22"))
    }

    @Test
    fun `普通工作日不是节假日`() {
        assertFalse(ChineseHolidays.isHoliday("2026-03-10"))
        assertFalse(ChineseHolidays.isHoliday("2026-06-01"))
        assertFalse(ChineseHolidays.isHoliday("2026-11-11"))
    }

    @Test
    fun `时间戳版本与日期串一致`() {
        val t = ts(2026, 10, 1)
        assertTrue(ChineseHolidays.isHoliday(t))
        val t2 = ts(2026, 3, 10)
        assertFalse(ChineseHolidays.isHoliday(t2))
    }

    @Test
    fun `2027劳动节是节假日`() {
        assertTrue(ChineseHolidays.isHoliday("2027-05-01"))
        assertTrue(ChineseHolidays.isHoliday("2027-05-03"))
    }

    @Test
    fun `国庆期间顺延到10月8日9点`() {
        // 2026-10-01（周四）12:00 → 10-01~10-07 放假 → 10-08（周四）9:00
        val workday = ChineseHolidays.nextWorkday(ts(2026, 10, 1, 12))
        assertEquals("2026-10-08 09:00", fmt(workday))
    }

    @Test
    fun `周末顺延到周一9点`() {
        // 2026-10-10 是周六 → 下一工作日 10-12 周一（10-11 周日也休）
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(2026, 9, 10, 15, 0, 0)  // 2026-10-10 周六
        val workday = ChineseHolidays.nextWorkday(c.timeInMillis)
        assertEquals("2026-10-12 09:00", fmt(workday))
    }

    @Test
    fun `普通工作日返回当天9点`() {
        // 2026-03-10 周二 12:00 → 当天 9:00（已是工作日，时间归 9 点）
        val workday = ChineseHolidays.nextWorkday(ts(2026, 3, 10, 12))
        assertEquals("2026-03-10 09:00", fmt(workday))
    }
}
