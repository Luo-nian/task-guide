package com.taskbar.app

import com.taskbar.app.data.model.RewardRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** 积分规则单测：类型基础分 + 优先级加成 */
class RewardRulesTest {

    @Test
    fun `单次高优先级15分`() {
        // once=8 + high=7 = 15
        assertEquals(15, RewardRules.forTask("once", "high"))
    }

    @Test
    fun `单次中优先级12分`() {
        // once=8 + medium=4 = 12
        assertEquals(12, RewardRules.forTask("once", "medium"))
    }

    @Test
    fun `重复中优先级14分`() {
        // repeat=10 + medium=4 = 14
        assertEquals(14, RewardRules.forTask("repeat", "medium"))
    }

    @Test
    fun `习惯低优先级6分`() {
        // habit=5 + low=1 = 6
        assertEquals(6, RewardRules.forTask("habit", "low"))
    }

    @Test
    fun `速记低优先级3分`() {
        // note=2 + low=1 = 3
        assertEquals(3, RewardRules.forTask("note", "low"))
    }

    @Test
    fun `目标高优先级22分`() {
        // goal=15 + high=7 = 22
        assertEquals(22, RewardRules.forTask("goal", "high"))
    }

    @Test
    fun `优先级越高分越多`() {
        val low = RewardRules.forTask("once", "low")     // 9
        val medium = RewardRules.forTask("once", "medium") // 12
        val high = RewardRules.forTask("once", "high")    // 15
        assertEquals(9, low)
        assertEquals(12, medium)
        assertEquals(15, high)
        assertEquals(true, high > medium && medium > low)
    }

    @Test
    fun `目标类整体高于速记类`() {
        val goal = RewardRules.forTask("goal", "low")   // 16
        val note = RewardRules.forTask("note", "high")  // 9
        assertEquals(16, goal)
        assertEquals(9, note)
        assertEquals(true, goal > note)
    }
}
