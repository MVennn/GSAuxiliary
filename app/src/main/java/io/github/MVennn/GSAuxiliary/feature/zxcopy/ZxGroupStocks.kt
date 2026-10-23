package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyFieldCatalog
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyRow
import io.github.MVennn.GSAuxiliary.util.IdCache
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 读取「自选」页**当前显示分组**里的全部股票，并把字段按
 * [ZxCopyFieldCatalog] 格式化成 [ZxCopyRow]。
 *
 * ## 为什么读当前页的适配器，而不是 `ZXWorkflow.getStocksByGroupId()`
 *
 * 2026-10-23 真机实测：`getStocksByGroupId()` 只回 `StockData`（`m_stkCode` /
 * `m_stkMarket`，**没有名称**），而用户要的格式是「名称 代码」；另外「持仓股」
 * 不是真实分组（`getAllGroups()` 里只有「全部」「自选」），走 API 还得特判。
 *
 * 当前页的 `InterceptListView` 适配器里，条目就是 `StockInfo`（`stkName` +
 * `m_stkCode` + 全部行情字段），排序也与屏幕一致 —— 所见即所得，三种 tab 统一。
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
 * ## 字段读取
 *
 * 条目类 → 字段映射按类缓存（规则 2.1）；每个字段按候选顺序取第一个非空。
 * 名称 / 代码仍为空时，用 `adapter.getView(i, null, list)` 现绑一行读
 * `#tv_item_stk_name` / `#tv_item_stk_code` 兜底。再读不到代码就跳过该条
 * （**绝不猜代码**）。
 */
object ZxGroupStocks {

    /** 一次读取的结果 */
    data class Snapshot(
        val groupName: String?,
        val rows: List<ZxCopyRow>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /** 条目类 → 字段 id → 反射字段（失败也缓存，避免每只股票重扫继承链） */
    private class FieldAccess(val byId: Map<String, Field>)

    private val fieldsByClass =
        Collections.synchronizedMap(WeakHashMap<Class<*>, FieldAccess>())

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
        val rows = ArrayList<ZxCopyRow>(count)
        val seen = HashSet<String>(count)
        var skipped = 0

        for (i in 0 until count) {
            val item = runCatching { adapter.getItem(i) }.getOrNull()
            val values = HashMap<String, String>(ZxCopyFieldCatalog.ALL.size)
            val sort = HashMap<String, Double>(8)

            if (item != null) {
                val access = fieldsOf(item.javaClass)
                for (spec in ZxCopyFieldCatalog.ALL) {
                    val field = access.byId[spec.id] ?: continue
                    val raw = runCatching { field.get(item) }.getOrNull() ?: continue
                    val text = ZxCopyFieldCatalog.format(spec, raw) ?: continue
                    values[spec.id] = text
                    ZxCopyFieldCatalog.sortValue(spec, raw)?.let { sort[spec.id] = it }
                }
            }

            if (values["name"] == null || values["code"] == null) {
                val row = runCatching { adapter.getView(i, null, list) }.getOrNull()
                if (values["name"] == null && nameId != 0) {
                    val t = row?.findViewById<TextView>(nameId)?.text?.toString()
                    ZxCopyFieldCatalog.byId("name")?.let { spec ->
                        ZxCopyFieldCatalog.format(spec, t)?.let { values["name"] = it }
                    }
                }
                if (values["code"] == null && codeId != 0) {
                    val t = row?.findViewById<TextView>(codeId)?.text?.toString()
                    ZxCopyFieldCatalog.byId("code")?.let { spec ->
                        ZxCopyFieldCatalog.format(spec, t)?.let { values["code"] = it }
                    }
                }
            }

            val code = values["code"]
            if (code.isNullOrBlank()) {
                skipped++
                continue
            }
            val market = values["market"].orEmpty()
            if (!seen.add("$code.$market")) continue
            rows.add(ZxCopyRow(values, sort))
        }

        if (skipped > 0) GLog.w("自选分组复制：$skipped 条读不到代码，已跳过")
        return Snapshot(groupName, rows)
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

    // ------------------------------------------------------------------ 字段

    private fun fieldsOf(cls: Class<*>): FieldAccess = synchronized(fieldsByClass) {
        fieldsByClass[cls]?.let { return it }
        val map = HashMap<String, Field>(ZxCopyFieldCatalog.ALL.size)
        for (spec in ZxCopyFieldCatalog.ALL) {
            for (name in spec.candidates) {
                val f = Reflect.findField(cls, name) ?: continue
                map[spec.id] = f
                break
            }
        }
        val access = FieldAccess(map)
        fieldsByClass[cls] = access
        access
    }
}
