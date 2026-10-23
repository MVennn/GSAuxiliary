package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyFieldCatalog
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyFormat
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyImageRenderer
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyRuleStore
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 字段行增删动画时长（与规则列表的 260/220ms 同一量级） */
private const val FieldAnimMillis = 200

/**
 * 「复制规则」三级编辑页。
 *
 * 排版与「一键配置」的方案编辑页同构：名称 / 字段 / 格式 / 排序 / 动作 /
 * 图片样式 / PDF 样式 / 操作 / 预览，每组一张卡，**每改一下立即写**
 * `zx_copy_rules`，不设保存按钮。
 *
 * ## 几个刻意的 UI 取舍（都是 QuickConfig 踩过的坑）
 *
 * - 每个可弹菜单的控件**各自 remember 一个锚点**，不共享变量；
 * - 字段行按字段 id 加 `key`，删除动画用 `MutableTransitionState`；
 * - `endActions` 里的控件统一包在 `align(CenterVertically)` 的 Row 里；
 * - 行内弹窗用 [DownArrowIcon]，进下一级 / 动作行用 [ArrowRightIcon]；
 * - 规则被删掉后 `alive=false`，这一页正在滑走时的编辑一律不落库。
 */
@Composable
internal fun ZxCopyRuleEditScreen(ruleId: String, onDeleted: () -> Unit) {
    val scrollState = rememberScrollState()

    val json by rememberStringPref(ConfigKeys.ZX_COPY_RULES, ConfigKeys.DEFAULT_ZX_COPY_RULES)
    val data = remember(json) { ZxCopyRuleStore.decode(json) }
    val liveRule = remember(json, ruleId) { data.rules.firstOrNull { it.id == ruleId } }
    var lastRule by remember(ruleId) { mutableStateOf(liveRule) }
    if (liveRule != null) lastRule = liveRule
    val rule = liveRule ?: lastRule
    val alive = liveRule != null

    if (rule == null) {
        LaunchedEffect(ruleId) { onDeleted() }
        Column(Modifier.fillMaxSize()) { Spacer(Modifier.height(24.dp)) }
        return
    }

    val fieldNames = stringArrayResource(R.array.zx_copy_field_names)
    val groupNames = stringArrayResource(R.array.zx_copy_field_groups)
    val localContext = LocalContext.current
    val formatTexts = listOf(
        stringResource(R.string.zx_copy_format_text),
        stringResource(R.string.zx_copy_format_markdown),
        stringResource(R.string.zx_copy_format_csv),
        stringResource(R.string.zx_copy_format_json),
        stringResource(R.string.zx_copy_format_template),
    )
    val actionTexts = listOf(
        stringResource(R.string.zx_copy_action_clipboard),
        stringResource(R.string.zx_copy_action_share_text),
        stringResource(R.string.zx_copy_action_image),
        stringResource(R.string.zx_copy_action_pdf),
    )
    val sortTexts = listOf(
        stringResource(R.string.zx_copy_sort_screen),
        stringResource(R.string.zx_copy_sort_code),
        stringResource(R.string.zx_copy_sort_name),
        stringResource(R.string.zx_copy_sort_zdf_desc),
        stringResource(R.string.zx_copy_sort_zdf_asc),
    )
    val emptyTexts = listOf(
        stringResource(R.string.zx_copy_empty_dash),
        stringResource(R.string.zx_copy_empty_blank),
    )
    val imagePresetTexts = listOf(
        stringResource(R.string.zx_copy_image_simple),
        stringResource(R.string.zx_copy_image_table),
        stringResource(R.string.zx_copy_image_card),
    )
    val rowsPerPageChoices = listOf("20", "30", "50", "100")
    val pdfFontChoices = listOf("8", "10", "12", "14")
    val pdfMarginChoices = listOf("24", "36", "48", "60")

    fun labelsFor(r: ZxCopyRuleStore.Rule) = zxCopyLabels(r, fieldNames)
    fun previewFor(r: ZxCopyRuleStore.Rule) =
        zxCopyPreview(r, fieldNames, formatTexts, actionTexts)

    fun update(transform: (ZxCopyRuleStore.Rule) -> ZxCopyRuleStore.Rule) {
        if (!alive) return
        val updatedRules = data.rules.map { r ->
            if (r.id == ruleId) {
                val t = transform(r)
                t.copy(labels = labelsFor(t), preview = previewFor(t))
            } else {
                r
            }
        }
        val newDefaultId = data.defaultId.takeIf { id -> updatedRules.any { it.id == id } }
            ?: updatedRules.firstOrNull { it.isDefault }?.id
            ?: updatedRules.first().id
        writeZxCopyRules(
            ZxCopyRuleStore.Data(
                defaultId = newDefaultId,
                rules = updatedRules.map { s ->
                    if (s.id == newDefaultId) s.copy(isDefault = true)
                    else if (s.isDefault) s.copy(isDefault = false)
                    else s
                },
            ),
        )
    }

    fun withFooterText(r: ZxCopyRuleStore.Rule, text: String) = r.copy(
        footerText = text,
        image = r.image.copy(footerText = text),
        pdf = r.pdf.copy(footerText = text),
    )

    // 预览图走 App 自带的贴底弹窗（与「重置模块」同一个从下往上滑入的壳）
    val pageDialogs = LocalDialogController.current
    val previewTitle = stringResource(R.string.zx_copy_preview_title)
    val previewClose = stringResource(R.string.zx_copy_preview_close)
    val previewMeta = remember {
        ZxCopyFormat.Meta("全部", System.currentTimeMillis(), 3, "自选分组")
    }

    /** 刚添加 / 正在移除的字段：字段行的展开 / 收起动画靠它们驱动 */
    var justAddedField by remember { mutableStateOf<String?>(null) }
    var removingField by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 名称 ----
        Card(modifier = Modifier.cardPadding()) {
            CopyNameField(
                ruleId = rule.id,
                initial = rule.name,
                onRename = { text -> if (text.isNotBlank()) update { it.copy(name = text) } },
            )
        }

        // ---- 按钮动作（第一排：先明确点击按钮做什么） ----
        SmallTitle(stringResource(R.string.zx_copy_section_action))
        Card(modifier = Modifier.cardPadding()) {
            PickerRow(
                title = stringResource(R.string.zx_copy_action),
                summary = actionTexts.getOrElse(zxCopyActionIndex(rule.action)) { rule.action },
                items = actionTexts,
                selectedIndex = zxCopyActionIndex(rule.action),
                onPick = { index -> update { it.copy(action = ZX_COPY_ACTIONS[index]) } },
            )
        }

        // ---- 字段 ----
        SmallTitle(stringResource(R.string.zx_copy_section_fields))
        Card(modifier = Modifier.cardPadding()) {
            rule.fields.forEachIndexed { index, fid ->
                key(fid) {
                    // 与规则列表的新建 / 删除同一套：先展开 / 收完，再落配置
                    val visibleState = remember(fid) {
                        MutableTransitionState(fid != justAddedField)
                    }
                    LaunchedEffect(fid) {
                        if (fid == justAddedField) {
                            visibleState.targetState = true
                            delay(FieldAnimMillis.toLong())
                            if (justAddedField == fid) justAddedField = null
                        }
                    }
                    LaunchedEffect(Unit) {
                        snapshotFlow { removingField == fid }.first { it }
                        visibleState.targetState = false
                        // 上限兜底：动画被系统关掉 / 状态没推进时也要真的删掉
                        withTimeoutOrNull(FieldAnimMillis.toLong() + 300) {
                            snapshotFlow { visibleState.currentState }.first { !it }
                        }
                        update { r -> r.copy(fields = r.fields.filter { it != fid }) }
                        removingField = null
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = expandVertically(tween(FieldAnimMillis)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(FieldAnimMillis)) + fadeOut(tween(140)),
                    ) {
                        CopyFieldRow(
                            fieldId = fid,
                            label = fieldNames.getOrElse(ZxCopyFieldCatalog.index(fid)) { fid },
                            group = groupNames.getOrElse(
                                ZxCopyFieldCatalog.byId(fid)?.group?.ordinal ?: 0,
                            ) { "" },
                            canUp = index > 0,
                            canDown = index < rule.fields.size - 1,
                            canRemove = rule.fields.size > 1,
                            onUp = {
                                update { r ->
                                    val list = r.fields.toMutableList()
                                    list.add(index - 1, list.removeAt(index))
                                    r.copy(fields = list)
                                }
                            },
                            onDown = {
                                update { r ->
                                    val list = r.fields.toMutableList()
                                    list.add(index + 1, list.removeAt(index))
                                    r.copy(fields = list)
                                }
                            },
                            onRemove = { removingField = fid },
                        )
                    }
                }
            }

            AddFieldRow(
                selected = rule.fields,
                fieldNames = fieldNames,
                groupNames = groupNames,
                onAdd = { fid ->
                    update { r -> r.copy(fields = r.fields + fid) }
                    justAddedField = fid
                },
            )
        }

        // ---- 排序与空值 ----
        SmallTitle(stringResource(R.string.zx_copy_section_order))
        Card(modifier = Modifier.cardPadding()) {
            PickerRow(
                title = stringResource(R.string.zx_copy_sort),
                summary = sortTexts.getOrElse(zxCopySortIndex(rule.sort)) { rule.sort },
                items = sortTexts,
                selectedIndex = zxCopySortIndex(rule.sort),
                onPick = { index -> update { it.copy(sort = ZX_COPY_SORTS[index]) } },
            )
            PickerRow(
                title = stringResource(R.string.zx_copy_empty),
                summary = emptyTexts.getOrElse(zxCopyEmptyIndex(rule.empty)) { rule.empty },
                items = emptyTexts,
                selectedIndex = zxCopyEmptyIndex(rule.empty),
                onPick = { index -> update { it.copy(empty = ZX_COPY_EMPTIES[index]) } },
            )
        }

        // ---- 文本格式 ----
        SmallTitle(stringResource(R.string.zx_copy_section_format))
        Card(modifier = Modifier.cardPadding()) {
            PickerRow(
                title = stringResource(R.string.zx_copy_format),
                summary = formatTexts.getOrElse(zxCopyFormatIndex(rule.format)) { rule.format },
                items = formatTexts,
                selectedIndex = zxCopyFormatIndex(rule.format),
                onPick = { index ->
                    update { it.copy(format = ZX_COPY_FORMATS[index]) }
                },
            )

            if (rule.format == ZxCopyRuleStore.FORMAT_TEXT) {
                val choices = listOf(" ", "\t", ",", "|", "")
                val names = listOf(
                    stringResource(R.string.zx_copy_separator_space),
                    stringResource(R.string.zx_copy_separator_tab),
                    stringResource(R.string.zx_copy_separator_comma),
                    stringResource(R.string.zx_copy_separator_pipe),
                    stringResource(R.string.zx_copy_separator_none),
                )
                val index = choices.indexOf(rule.separator).takeIf { it >= 0 } ?: 0
                PickerRow(
                    title = stringResource(R.string.zx_copy_separator),
                    summary = names[index],
                    items = names,
                    selectedIndex = index,
                    onPick = { picked -> update { it.copy(separator = choices[picked]) } },
                )
            }
            if (rule.format == ZxCopyRuleStore.FORMAT_TEMPLATE) {
                CopyValueField(
                    fieldKey = "${rule.id}-template",
                    label = stringResource(R.string.zx_copy_template),
                    hint = stringResource(R.string.zx_copy_template_hint),
                    initial = rule.template,
                    onChange = { text -> update { it.copy(template = text) } },
                )
            }

            if (rule.format != ZxCopyRuleStore.FORMAT_JSON) {
                GsSwitchPreference(
                    checked = rule.header,
                    onCheckedChange = { v -> update { it.copy(header = v) } },
                    title = stringResource(R.string.zx_copy_header),
                )
            }
            if (rule.header && rule.format != ZxCopyRuleStore.FORMAT_CSV &&
                rule.format != ZxCopyRuleStore.FORMAT_JSON
            ) {
                CopyValueField(
                    fieldKey = "${rule.id}-header-text",
                    label = stringResource(R.string.zx_copy_header_text),
                    hint = "",
                    initial = rule.headerText,
                    onChange = { text -> update { it.copy(headerText = text) } },
                )
            }
            if (rule.format == ZxCopyRuleStore.FORMAT_TEXT ||
                rule.format == ZxCopyRuleStore.FORMAT_MARKDOWN ||
                rule.format == ZxCopyRuleStore.FORMAT_TEMPLATE
            ) {
                GsSwitchPreference(
                    checked = rule.footer,
                    onCheckedChange = { v -> update { it.copy(footer = v) } },
                    title = stringResource(R.string.zx_copy_footer),
                )
                GsSwitchPreference(
                    checked = rule.includeGroup,
                    onCheckedChange = { v -> update { it.copy(includeGroup = v) } },
                    title = stringResource(R.string.zx_copy_include_group),
                )
                GsSwitchPreference(
                    checked = rule.includeTime,
                    onCheckedChange = { v -> update { it.copy(includeTime = v) } },
                    title = stringResource(R.string.zx_copy_include_time),
                )
                GsSwitchPreference(
                    checked = rule.includeCount,
                    onCheckedChange = { v -> update { it.copy(includeCount = v) } },
                    title = stringResource(R.string.zx_copy_include_count),
                )
            }
        }

        // ---- 页脚（只在任一页脚开关打开时出现）----
        if (rule.footer || rule.image.showFooter || rule.pdf.showFooter) {
            SmallTitle(stringResource(R.string.zx_copy_section_footer))
            Card(modifier = Modifier.cardPadding()) {
                CopyValueField(
                    fieldKey = "${rule.id}-footer-text",
                    label = stringResource(R.string.zx_copy_footer_text),
                    hint = stringResource(R.string.zx_copy_footer_text) + " · " +
                        stringResource(R.string.zx_copy_footer_text_hint),
                    initial = rule.footerText,
                    showLabel = false,
                    onChange = { text -> update { r -> withFooterText(r, text) } },
                )
            }
        }

        // ---- 图片样式 ----
        SmallTitle(stringResource(R.string.zx_copy_section_image))
        Card(modifier = Modifier.cardPadding()) {
            PickerRow(
                title = stringResource(R.string.zx_copy_image_preset),
                summary = imagePresetTexts.getOrElse(zxCopyImagePresetIndex(rule.image.preset)) {
                    rule.image.preset
                },
                items = imagePresetTexts,
                selectedIndex = zxCopyImagePresetIndex(rule.image.preset),
                onPick = { index ->
                    update { it.copy(image = it.image.copy(preset = ZX_COPY_IMAGE_PRESETS[index])) }
                },
            )
            GsSwitchPreference(
                checked = rule.image.dark,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(dark = v)) } },
                title = stringResource(R.string.zx_copy_image_dark),
            )
            GsSwitchPreference(
                checked = rule.image.showHeader,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(showHeader = v)) } },
                title = stringResource(R.string.zx_copy_image_show_header),
            )
            GsSwitchPreference(
                checked = rule.image.showGroup,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(showGroup = v)) } },
                title = stringResource(R.string.zx_copy_image_show_group),
            )
            GsSwitchPreference(
                checked = rule.image.showTime,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(showTime = v)) } },
                title = stringResource(R.string.zx_copy_image_show_time),
            )
            GsSwitchPreference(
                checked = rule.image.showCount,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(showCount = v)) } },
                title = stringResource(R.string.zx_copy_image_show_count),
            )
            GsSwitchPreference(
                checked = rule.image.showFooter,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(showFooter = v)) } },
                title = stringResource(R.string.zx_copy_image_show_footer),
            )
            GsSwitchPreference(
                checked = rule.image.colorTrend,
                onCheckedChange = { v -> update { it.copy(image = it.image.copy(colorTrend = v)) } },
                title = stringResource(R.string.zx_copy_image_color_trend),
            )
            PickerRow(
                title = stringResource(R.string.zx_copy_image_rows_per_page),
                summary = rule.image.rowsPerPage.toString(),
                items = rowsPerPageChoices,
                selectedIndex = rowsPerPageChoices.indexOf(rule.image.rowsPerPage.toString())
                    .takeIf { it >= 0 } ?: 1,
                onPick = { index ->
                    update {
                        it.copy(image = it.image.copy(rowsPerPage = rowsPerPageChoices[index].toInt()))
                    }
                },
            )
            BasicComponent(
                title = stringResource(R.string.zx_copy_preview_image),
                endActions = {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { EndIconSlot { ArrowRightIcon() } }
                },
                onClick = {
                    val metrics = localContext.resources.displayMetrics
                    val bmp = runCatching {
                        ZxCopyImageRenderer(
                            ZxCopyFormat.sampleRows(), rule, previewMeta, metrics, 1080,
                        ).renderPage(0)
                    }.getOrNull() ?: return@BasicComponent
                    pageDialogs?.showImage(
                        ImageDialogSpec(
                            image = bmp.asImageBitmap(),
                            title = previewTitle,
                            closeText = previewClose,
                        ),
                    )
                },
            )
        }

        // ---- PDF 样式 ----
        SmallTitle(stringResource(R.string.zx_copy_section_pdf))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = rule.pdf.landscape,
                enabled = alive && !rule.pdf.longPage,
                onCheckedChange = { v -> update { it.copy(pdf = it.pdf.copy(landscape = v)) } },
                title = stringResource(R.string.zx_copy_pdf_landscape),
            )
            GsSwitchPreference(
                checked = rule.pdf.longPage,
                onCheckedChange = { v ->
                    update {
                        it.copy(
                            pdf = it.pdf.copy(
                                longPage = v,
                                landscape = if (v) false else it.pdf.landscape,
                            ),
                        )
                    }
                },
                title = stringResource(R.string.zx_copy_pdf_long_page),
            )
            PickerRow(
                title = stringResource(R.string.zx_copy_pdf_font_size),
                summary = "${rule.pdf.fontSize}pt",
                items = pdfFontChoices.map { "${it}pt" },
                selectedIndex = pdfFontChoices.indexOf(rule.pdf.fontSize.toString())
                    .takeIf { it >= 0 } ?: 1,
                onPick = { index ->
                    update { it.copy(pdf = it.pdf.copy(fontSize = pdfFontChoices[index].toInt())) }
                },
            )
            PickerRow(
                title = stringResource(R.string.zx_copy_pdf_margin),
                summary = "${rule.pdf.margin}pt",
                items = pdfMarginChoices.map { "${it}pt" },
                selectedIndex = pdfMarginChoices.indexOf(rule.pdf.margin.toString())
                    .takeIf { it >= 0 } ?: 1,
                onPick = { index ->
                    update { it.copy(pdf = it.pdf.copy(margin = pdfMarginChoices[index].toInt())) }
                },
            )
            GsSwitchPreference(
                checked = rule.pdf.showHeader,
                onCheckedChange = { v -> update { it.copy(pdf = it.pdf.copy(showHeader = v)) } },
                title = stringResource(R.string.zx_copy_image_show_header),
            )
            GsSwitchPreference(
                checked = rule.pdf.showFooter,
                onCheckedChange = { v -> update { it.copy(pdf = it.pdf.copy(showFooter = v)) } },
                title = stringResource(R.string.zx_copy_image_show_footer),
            )
            GsSwitchPreference(
                checked = rule.pdf.pageNumbers,
                onCheckedChange = { v -> update { it.copy(pdf = it.pdf.copy(pageNumbers = v)) } },
                title = stringResource(R.string.zx_copy_pdf_page_numbers),
            )
            GsSwitchPreference(
                checked = rule.pdf.zebra,
                onCheckedChange = { v -> update { it.copy(pdf = it.pdf.copy(zebra = v)) } },
                title = stringResource(R.string.zx_copy_pdf_zebra),
            )
        }

        // ---- 规则与导出 ----
        SmallTitle(stringResource(R.string.zx_copy_section_actions))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = rule.shareAfterExport,
                onCheckedChange = { v -> update { it.copy(shareAfterExport = v) } },
                title = stringResource(R.string.zx_copy_share_after_export),
            )
            GsSwitchPreference(
                checked = rule.isDefault,
                enabled = alive && data.rules.size > 1,
                onCheckedChange = { v ->
                    val target = if (v) {
                        lastZxCopyDefaultId = data.defaultId.takeIf { it != ruleId }
                        ruleId
                    } else {
                        lastZxCopyDefaultId
                            ?.takeIf { last -> last != ruleId && data.rules.any { it.id == last } }
                            ?: data.rules.firstOrNull { it.id != ruleId }?.id
                            ?: ruleId
                    }
                    val updated = data.rules.map { it.copy(isDefault = it.id == target) }
                    writeZxCopyRules(data.copy(defaultId = target, rules = updated))
                },
                title = stringResource(R.string.zx_copy_set_default),
            )

            val canDelete = data.rules.size > 1
            val deleteTitle = stringResource(R.string.zx_copy_menu_delete)
            val deleteConfirm = stringResource(R.string.zx_copy_delete_confirm)
            val confirmText = stringResource(R.string.action_confirm)
            val cancelText = stringResource(R.string.action_cancel)
            val dialogs = LocalDialogController.current
            BasicComponent(
                title = deleteTitle,
                summary = if (canDelete) null else stringResource(R.string.zx_copy_delete_last_hint),
                enabled = canDelete && alive,
                endActions = {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { EndIconSlot { ArrowRightIcon(canDelete && alive) } }
                },
                onClick = {
                    dialogs?.showConfirm(
                        ConfirmDialogSpec(
                            title = deleteTitle,
                            message = deleteConfirm,
                            confirmText = confirmText,
                            cancelText = cancelText,
                            onConfirm = {
                                val rest = data.rules.filter { it.id != ruleId }
                                val newDefault = data.defaultId.takeIf { id -> rest.any { it.id == id } }
                                    ?: rest.first().id
                                writeZxCopyRules(
                                    ZxCopyRuleStore.Data(
                                        defaultId = newDefault,
                                        rules = rest.map {
                                            if (it.id == newDefault) it.copy(isDefault = true) else it
                                        },
                                    ),
                                )
                                onDeleted()
                            },
                        ),
                    )
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

// ------------------------------------------------------------ 小控件

@Composable
private fun CopyNameField(
    ruleId: String,
    initial: String,
    onRename: (String) -> Unit,
) {
    val state = remember(ruleId) { TextFieldState(initial) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect { text -> if (text.isNotBlank()) onRename(text) }
    }
    TextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        label = stringResource(R.string.zx_copy_field_name),
        useLabelAsPlaceholder = true,
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        ),
    )
}

@Composable
private fun CopyValueField(
    fieldKey: String,
    label: String,
    hint: String,
    initial: String,
    showLabel: Boolean = true,
    onChange: (String) -> Unit,
) {
    val state = remember(fieldKey) { TextFieldState(initial) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect { text -> onChange(text) }
    }
    TextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        // 页脚字段不要上方那颗小标签：名字与说明一起写在字段下方
        label = if (showLabel) label else "",
        useLabelAsPlaceholder = false,
        lineLimits = TextFieldLineLimits.SingleLine,
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        ),
    )
    if (hint.isNotBlank()) CardNote(hint)
}

/**
 * 字段行：左侧字段名 + 分组，右端一颗 ⋮（上移 / 下移 / 移除）。
 *
 * 锚点每行一份；`⋮` 与字段名共用一条中线（`align(CenterVertically)`）。
 */
@Composable
private fun CopyFieldRow(
    fieldId: String,
    label: String,
    group: String,
    canUp: Boolean,
    canDown: Boolean,
    canRemove: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var anchor by remember(fieldId) { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    val upText = stringResource(R.string.zx_copy_menu_move_up)
    val downText = stringResource(R.string.zx_copy_menu_move_down)
    val removeText = stringResource(R.string.zx_copy_field_remove)
    BasicComponent(
        title = label,
        summary = group.ifBlank { null },
        endActions = {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 40dp 槽位与下面各行的 ↓ / › 一致：⋮ 的中心才会和箭头在一条竖线上
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable {
                            dialogs?.showChoice(
                                ChoiceDialogSpec(
                                    items = listOf(upText, downText, removeText),
                                    selectedIndex = -1,
                                    onSelect = { which ->
                                        when (which) {
                                            0 -> onUp()
                                            1 -> onDown()
                                            2 -> onRemove()
                                        }
                                    },
                                    anchor = anchor,
                                    disabledIndices = buildSet {
                                        if (!canUp) add(0)
                                        if (!canDown) add(1)
                                        if (!canRemove) add(2)
                                    },
                                ),
                            )
                        }
                        .onGloballyPositioned { anchor = it.boundsInWindow() },
                    contentAlignment = Alignment.Center,
                ) {
                    TintedIcon(
                        imageVector = AppIcons.MoreVert,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        },
    )
}

/**
 * 「添加字段」：弹层里按 [ZxCopyFieldCatalog.ALL] 顺序列出未选字段，
 * 每项标签带分组前缀（「行情 · 涨幅」），一眼知道加的是什么。
 */
@Composable
private fun AddFieldRow(
    selected: List<String>,
    fieldNames: Array<String>,
    groupNames: Array<String>,
    onAdd: (String) -> Unit,
) {
    val available = ZxCopyFieldCatalog.ALL.filter { it.id !in selected }
    if (available.isEmpty()) return

    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    val items = available.map { spec ->
        val name = fieldNames.getOrElse(ZxCopyFieldCatalog.index(spec.id)) { spec.id }
        val group = groupNames.getOrElse(spec.group.ordinal) { "" }
        if (group.isBlank()) name else "$group · $name"
    }

    BasicComponent(
        title = stringResource(R.string.zx_copy_add_field),
        titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
        startAction = {
            TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
        },
        endActions = {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EndIconSlot { ArrowRightIcon() }
            }
        },
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(
                    items = items,
                    selectedIndex = -1,
                    onSelect = { index -> onAdd(available[index].id) },
                    anchor = anchor,
                ),
            )
        },
    )
}

/** 点整行弹一个选择菜单（排序 / 空值 / 格式 / 动作 / 预设 / 行数 / 字号 / 页边距） */
@Composable
private fun PickerRow(
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            // `endActions` 的 Row 默认 Top 对齐；不显式居中时 ↓ 会比字段行的 ⋮ 高半格
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EndIconSlot { DownArrowIcon() }
            }
        },
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = onPick,
                    anchor = anchor,
                ),
            )
        },
    )
}

/**
 * 行尾 40dp 槽位：`⋮` / `↓` / `›` 都放在同样宽度的盒子里，
 * 视觉中心才和字段行那颗 `⋮` 落在同一条竖线上。
 */
@Composable
private fun EndIconSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
