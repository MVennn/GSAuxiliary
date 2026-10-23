package io.github.MVennn.GSAuxiliary.core.zxcopy

/**
 * 一只待导出的股票 —— **已经按字段目录格式化好的值**。
 *
 * 目标进程（`feature/zxcopy`）负责从宿主 `StockInfo` 里读原始值、调
 * [ZxCopyFieldCatalog.format] 格式化；本类只保存结果，因此：
 *  - 设置页的预览可以直接用样例行；
 *  - 文本 / Markdown / CSV / JSON / 图片 / PDF 渲染器都不需要反射宿主字段；
 *  - 单测不需要 Android / 宿主。
 */
data class ZxCopyRow(
    /** 字段 id → 显示文本（缺失的字段不放进 map） */
    val values: Map<String, String>,
    /** 字段 id → 排序用的数值（涨幅 / 价格 / 市值等） */
    val sort: Map<String, Double> = emptyMap(),
) {
    fun value(id: String): String = values[id].orEmpty()
}
