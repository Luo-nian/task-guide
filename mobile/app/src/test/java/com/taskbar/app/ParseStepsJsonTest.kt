package com.taskbar.app

import com.taskbar.app.ui.parseStepsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析步骤 JSON（JsonImportDialog 用的 parseStepsJson）—— 覆盖正常/边界/容错 */
class ParseStepsJsonTest {

    @Test
    fun `parses full JSON with title and steps with attrs`() {
        val json = """
            {
              "title": "读书",
              "steps": [
                {"title": "通读第 1 章", "attr_label": "用时", "attr_value": "30 分钟"},
                {"title": "做笔记"},
                {"title": "复习", "attr_label": "", "attr_value": ""}
              ]
            }
        """.trimIndent()
        val (title, steps) = parseStepsJson(json)
        assertEquals("读书", title)
        assertEquals(3, steps.size)
        assertEquals(Triple("通读第 1 章", "用时", "30 分钟"), steps[0])
        assertEquals(Triple("做笔记", "", ""), steps[1])
        assertEquals(Triple("复习", "", ""), steps[2])
    }

    @Test
    fun `parses without title field`() {
        val (title, steps) = parseStepsJson("""{"steps":[{"title":"a"}]}""")
        assertNull(title)
        assertEquals(1, steps.size)
        assertEquals("a", steps[0].first)
    }

    @Test
    fun `parses without steps field returns empty list`() {
        val (title, steps) = parseStepsJson("""{"title":"solo"}""")
        assertEquals("solo", title)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `empty input returns null title and empty steps`() {
        val (title, steps) = parseStepsJson("")
        assertNull(title); assertTrue(steps.isEmpty())
        val (t2, s2) = parseStepsJson("   ")
        assertNull(t2); assertTrue(s2.isEmpty())
    }

    @Test
    fun `malformed JSON returns null title and empty steps (no crash)`() {
        val (title, steps) = parseStepsJson("{not json")
        assertNull(title); assertTrue(steps.isEmpty())
        val (t2, s2) = parseStepsJson("""{"steps": "not an array"}""")
        assertNull(t2); assertTrue(s2.isEmpty())
    }

    @Test
    fun `step without title field is dropped (other steps kept)`() {
        val json = """{"steps":[{"title":"a"},{"no_title":1},{"title":"b"}]}"""
        val (title, steps) = parseStepsJson(json)
        assertNull(title)
        assertEquals(2, steps.size)
        assertEquals("a", steps[0].first)
        assertEquals("b", steps[1].first)
    }

    @Test
    fun `blank step title is dropped`() {
        val json = """{"steps":[{"title":"valid"},{"title":""},{"title":"   "},{"title":"ok"}]}"""
        val (_, steps) = parseStepsJson(json)
        assertEquals(2, steps.size)
        assertEquals("valid", steps[0].first)
        assertEquals("ok", steps[1].first)
    }
}
