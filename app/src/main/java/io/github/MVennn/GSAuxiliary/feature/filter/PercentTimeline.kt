package io.github.MVennn.GSAuxiliary.feature.filter

/**
 * 「代码.市场 → 涨跌幅」的时间线：数据项字段与行内文案**谁最近变化过，就听谁的**。
 *
 * ## 为什么需要它
 *
 * 老版本是「字段优先、行内文案只在字段读不到时兜底」。两个真机事实把这个优先级推翻：
 *
 *  1. 数据项上的 `zdf` 可能是**加载时的一份快照** —— 宿主行情走的是「原地刷行内文案」，
 *     未必回写数据项字段；
 *  2. 重算只在 `notifyDataSetChanged` / `setAdapter` / `onResume` 时发生，
 *     而行情原地刷文案根本不发通知。
 *
 * 两条叠起来就是用户看到的现象：**一只股票从涨变跌，列表里却一直留着它**（2026-11-12 报）。
 *
 * ## 裁决规则
 *
 * 每个「代码.市场」两侧各记一个样本（值 + 观察时间）：
 *
 *  - **字段**：每轮重算时读一次，**值变了**才刷新时间戳（没变就是没变，不能算「刚更新」）；
 *  - **文案**：可见行绑定时（`getView`）与每 [io.github.MVennn.GSAuxiliary.feature.stgfilter] 的
 *    节拍器扫描时各采一次，值变了才刷新时间戳。
 *
 * 取**时间戳更晚**的那一侧；平手时文案优先（屏幕上显示的那份才是用户眼睛看到的）。
 *
 * 由此得到两个必要行为：
 *  - 被筛掉的行不再可见 → 文案停止更新 → 它的判定**粘住**，不会下一轮又飘回来；
 *  - 宿主真正刷新数据（新对象、字段值变了）→ 字段时间戳更新 → 立刻回到字段，
 *    于是「跌转涨」的股票能在下一轮重算里回补。
 *
 * ## 线程
 *
 * 调用方都在主线程（筛选重算、`getView`、节拍器），但仍按实例加锁：
 * 宿主在子线程 `notifyDataSetChanged` 的越界写法不是没出现过。
 */
internal class PercentTimeline {

    private class Sample(val value: Double, val at: Long)

    private class Entry {
        /** 数据项字段最近一次**变化**时的值与时间 */
        var field: Sample? = null

        /** 行内文案最近一次**变化**时的值与时间 */
        var text: Sample? = null
    }

    private val entries = HashMap<String, Entry>(256)

    /**
     * 本轮该用的涨跌幅。
     *
     * @param fieldPct 这一轮从数据项字段读到的值；读不到传 null，此时沿用上一次的字段样本
     * @param now 观察时间（单调时钟，毫秒）；一整趟重算共用一个值
     */
    @Synchronized
    fun resolve(cam: String, fieldPct: Double?, now: Long): Double? {
        val e = entryOf(cam)
        if (fieldPct != null) {
            val s = e.field
            if (s == null || s.value != fieldPct) e.field = Sample(fieldPct, now)
        }
        val f = e.field
        val t = e.text
        return when {
            t == null -> f?.value
            f == null -> t.value
            t.at >= f.at -> t.value
            else -> f.value
        }
    }

    /**
     * 记一条行内文案样本。
     *
     * @return 相对上一次采集，文案**变了**没有 —— 没变就不必重算整份筛选
     */
    @Synchronized
    fun observeText(cam: String, pct: Double, now: Long): Boolean {
        val e = entryOf(cam)
        val s = e.text
        if (s != null && s.value == pct) return false
        e.text = Sample(pct, now)
        return true
    }

    /** 页面销毁 / 筛选关闭时清空（样本按「代码.市场」存，跨页面继续用只会拿旧文案判新页面） */
    @Synchronized
    fun reset() {
        entries.clear()
    }

    private fun entryOf(cam: String): Entry {
        entries[cam]?.let { return it }
        // 上限只是防「一整天换几十个策略」把表撑大；真到上限就整表重来（丢掉的是粘性，不是正确性）
        if (entries.size >= MAX_ENTRIES) entries.clear()
        val e = Entry()
        entries[cam] = e
        return e
    }

    private companion object {
        const val MAX_ENTRIES = 4096
    }
}
