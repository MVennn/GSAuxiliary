package io.github.MVennn.GSAuxiliary.ui

import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyFieldCatalog
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyRuleStore

/**
 * 「自选分组复制」设置页的共享小工具。
 *
 * 规则列表页与编辑页都要做同一件事：把规则里的字段 id 映射成当前语言的标签、
 * 生成列表页的一行预览、把整份规则写回 remote preferences。放在一处，
 * 避免两页各写一份、改文案时漏一边。
 */

internal val ZX_COPY_FORMATS = listOf(
    ZxCopyRuleStore.FORMAT_TEXT,
    ZxCopyRuleStore.FORMAT_MARKDOWN,
    ZxCopyRuleStore.FORMAT_CSV,
    ZxCopyRuleStore.FORMAT_JSON,
    ZxCopyRuleStore.FORMAT_TEMPLATE,
)

internal val ZX_COPY_ACTIONS = listOf(
    ZxCopyRuleStore.ACTION_CLIPBOARD,
    ZxCopyRuleStore.ACTION_SHARE_TEXT,
    ZxCopyRuleStore.ACTION_IMAGE,
    ZxCopyRuleStore.ACTION_PDF,
)

internal val ZX_COPY_SORTS = listOf(
    ZxCopyRuleStore.SORT_SCREEN,
    ZxCopyRuleStore.SORT_CODE,
    ZxCopyRuleStore.SORT_NAME,
    ZxCopyRuleStore.SORT_ZDF_DESC,
    ZxCopyRuleStore.SORT_ZDF_ASC,
)

internal val ZX_COPY_EMPTIES = listOf(
    ZxCopyRuleStore.EMPTY_DASH,
    ZxCopyRuleStore.EMPTY_BLANK,
)

internal val ZX_COPY_IMAGE_PRESETS = listOf(
    ZxCopyRuleStore.IMAGE_SIMPLE,
    ZxCopyRuleStore.IMAGE_TABLE,
    ZxCopyRuleStore.IMAGE_CARD,
)

/** 上一次的默认规则 id：编辑页把「设为默认」关掉时交还给它 */
internal var lastZxCopyDefaultId: String? = null

internal fun zxCopyFormatIndex(format: String): Int =
    ZX_COPY_FORMATS.indexOf(format).takeIf { it >= 0 } ?: 0

internal fun zxCopyActionIndex(action: String): Int =
    ZX_COPY_ACTIONS.indexOf(action).takeIf { it >= 0 } ?: 0

internal fun zxCopySortIndex(sort: String): Int =
    ZX_COPY_SORTS.indexOf(sort).takeIf { it >= 0 } ?: 0

internal fun zxCopyEmptyIndex(empty: String): Int =
    ZX_COPY_EMPTIES.indexOf(empty).takeIf { it >= 0 } ?: 0

internal fun zxCopyImagePresetIndex(preset: String): Int =
    ZX_COPY_IMAGE_PRESETS.indexOf(preset).takeIf { it >= 0 } ?: 1

/** 字段 id → 当前语言标签；数组顺序与 [ZxCopyFieldCatalog.ALL] 一一对应 */
internal fun zxCopyLabels(
    rule: ZxCopyRuleStore.Rule,
    fieldNames: Array<String>,
): Map<String, String> = rule.fields.associateWith { id ->
    val index = ZxCopyFieldCatalog.index(id)
    if (index in fieldNames.indices) fieldNames[index]
    else rule.labels[id]?.takeIf { it.isNotBlank() } ?: id
}

/** 列表页的一行预览：`名称 代码 涨幅 · Markdown · 复制到剪贴板` */
internal fun zxCopyPreview(
    rule: ZxCopyRuleStore.Rule,
    fieldNames: Array<String>,
    formatTexts: List<String>,
    actionTexts: List<String>,
    /** 「X 等 N 项」的格式串，由调用方按当前语言传入（%1$s = 前缀，%2$d = 总数） */
    moreFormat: String = "%1\$s 等 %2\$d 项",
): String {
    val labels = rule.fields.map { id ->
        val index = ZxCopyFieldCatalog.index(id)
        if (index in fieldNames.indices) fieldNames[index] else rule.labels[id] ?: id
    }
    val fields = if (labels.size <= 3) {
        labels.joinToString(" ")
    } else {
        String.format(moreFormat, labels.take(3).joinToString(" "), labels.size)
    }
    val format = formatTexts.getOrElse(zxCopyFormatIndex(rule.format)) { rule.format }
    val action = actionTexts.getOrElse(zxCopyActionIndex(rule.action)) { rule.action }
    return "$fields · $format · $action"
}

internal fun writeZxCopyRules(data: ZxCopyRuleStore.Data) {
    ModulePreferences.edit {
        putString(ConfigKeys.ZX_COPY_RULES, ZxCopyRuleStore.encode(data))
    }
}
