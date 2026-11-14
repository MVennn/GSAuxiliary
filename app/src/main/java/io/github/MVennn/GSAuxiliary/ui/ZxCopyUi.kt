package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyFieldCatalog
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyGroups
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

/**
 * 「适用分组」选择行的四个选项 → 规则里存的 groups 值。
 *
 * 第一个是「不绑定」，其余三个各绑一个 tab。顺序与编辑页的选项文案一一对应。
 */
internal val ZX_COPY_GROUP_CHOICES: List<List<String>> = listOf(
    emptyList(),
    listOf(ZxCopyGroups.ALL),
    listOf(ZxCopyGroups.SELF),
    listOf(ZxCopyGroups.HOLDING),
)

internal fun zxCopyGroupIndex(groups: List<String>): Int {
    val normalized = ZxCopyGroups.normalize(groups)
    val index = ZX_COPY_GROUP_CHOICES.indexOfFirst { it == normalized }
    return if (index >= 0) index else 0
}

/** 规则绑定的分组名（按当前语言）；没绑定返回 null */
internal fun zxCopyGroupNames(
    groups: List<String>,
    labels: Map<String, String>,
): String? = ZxCopyGroups.normalize(groups)
    .mapNotNull { labels[it] }
    .takeIf { it.isNotEmpty() }
    ?.joinToString("、")

/** 三个分组键 → 当前语言名称（选择行与列表预览共用，文案只在这里取一次） */
@Composable
internal fun zxCopyGroupLabels(): Map<String, String> = mapOf(
    ZxCopyGroups.ALL to stringResource(R.string.zx_copy_group_all),
    ZxCopyGroups.SELF to stringResource(R.string.zx_copy_group_self),
    ZxCopyGroups.HOLDING to stringResource(R.string.zx_copy_group_holding),
)

/** 列表预览的前缀句「仅 自选」；没绑定分组返回 null */
internal fun zxCopyGroupBinding(
    rule: ZxCopyRuleStore.Rule,
    labels: Map<String, String>,
    onlyFormat: String,
): String? = zxCopyGroupNames(rule.groups, labels)?.let { String.format(onlyFormat, it) }

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
    /** 前缀句「仅 自选」；不传就只显示规则本身 */
    binding: String? = null,
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
    val body = "$fields · $format · $action"
    return if (binding.isNullOrBlank()) body else "$binding · $body"
}

internal fun writeZxCopyRules(data: ZxCopyRuleStore.Data) {
    ModulePreferences.edit {
        putString(ConfigKeys.ZX_COPY_RULES, ZxCopyRuleStore.encode(data))
    }
}
