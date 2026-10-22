package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Reflect

/**
 * 把选中的宿主 `StockInfo` 转成详情页要的 `IntentStockInfo`，并打开
 * `StockFragAct`。
 *
 * 契约与现有「策略直达标准行情」相同：
 *
 * ```
 * action=quotes.stockDetail
 * component=com.guosen.quotation.ui.StockFragAct
 * adapter_key=Bundle{ stockList: ArrayList<IntentStockInfo> }
 * stockIndex=0（顶层 extra）
 * ```
 *
 * 必须构造**全新的** Bundle / ArrayList，绝不原地改宿主适配器持有的列表。
 */
internal object StockDetailLauncher {

    fun launch(activity: Activity, env: FeatureEnv, items: List<SelectedStock>): Boolean {
        if (items.isEmpty()) return false
        val cls = Reflect.loadClass(StgSnapAddTargets.CLS_INTENT_STOCK_INFO, env.classLoader)
        if (cls == null) {
            GLog.e("截图并添加：找不到 ${StgSnapAddTargets.CLS_INTENT_STOCK_INFO}")
            return false
        }

        val converted = ArrayList<Parcelable>(items.size)
        for (item in items) {
            val one = toIntentStockInfo(cls, item)
            if (one == null) {
                GLog.w("截图并添加：第 ${items.indexOf(item) + 1} 只转换 IntentStockInfo 失败")
                return false
            }
            converted.add(one)
        }

        return try {
            val bundle = Bundle().apply {
                putParcelableArrayList(StgSnapAddTargets.KEY_STOCK_LIST, converted)
            }
            val intent = Intent(StgSnapAddTargets.ACTION_STOCK_DETAIL)
                .setClassName(StgSnapAddTargets.TARGET_PKG, StgSnapAddTargets.CLS_STOCK_QUOTE)
                .putExtra(StgSnapAddTargets.KEY_ADAPTER, bundle)
                .putExtra(StgSnapAddTargets.KEY_STOCK_INDEX, 0)
            activity.startActivity(intent)
            GLog.i("截图并添加：已打开详情页（${converted.size} 只）")
            true
        } catch (t: Throwable) {
            GLog.w("截图并添加：打开详情页失败", t)
            false
        }
    }

    /** 单只契约（回退路径）：`adapter_key.stockInfo = IntentStockInfo` */
    fun launchSingle(activity: Activity, env: FeatureEnv, item: SelectedStock): Boolean {
        val cls = Reflect.loadClass(StgSnapAddTargets.CLS_INTENT_STOCK_INFO, env.classLoader)
            ?: return false
        val one = toIntentStockInfo(cls, item) ?: return false
        return try {
            val bundle = Bundle().apply {
                putParcelable(StgSnapAddTargets.KEY_STOCK_INFO, one)
            }
            val intent = Intent(StgSnapAddTargets.ACTION_STOCK_DETAIL)
                .setClassName(StgSnapAddTargets.TARGET_PKG, StgSnapAddTargets.CLS_STOCK_QUOTE)
                .putExtra(StgSnapAddTargets.KEY_ADAPTER, bundle)
            activity.startActivity(intent)
            true
        } catch (t: Throwable) {
            GLog.w("截图并添加：打开单只详情页失败", t)
            false
        }
    }

    private fun toIntentStockInfo(cls: Class<*>, stock: SelectedStock): Parcelable? {
        val item = stock.item
        if (item != null) {
            if (cls.isInstance(item)) {
                return item as? Parcelable
            }
            // 宿主自带的 IntentStockInfo(StockInfo)
            cls.declaredConstructors
                .firstOrNull {
                    it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(item.javaClass)
                }
                ?.let { c ->
                    runCatching {
                        c.isAccessible = true
                        c.newInstance(item) as? Parcelable
                    }.getOrNull()?.let { return it }
                }
        }
        // 兜底 (String code, String market)
        cls.declaredConstructors
            .firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
            ?.let { c ->
                runCatching {
                    c.isAccessible = true
                    c.newInstance(stock.code, stock.market) as? Parcelable
                }.getOrNull()?.let { return it }
            }
        return null
    }
}
