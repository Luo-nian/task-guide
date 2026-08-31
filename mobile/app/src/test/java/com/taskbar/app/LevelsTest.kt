package com.taskbar.app

import com.taskbar.app.data.model.Levels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等级体系单元测试（对齐桌面端 preview-server.js LEVELS）：
 * lv1 历练学徒 0-20 / lv2 风华游侠 20-60 / lv3 破浪骑士 60-120 / lv4 群星行者 120-200 / lv5 传奇勇者 200-999
 */
class LevelsTest {

    @Test
    fun `积分0是历练学徒`() {
        val lv = Levels.of(0)
        assertEquals(1, lv.lv)
        assertEquals("历练学徒", lv.name)
        assertEquals(0f, lv.progress, 0.001f)
    }

    @Test
    fun `积分20升到风华游侠`() {
        val lv = Levels.of(20)
        assertEquals(2, lv.lv)
        assertEquals("风华游侠", lv.name)
        // 进度 = 总积分 / 本级上限 = 20/60
        assertEquals(20f / 60f, lv.progress, 0.001f)
        assertEquals(40, lv.toNext)
    }

    @Test
    fun `积分30风华游侠进度一半`() {
        val lv = Levels.of(30)
        assertEquals(2, lv.lv)
        assertEquals(30f / 60f, lv.progress, 0.001f)   // 30/60 = 50%
        assertEquals(30, lv.toNext)
    }

    @Test
    fun `积分59还是风华游侠`() {
        val lv = Levels.of(59)
        assertEquals(2, lv.lv)
        assertEquals(1, lv.toNext)  // 60 - 59
    }

    @Test
    fun `积分60升到破浪骑士`() {
        val lv = Levels.of(60)
        assertEquals(3, lv.lv)
        assertEquals("破浪骑士", lv.name)
        assertEquals(60f / 120f, lv.progress, 0.001f)   // 60/120 = 50%
    }

    @Test
    fun `积分95破浪骑士进度7917`() {
        val lv = Levels.of(95)
        assertEquals(3, lv.lv)
        // 进度 = 总积分 / 本级上限 = 95/120
        assertEquals(95f / 120f, lv.progress, 0.01f)
        assertEquals(25, lv.toNext)  // 120 - 95
    }

    @Test
    fun `积分120升到群星行者`() {
        val lv = Levels.of(120)
        assertEquals(4, lv.lv)
        assertEquals("群星行者", lv.name)
    }

    @Test
    fun `积分200升到传奇勇者`() {
        val lv = Levels.of(200)
        assertEquals(5, lv.lv)
        assertEquals("传奇勇者", lv.name)
        assertEquals(0, lv.toNext)  // 最高级：距下一级 0
    }

    @Test
    fun `积分999仍是传奇勇者且进度封顶1`() {
        val lv = Levels.of(999)
        assertEquals(5, lv.lv)
        assertEquals(1f, lv.progress, 0.001f)
        assertEquals(0, lv.toNext)
    }

    @Test
    fun `积分负数按0处理`() {
        val lv = Levels.of(-5)
        assertEquals(1, lv.lv)
        assertEquals(0f, lv.progress, 0.001f)
    }

    @Test
    fun `所有等级称号存在且不空`() {
        listOf(0, 20, 60, 120, 200).forEach { p ->
            val lv = Levels.of(p)
            assertTrue("lv${lv.lv} 称号不能为空", lv.title.isNotBlank())
        }
    }
}
