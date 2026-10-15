package io.github.SansKritO4.GSAuxiliary.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.SansKritO4.GSAuxiliary.BuildConfig
import io.github.SansKritO4.GSAuxiliary.R
import io.github.SansKritO4.GSAuxiliary.app.FrameworkBridge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tab 1 · 概览 —— 模块身份 + 运行环境，**一屏内看完**。
 *
 * 页面自上而下两块：
 *   1. **状态卡**（顶部）：一眼看出模块有没有在工作（大对勾 / 大感叹号），
 *      卡片里同时给出框架版本与 API 版本
 *   2. 模块 / 运行环境 两个分组
 *
 * ## 为什么只有两组（2.8.0 精简）
 *
 * 原来还有第三组「框架」（连接状态 / 框架版本 / API 版本 / 作用域）和
 * 「配置通道」一行，以及「模块名称」一行。这些都**和顶部状态卡重复**：
 *
 *  - 框架连接、框架版本、API 版本 → 状态卡里已经有了
 *  - 配置通道（remote preferences / 本地暂存）→ 属排障信息，在「查看日志」里更合适
 *  - 模块名称 → 就是 App 名字，标题栏已经写着
 *
 * 去掉之后整页不再需要滚动，剩下的每一行都是「不看就不知道」的信息。
 *
 * 这一页**只做展示**，不放任何操作项：热重载等操作在「设置」页的「框架操作」分组。
 */
@Composable
internal fun ModuleTab() {
    val scrollState = rememberScrollState()
    val connected = rememberConnected()

    val unknown = stringResource(R.string.common_unknown)
    val unreadable = stringResource(R.string.framework_info_unreadable)
    val noFramework = stringResource(R.string.overview_status_no_framework)
    val activeTitle = stringResource(R.string.overview_status_active)
    val inactiveTitle = stringResource(R.string.overview_status_inactive)

    val moduleVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    val buildTime = BuildConfig.BUILD_TIME.ifEmpty { "—" }

    // 框架信息一次取全，只喂给顶部状态卡
    val framework = remember(connected.value, unknown, unreadable, noFramework) {
        val svc = FrameworkBridge.service()
        if (svc == null) {
            FrameworkInfo(active = false, version = noFramework, api = "—")
        } else {
            val version = runCatching {
                listOfNotNull(svc.frameworkName, svc.frameworkVersion)
                    .joinToString(" ")
                    .ifEmpty { unreadable }
            }.getOrDefault(unreadable)
            val api = runCatching { "API ${svc.apiVersion}" }.getOrDefault(unknown)
            FrameworkInfo(active = true, version = version, api = api)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        // 玻璃顶栏时内容铺到屏幕顶边，首屏空白在这里补（见 Glass.kt 的 LocalTopInset）
        Spacer(Modifier.height(8.dp + LocalTopInset.current))

        // --- 顶部状态卡：已激活 / 未激活 ---
        OverviewStatusCard(
            active = framework.active,
            title = if (framework.active) activeTitle else inactiveTitle,
            version = framework.version,
            api = framework.api,
            modifier = Modifier.cardPadding(),
        )

        // --- 模块自身 ---
        SmallTitle(stringResource(R.string.section_overview_module))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.info_module_version),
                summary = moduleVersion,
            )
            BasicComponent(
                title = stringResource(R.string.info_build_time),
                summary = buildTime,
            )
        }

        // --- 手机与依赖库 ---
        SmallTitle(stringResource(R.string.section_overview_runtime))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.info_android_version),
                summary = androidVersion,
            )
            BasicComponent(
                title = stringResource(R.string.info_device_model),
                summary = deviceModel,
            )
            BasicComponent(
                title = stringResource(R.string.info_miuix_version),
                summary = BuildConfig.MIUIX_VERSION,
            )
        }

        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

/** 框架信息的一次性快照（只给顶部状态卡用） */
private data class FrameworkInfo(
    val active: Boolean,
    val version: String,
    val api: String,
)

/**
 * 概览页顶部的状态卡 —— 对应 LSPosed 页面顶部那张「已激活 + 大对勾」的卡片。
 *
 * ## Miuix 里没有现成的这个组件
 *
 * 把 `miuix-ui` / `miuix-core` / `miuix-preference` 0.9.3 的类清单过了一遍：
 * `Card` / `BasicComponent` / `SmallTitle` / `Checkbox` / `Badge` /
 * `ProgressIndicator` / `Snackbar` / `Tooltip` … 都在，但**没有**「状态卡 / Hero 卡」
 * 这一类复合组件；LSPosed 那张卡也是它自己用 Miuix 基础件拼出来的。
 * 所以这里按 Miuix 的视觉规范自绘一个等效实现：
 * `Card`（自定义配色）+ 左侧三行文字 + 右下角一枚超大的装饰图标。
 *
 * ## 版式调整
 *
 *  - **字号上调一档**（2.9.2）：标题 22sp / 版本 16sp / API 15sp —— 状态文案是
 *    这张卡的主信息，原尺寸在卡片里显得偏小；
 *  - **卡片高度固定**（2.9.3）：[StatusCardMinHeight] = 148dp，三行文字垂直居中。
 *    装饰图标放进 `matchParentSize` 容器，**不再参与高度测量** ——
 *    于是「只调字号」不会连带改变卡片高度（2.9.1 / 2.9.2 时高度其实是被图标
 *    顶到 150 / 170dp 的，见 [StatusCardMinHeight] 的注释）；
 *  - **图标放大并移到右下角**（2.9.1 / 2.9.2 / 2.9.3）：图标尺寸 [StatusCardIconSize]，
 *    `align(BottomEnd)` 后各推半个身位、再回缩 [StatusCardIconInset]，于是卡内露出
 *    图标的左上约 65%（111dp 见方），成为一枚角落里的装饰弧线。
 *
 *    > Miuix `Card` 的 `BasicCard` 内部走 `squircleSurface`，其中调用了
 *    > `ClipKt.clip(modifier, shape)`（`javap -c` 逆 `SquircleBackgroundKt` 确认），
 *    > 所以**不需要自己再包一层裁剪**，超出卡片范围的部分会被自动切掉。
 *
 * 图标走 [AppIcons] 自绘（不引图标库，理由见该文件注释）。
 *
 * @param active 框架是否已连接（决定配色与右侧图标）
 */
@Composable
private fun OverviewStatusCard(
    active: Boolean,
    title: String,
    version: String,
    api: String,
    modifier: Modifier = Modifier,
) {
    // 绿色 = 正常工作；红色 = 没接上框架。都取低透明度做卡面，深浅色主题下都能用
    val accent = if (active) Color(0xFF1AAE6A) else MiuixTheme.colorScheme.error

    Card(
        modifier = modifier,
        // 内边距改由文字列自己加：装饰图标要溢出到卡片角外，不能被 Card 的内边距挡住
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = accent.copy(alpha = 0.14f),
            contentColor = MiuixTheme.colorScheme.onBackground,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StatusCardMinHeight),
        ) {
            // --- 左侧三行文字：垂直居中（2.9.2 起字号整体上调一档） ---
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp, end = 16.dp),
            ) {
                Text(
                    text = title,
                    color = accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = version,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = api,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 15.sp,
                )
            }

            // --- 右下角装饰图标 ---
            //
            // ⚠️ 必须放在 `matchParentSize` 的容器里：`matchParentSize` 的节点
            // **不参与父级尺寸测量**，所以这枚 170dp 的图标不会再把卡片撑高。
            // 直接把它当 Box 的子节点时，`Modifier.size(170.dp)` 会把 Box 顶到 170dp
            // —— 那正是 2.9.1 / 2.9.2 卡片「越改越高」的真正原因（`offset` 只挪位置、
            // 不改测量尺寸）。现在卡片高度只由文字内容 + [StatusCardMinHeight] 决定。
            //
            // offset 正向 = 向右 / 向下；推「半个身位 - 内缩量」，图标中心就落在
            // 卡片右下角的左上方。溢出部分由 Card 自身的 squircle 裁剪切掉。
            Box(Modifier.matchParentSize()) {
                Icon(
                    imageVector = if (active) AppIcons.CheckCircle else AppIcons.ErrorCircle,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.16f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(
                            x = StatusCardIconSize / 2f - StatusCardIconInset,
                            y = StatusCardIconSize / 2f - StatusCardIconInset,
                        )
                        .size(StatusCardIconSize),
                )
            }
        }
    }
}

/**
 * 状态卡固定高度（`heightIn(min = ...)`）。
 *
 * 文字内容本身只有约 82dp（22 / 16 / 15sp 三行），这里给到 148dp 是为了让
 * 三行文字垂直居中、留白从容。
 *
 * **2.9.3 起这确实是卡片的实际高度**：装饰图标改放进 `matchParentSize` 容器后
 * 不再参与测量，卡片高度不再被图标顶高。之前（2.9.1 / 2.9.2）虽然写了这个 min，
 * 但图标分别有 150dp / 170dp，实际高度一直是「图标说了算」——
 * 也就是说 2.9.1 的「纵向拉长」其实并没有按这个值生效，2.9.3 才真正落到 148dp。
 *
 * 选 148dp 而不是「内容自然高度」还有一个原因：高度固定之后，
 * **只调字号不会再改变卡片高度**（用户 2.9.3 的要求）。
 */
private val StatusCardMinHeight = 148.dp

/**
 * 右下角装饰图标的尺寸（2.9.2 由 150dp 放大到 170dp）。
 *
 * 图标在 `matchParentSize` 容器里，所以这个尺寸**不影响卡片高度**，
 * 只决定露出的弧度有多大。
 */
private val StatusCardIconSize = 170.dp

/**
 * 图标中心相对卡片右下角**向左上**内缩的量（2.9.2 新增 16dp，2.9.3 加大到 26dp）。
 *
 * 取 0 时中心正好压在角上，只露左上四分之一；内缩越多露出越多。
 * 现在露出的是 `图标一半 + inset` = 85 + 26 = **111dp** 见方（约占图标 65%），
 * 观感更像「有意的角落装饰」而不是「被裁掉的图标」。
 */
private val StatusCardIconInset = 26.dp
