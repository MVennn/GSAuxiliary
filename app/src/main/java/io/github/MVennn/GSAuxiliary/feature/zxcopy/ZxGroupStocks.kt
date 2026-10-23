package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.util.IdCache
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 读取「自选」页**当前显示分组**里的全部股票。
 *
 * ## 为什么读当前页的适配器，而不是 `ZXWorkflow.getStocksByGroupId()`
 *
 * 2026-10-23 真机实测：`getStocksByGroupId()` 只回 `StockData`（`m_stkCode` /
 * `m_stkMarket`，**没有名称**），而用户要的格式是「名称 代码」；另外「持仓股」
 * 不是真实分组（`getAllGroups()` 里只有「全部」「自选」），走 API 还得特判。
 *
 * 当前页的 `InterceptListView` 适配器里，条目就是 `StockInfo`（`stkName` +
 * `m_stkCode` + `m_stkMarket`），排序也与屏幕一致 —— 所见即所得，三种 tab 统一。
 *
 * ## 视图结构（实测）
 *
 * ```
 * LockViewPager #vp_zx_group
 *   └─ 当前页
 *        └─ StockListView #stklv_zxg      ← 自定义容器，不是 ListView
 *             ├─ StockHeaderView          ← 列头，是兄弟节点、不在 adapter 里
 *             └─ InterceptListView        ← 真正的 AbsListView
 * ```
 *
 * 所以这里按「页 → 容器 → 第一个 AbsListView」找，不假设 `#stklv_zxg` 自己是列表。
 *
 * ## 读不到字段时的兜底
 *
 * 条目字段按类缓存（规则 2.1）；名称 / 代码仍为空时，用
 * `adapter.getView(i, null, list)` 现绑一行，读行内
 * `#tv_item_stk_name` / `#tv_item_stk_code` 的文本。再读不到代码就跳过该条
 * （**绝不猜代码**），读不到名称则只复制代码。
 */
object ZxGroupStocks {

    /** 一次读取的结果；[groupName] 取不到时为 null（Toast 里省略分组名） */
    data class Snapshot(
        val groupName: String?,
        val stocks: List<ZxCopyPlan.Stock>,
    )

    /** 条目类 → 解析好的字段（规则 2.1：按类缓存，失败也缓存） */
    private class ItemFields(
        val name: List<Field>,
        val code: List<Field>,
        val market: List<Field>,
        val cam: List<Field>,
    )

    private val fieldsByClass =
        Collections.synchronizedMap(WeakHashMap<Class<*>, ItemFields>())

    /**
     * 读当前分组。失败返回 null（调用方提示复制失败）；空分组返回空列表。
     *
     * @param ids 调用方已经预热过的 id 解析器（[ZxGroupCopyFeature] 在布局回调里用过）
     */
    fun read(activity: Activity, ids: IdCache): Snapshot? {
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return null

        val pagerId = ids.of(ZxGroupCopyTargets.PAGER_ID)
        val pager = (if (pagerId != 0) root.findViewById<View>(pagerId) as? ViewGroup else null)
        if (pager == null) {
            GLog.w("自选分组复制：找不到分组页容器 ${ZxGroupCopyTargets.PAGER_ID}")
            return null
        }

        val page = currentPage(pager)
        if (page == null) {
            GLog.w("自选分组复制：找不到当前分组页")
            return null
        }

        val list = findList(page, ids.of(ZxGroupCopyTargets.LIST_CONTAINER_ID))
        if (list == null) {
            GLog.w("自选分组复制：当前分组页里没有股票列表")
            return null
        }

        val adapter = list.adapter
        if (adapter == null) {
            GLog.w("自选分组复制：股票列表没有适配器")
            return null
        }

        val groupName = groupName(root, ids, pager)
        val count = adapter.count
        if (count <= 0) return Snapshot(groupName, emptyList())

        val nameId = ids.of(ZxGroupCopyTargets.NAME_ID)
        val codeId = ids.of(ZxGroupCopyTargets.CODE_ID)
        val stocks = ArrayList<ZxCopyPlan.Stock>(count)
        var skipped = 0
        for (i in 0 until count) {
            val item = runCatching { adapter.getItem(i) }.getOrNull()
            var name = item?.let { firstString(it, fieldsOf(it.javaClass).name) }
            var code = item?.let { codeOf(it) }
            var market = item?.let { marketOf(it) }

            if (name.isNullOrBlank() || code.isNullOrBlank()) {
                // 行情未回填 / 条目不是 StockInfo：现绑一行读文本兜底
                val row = runCatching { adapter.getView(i, null, list) }.getOrNull()
                if (name.isNullOrBlank() && nameId != 0) {
                    name = row?.findViewById<TextView>(nameId)?.text?.toString()
                }
                if (code.isNullOrBlank() && codeId != 0) {
                    code = row?.findViewById<TextView>(codeId)?.text?.toString()
                }
            }

            val n = ZxCopyPlan.normalizeName(name)
            val c = ZxCopyPlan.normalizeCode(code)
            if (c == null) {
                skipped++
                continue
            }
            stocks.add(ZxCopyPlan.Stock(n, c, market))
        }
        if (skipped > 0) GLog.w("自选分组复制：$skipped 条读不到代码，已跳过")
        return Snapshot(groupName, stocks)
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 当前页：优先 `getCurrentItem()`；取不到再按「子视图 left 离 pager 左内边距最近」。
     *
     * `ViewPager` 把当前页摆在 `left = paddingLeft` 的位置，左右预加载页分别是
     * `-width` / `+width`，所以这个兜底不需要知道宿主的分页器实现。
     */
    private fun currentPage(pager: ViewGroup): View? {
        val current = runCatching {
            Reflect.findMethod(pager.javaClass, "getCurrentItem")?.invoke(pager) as? Int
        }.getOrNull()
        if (current != null && current in 0 until pager.childCount) {
            val c = pager.getChildAt(current)
            if (c != null && c.width > 0) return c
        }
        var best: View? = null
        var bestDist = Int.MAX_VALUE
        for (i in 0 until pager.childCount) {
            val c = pager.getChildAt(i)
            if (c.width <= 0) continue
            val d = abs(c.left - pager.paddingLeft)
            if (d < bestDist) {
                best = c
                bestDist = d
            }
        }
        return best
    }

    private fun currentIndex(pager: ViewGroup): Int {
        val current = runCatching {
            Reflect.findMethod(pager.javaClass, "getCurrentItem")?.invoke(pager) as? Int
        }.getOrNull()
        if (current != null && current in 0 until pager.childCount) return current
        val page = currentPage(pager) ?: return -1
        return pager.indexOfChild(page)
    }

    private fun findList(page: View, containerId: Int): AbsListView? {
        if (containerId != 0) {
            val container = page.findViewById<View>(containerId)
            if (container != null) findAbsListView(container)?.let { return it }
        }
        return findAbsListView(page)
    }

    /** 深度优先找第一个 [AbsListView]（实测是 `InterceptListView`） */
    private fun findAbsListView(v: View, depth: Int = 0): AbsListView? {
        if (depth > 30) return null
        if (v is AbsListView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findAbsListView(v.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 分组名：`#indicator_zx_group` 的 `#title_container` 第 currentItem 个子项 */
    private fun groupName(root: View, ids: IdCache, pager: ViewGroup): String? {
        val indicatorId = ids.of(ZxGroupCopyTargets.GROUP_INDICATOR_ID)
        val titleId = ids.of(ZxGroupCopyTargets.TITLE_CONTAINER_ID)
        if (indicatorId == 0 || titleId == 0) return null
        val indicator = root.findViewById<View>(indicatorId) ?: return null
        val titles = indicator.findViewById<ViewGroup>(titleId) ?: return null
        val index = currentIndex(pager)
        if (index < 0 || index >= titles.childCount) return null
        val title = titles.getChildAt(index) as? TextView ?: return null
        return title.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ 条目字段

    private fun fieldsOf(cls: Class<*>): ItemFields = synchronized(fieldsByClass) {
        fieldsByClass[cls]?.let { return it }
        val f = ItemFields(
            name = ZxGroupCopyTargets.NAME_FIELDS.mapNotNull { Reflect.findField(cls, it) },
            code = ZxGroupCopyTargets.CODE_FIELDS.mapNotNull { Reflect.findField(cls, it) },
            market = ZxGroupCopyTargets.MARKET_FIELDS.mapNotNull { Reflect.findField(cls, it) },
            cam = ZxGroupCopyTargets.CAM_FIELDS.mapNotNull { Reflect.findField(cls, it) },
        )
        fieldsByClass[cls] = f
        f
    }

    private fun firstString(item: Any, fields: List<Field>): String? {
        for (f in fields) {
            val v = runCatching { f.get(item) }.getOrNull() as? String
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun codeOf(item: Any): String? {
        val f = fieldsOf(item.javaClass)
        firstString(item, f.cam)?.let { cam ->
            val dot = cam.indexOf('.')
            val code = if (dot > 0) cam.substring(0, dot) else cam
            if (code.isNotBlank()) return code
        }
        return firstString(item, f.code)
    }

    private fun marketOf(item: Any): String? {
        val f = fieldsOf(item.javaClass)
        firstString(item, f.cam)?.let { cam ->
            val dot = cam.indexOf('.')
            if (dot > 0 && dot < cam.length - 1) return cam.substring(dot + 1)
        }
        return firstString(item, f.market)
    }
}
