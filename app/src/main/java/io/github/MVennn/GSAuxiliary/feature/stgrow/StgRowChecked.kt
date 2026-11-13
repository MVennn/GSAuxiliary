package io.github.MVennn.GSAuxiliary.feature.stgrow

import android.widget.ListView

/**
 * 「按代码读写勾选态」—— 策略详情页上所有功能共用的一份实现。
 *
 * ## 为什么必须只有一份
 *
 * 勾选态是宿主 `AbsListView.mCheckStates` 里按**适配器位置**存的。筛选换代理、
 * 聚合写回并集、全选逐行点击，都会让「位置 i 上是哪只票」发生变化；谁能搬、
 * 怎么搬、什么时候放弃，必须同一个口径，否则三边各搬一次就互相打架
 * （2026-11-13 之前就是这个局面：筛选自己按代码搬、聚合搬都不搬、全选靠事后校正）。
 *
 * 本 object 只提供**读写循环**，不解析字段：每一行是哪只票由调用方给
 * （`rowCam: (子集下标) -> "代码.市场"`）。因为每个功能手里的坐标来源不同 ——
 * 筛选手里是「宿主原适配器 + 子集下标表」，聚合手里是「刚写回 data 的那个 List」——
 * 但「什么时候读、读不全怎么办、写回要不要全量对齐」必须一致。
 *
 * ## 口径（与 docs/FEATURE-STG-FILTER.md 那一节一致）
 *
 * 1. **读不全就整体放弃**（返回 null / 0）：半截迁移 = 一部分行换了身份、一部分没换，
 *    谁也说不清现在勾着什么，比不迁移更坏 —— 迁移错的勾选会真的把错票加进分组。
 * 2. **写回是全量对齐**，不是只追加拿：行集合变小之后，被筛掉的勾选必须一起消失。
 * 3. 键用 [StgRowSelection.keyOf]/`代码@市场`：同一代码在不同市场不能互相认领。
 * 4. 非多选态一律不碰（单选的 checked 是「当前行」，不是「选中集合」）。
 */
object StgRowChecked {

    /**
     * 读「勾着哪几只」。
     *
     * @param count  当前适配器的行数（= 调用方坐标体系的行数）
     * @param rowCam 子集下标 → `代码.市场`；读不出返回 null
     * @return 勾选行的键集合；非多选态、或任何一行读不出 → null
     */
    fun keysOf(lv: ListView, count: Int, rowCam: (Int) -> String?): Set<String>? {
        if (lv.choiceMode != ListView.CHOICE_MODE_MULTIPLE) return null
        val header = lv.headerViewsCount
        val out = HashSet<String>()
        for (i in 0 until count) {
            if (!lv.isItemChecked(header + i)) continue
            out.add(StgRowSelection.keyOfCam(rowCam(i)) ?: return null)
        }
        return out
    }

    /**
     * 按代码把勾选态对齐到当前坐标；返回改动行数。
     *
     * 先整趟读全每一行是谁，再动手写 —— 见口径第 1 条。
     *
     * @param count 当前适配器的行数
     */
    fun applyChecked(
        lv: ListView,
        count: Int,
        keys: Set<String>,
        rowCam: (Int) -> String?,
    ): Int {
        if (lv.choiceMode != ListView.CHOICE_MODE_MULTIPLE) return 0
        val rowKeys = arrayOfNulls<String>(count)
        for (i in 0 until count) {
            rowKeys[i] = StgRowSelection.keyOfCam(rowCam(i)) ?: return 0
        }
        val header = lv.headerViewsCount
        var changed = 0
        for (i in 0 until count) {
            val want = keys.contains(rowKeys[i])
            if (lv.isItemChecked(header + i) == want) continue
            runCatching { lv.setItemChecked(header + i, want) }.onSuccess { changed++ }
        }
        return changed
    }
}
