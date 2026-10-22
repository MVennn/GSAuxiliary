package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.view.View
import android.widget.CheckedTextView
import android.widget.HeaderViewListAdapter
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.stgfilter.StgFilterBridge
import io.github.MVennn.GSAuxiliary.util.Reflect

/** 选中读取的结果。失败时带一条用户看得懂的原因。 */
internal sealed interface SelectionResult {
    data class Ok(val items: List<SelectedStock>) : SelectionResult
    data class Fail(val reason: String, val message: String) : SelectionResult
}

/**
 * 从策略详情多选态读取勾选的股票。
 *
 * 与宿主「确认添加」读同一份 ListView checked states；筛选生效时用
 * [StgFilterBridge] 把子集下标映射回全量下标。**任何一行解析失败都整体中止** ——
 * 宁可不做，也不截错/漏截。
 */
internal object SnapSelection {

    fun read(activity: Activity): SelectionResult {
        val lv = findListView(activity)
            ?: return SelectionResult.Fail("找不到 lvStks", "找不到策略股票列表，请重进页面再试")
        val raw = lv.adapter
            ?: return SelectionResult.Fail("列表没有适配器", "股票列表还没加载出来，请稍后再试")
        val adapter = unwrapHeader(raw)
            ?: return SelectionResult.Fail("解包 HeaderViewListAdapter 失败", "股票列表状态异常，请重进页面再试")
        val total = adapter.count
        if (total <= 0) {
            return SelectionResult.Fail("列表为空", "股票列表是空的")
        }

        val filtered = isFilterProxy(adapter)
        val full: IntArray? = if (filtered) StgFilterBridge.fullIndices(total, adapter) else null
        if (filtered && full == null) {
            return SelectionResult.Fail(
                "列表挂着筛选代理但读不到下标表",
                "当前筛选状态读不到，请先关闭筛选再试",
            )
        }
        val mapping = full ?: IntArray(total) { it }
        val header = lv.headerViewsCount

        val checked: BooleanArray = readChecked(lv, adapter, total, mapping, header)
            ?: return SelectionResult.Fail(
                "checked states / 探针都读不到",
                "读不到勾选状态，请重进多选态再试",
            )

        val selectedCount = checked.count { it }
        if (selectedCount == 0) {
            return SelectionResult.Fail("没有选中的行", "请先勾选股票")
        }

        val out = ArrayList<SelectedStock>(selectedCount)
        for (i in 0 until total) {
            if (!checked[i]) continue
            val item = runCatching { adapter.getItem(i) }.getOrNull()
            if (item == null) {
                return SelectionResult.Fail("第 $i 行取不到数据对象", "有选中的股票读不出来，请重进页面再试")
            }
            val cam = readCam(item)
            val parts = StgSnapPlan.splitCam(cam)
            if (parts == null) {
                return SelectionResult.Fail(
                    "第 $i 行 codeAndMarket 非法（${cam ?: "null"}）",
                    "有选中的股票代码/市场读不出来，已取消本次操作",
                )
            }
            val name = Reflect.readField(item, *StgSnapAddTargets.NAME_FIELDS) as? String ?: ""
            out.add(SelectedStock(parts.first, parts.second, name, item))
        }

        val items = StgSnapPlan.dedup(out)
        if (items.size != out.size) {
            GLog.w("截图并添加：选中列表有重复代码 ${out.size} → ${items.size}，已去重")
        }

        val bottom = readBottomCount(activity)
        if (bottom >= 0 && bottom != selectedCount) {
            // 筛选生效时底部计数含被筛掉的行，对不上是常态；没有筛选时计数滞后也可能。
            // checked states 是宿主「确认添加」读的同一份表，以它为准。
            if (!filtered) {
                GLog.w("截图并添加：checked states=$selectedCount 与底部计数=$bottom 不一致，按 checked states")
            } else {
                GLog.d("截图并添加：筛选生效，checked states=$selectedCount，底部计数=$bottom")
            }
        }
        GLog.i("截图并添加：读取选中 ${items.size} 只（筛选=$filtered）")
        return SelectionResult.Ok(items)
    }

    /** 优先 checked states；CHOICE_MODE_NONE 时逐行现绑探针读 #ct_item */
    private fun readChecked(
        lv: ListView,
        adapter: ListAdapter,
        total: Int,
        full: IntArray,
        header: Int,
    ): BooleanArray? {
        if (lv.choiceMode != ListView.CHOICE_MODE_NONE) {
            val out = BooleanArray(total)
            for (i in 0 until total) {
                out[i] = runCatching { lv.isItemChecked(header + full[i]) }.getOrDefault(false)
            }
            return out
        }
        val ctId = idOf(lv.context, StgSnapAddTargets.CHECKBOX_ID_CANDIDATES)
        if (ctId == 0) return null
        val out = BooleanArray(total)
        for (i in 0 until total) {
            val probe = runCatching { adapter.getView(i, null, lv) }.getOrNull() ?: return null
            val ct = probe.findViewById<View>(ctId) as? CheckedTextView ?: return null
            out[i] = ct.isChecked
        }
        return out
    }

    private fun readCam(item: Any): String? {
        for (f in StgSnapAddTargets.CAM_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) return v
        }
        var code: String? = null
        for (f in StgSnapAddTargets.CODE_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) {
                code = v
                break
            }
        }
        if (code == null) return null
        for (f in StgSnapAddTargets.MARKET_FIELDS) {
            val v = Reflect.readField(item, f) ?: continue
            return "$code.${v}"
        }
        return null
    }

    private fun readBottomCount(activity: Activity): Int {
        val id = idOf(activity, StgSnapAddTargets.BOTTOM_COUNT_ID_CANDIDATES)
        if (id == 0) return -1
        val tv = activity.findViewById<View>(id) as? TextView ?: return -1
        return StgSnapPlan.parseCount(tv.text)
    }

    private fun findListView(activity: Activity): ListView? {
        val id = idOf(activity, StgSnapAddTargets.LIST_ID_CANDIDATES)
        if (id == 0) return null
        return activity.findViewById<View>(id) as? ListView
    }
    private fun unwrapHeader(adapter: ListAdapter?): ListAdapter? {
        var a = adapter
        while (a is HeaderViewListAdapter) {
            a = runCatching { a.wrappedAdapter }.getOrNull() ?: return null
        }
        return a
    }

    private fun isFilterProxy(adapter: ListAdapter): Boolean {
        if (StgFilterBridge.isFor(adapter)) return true
        // 跨实例兜底：这个类名由 proguard -keepnames 保住（规则 1.2.1）
        return adapter.javaClass.name.contains("FilteredAdapter")
    }

    private fun idOf(context: android.content.Context, names: Array<String>): Int {
        for (name in names) {
            val id = runCatching {
                context.resources.getIdentifier(name, "id", StgSnapAddTargets.TARGET_PKG)
            }.getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }
}
