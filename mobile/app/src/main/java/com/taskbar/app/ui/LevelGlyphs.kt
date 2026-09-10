package com.taskbar.app.ui

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/**
 * v5.15.6：等级图标库 —— 与桌面端 v5.14h.8 同一套语义图标
 * lv1 萌芽 / lv2 羽毛 / lv3 帆船 / lv4 弯月伴星 / lv5 奖杯 / lv6 盾 / lv7 锚 / lv8 太阳 / lv9 命运之眼 / lv10 轨道
 *
 * path d 直接取自桌面 app3.js ICONS（已在桌面 24x24 渲染验证清晰）；
 * 其中 <circle> 元素改写为等效 path 弧（PathParser 按段逐个解析）。
 */
object LevelGlyphs {

    /** 每级多段 path d（按 draw 顺序） */
    private val D: Map<Int, List<String>> = mapOf(
        1 to listOf(
            "M14 9.536V7a4 4 0 0 1 4-4h1.5a.5.5 0 0 1 .5.5V5a4 4 0 0 1-4 4a4 4 0 0 0-4 4c0 2 1 3 1 5a5 5 0 0 1-1 3M4 9a5 5 0 0 1 8 4a5 5 0 0 1-8-4m1 12h14"
        ),
        2 to listOf(
            "M20.24 12.24a6 6 0 0 0-8.49-8.49L5 10.5V19h8.5z",
            "M16 8 2 22",
            "M17.5 15H9"
        ),
        3 to listOf(
            "M22 18H2a4 4 0 0 0 4 4h12a4 4 0 0 0 4-4Z",
            "M21 14 10 2 3 14h18Z",
            "M10 2v16",
            "M4 17.5h16"
        ),
        4 to listOf(
            "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z",
            "M19 3v4",
            "M21 5h-4"
        ),
        5 to listOf(
            "M8 4.4h8v4.2a4 4 0 0 1-8 0V4.4Z",
            "M8 5.8H5.6a2.4 2.4 0 0 0 2.4 2.4M16 5.8h2.4a2.4 2.4 0 0 1-2.4 2.4",
            "M12 12.6v3.4M9.6 19.6h4.8M10.2 16h3.6l.5 3.6h-4.6l.5-3.6Z"
        ),
        6 to listOf(
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"
        ),
        7 to listOf(
            "M12 2A3 3 0 1 0 12 8A3 3 0 1 0 12 2",
            "M12 22V8",
            "M5 12H2a10 10 0 0 0 20 0h-3"
        ),
        8 to listOf(
            "M12 8A4 4 0 1 0 12 16A4 4 0 1 0 12 8",
            "M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"
        ),
        9 to listOf(
            "M2.8 12S6.2 5.6 12 5.6 21.2 12 21.2 12 17.8 18.4 12 18.4 2.8 12 2.8 12Z",
            "M12 9.3A2.7 2.7 0 1 0 12 14.7A2.7 2.7 0 1 0 12 9.3"
        ),
        10 to listOf(
            "M20.341 6.484A10 10 0 0 1 10.266 21.85m-6.607-4.334A10 10 0 0 1 13.74 2.152",
            "M12 9A3 3 0 1 0 12 15A3 3 0 1 0 12 9",
            "M19 3A2 2 0 1 0 19 7A2 2 0 1 0 19 3",
            "M5 17A2 2 0 1 0 5 21A2 2 0 1 0 5 17"
        )
    )

    /** 一次性解析为 Compose Path（object init 构建一次） */
    val PATHS: Map<Int, List<Path>> = D.mapValues { (_, segs) ->
        segs.map { seg ->
            try { PathParser().parsePathString(seg).toPath() } catch (e: Exception) { Path() }
        }
    }

    /**
     * v5.15.10：预热。object 首次访问会在调用线程上解析 10 档共 ~30 段 path，
     * 若正好发生在「我的」页首帧 → 首帧被拉长（实测进入该页 90th=300ms 的一部分）。
     * 在 Application 启动时丢到后台线程先解析一遍，之后取 PATHS 就是零成本。
     */
    fun prewarm() { PATHS.size }
}
