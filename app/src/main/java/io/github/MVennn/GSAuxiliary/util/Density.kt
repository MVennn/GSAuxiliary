package io.github.MVennn.GSAuxiliary.util

import android.view.View

/**
 * 屏幕密度换算（dp → px）。
 *
 * ## 为何放这里（2026-10-17 收敛五处重复）
 *
 * 同一个「`value * density + 0.5f` 四舍五入」写法此前有五处：
 * `NavBarTint` 与 `UiClean` 上各有一份逐字符相同的 `fun dp(v, value)`，
 * `StgSelectAllFeature` / `StgStockFilterFeature` / `StockDetailBarFeature` 里
 * 还各有一处内联 —— 五处语义必须一致（+0.5f 四舍五入，**不是**向下取整），
 * 所以统一收进 util，各处改为调用本层。
 *
 * 本层只依赖 Android 框架类，不依赖 libxposed api。
 */
object Density {

    /** [v] 所在屏幕密度下的 dp → px（+0.5f 四舍五入） */
    fun dp(v: View, value: Int): Int = dp(v, value.toFloat())

    /** 同 [dp]；dp 常量本身是 `Float`（如 `GAP_DP = 10f`）时用这个重载 */
    fun dp(v: View, value: Float): Int =
        (value * v.resources.displayMetrics.density + 0.5f).toInt()
}
