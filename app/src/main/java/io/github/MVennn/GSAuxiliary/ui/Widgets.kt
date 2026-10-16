package io.github.MVennn.GSAuxiliary.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 各 Tab / 二级页共用的小部件。
 * 统一放这里，避免在多个界面文件里重复定义。
 */

/** 卡片统一左右留白：Miuix `Card` 本身是满宽的，必须由调用方补边距 */
internal val CardHMargin = 12.dp

/** 给 Miuix `Card` 用的左右留白修饰符 */
internal fun Modifier.cardPadding() = padding(horizontal = CardHMargin)

/**
 * 效果预览行 —— 模仿真实列表的一行：股票名 + 涨跌幅 + 右侧标签。
 *
 * **颜色一律由调用方传入**，且「未加自选」那一行必须传主题色（`onBackground`），
 * 不能再写死深色 —— 否则深色模式下会变成「黑底黑字」看不见。
 */
@Composable
internal fun PreviewRow(
    name: String,
    nameColor: Color,
    change: String,
    changeColor: Color,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = nameColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = change,
            color = changeColor,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = tag,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
        )
    }
}

/**
 * 高亮颜色预设项 —— 大圆色块 + 名称，选中时描主色环 + 中心对勾。
 *
 * 相比原先「色块一行 + 下面再叠一份 RadioButton 列表」的重复布局，
 * 这里一个控件同时承担「看颜色」和「选颜色」，不再重复。
 */
@Composable
internal fun ColorPresetItem(
    name: String,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 0.dp,
        animationSpec = tween(180),
        label = "ring",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        animationSpec = tween(180),
        label = "label",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(color))
                .then(
                    if (selected) {
                        Modifier.border(ringWidth, MiuixTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = name,
            color = labelColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 带染色的自绘图标。
 *
 * ## 为什么不直接用 `Icon(imageVector, tint = ...)`
 *
 * 3.1.1 实测（魅族 20 / Android 16 / miuix 0.9.3 / debug 与 release 都复现）：
 * `Icon(imageVector, tint = ...)` 与 `Image(imageVector, colorFilter = ColorFilter.tint(...))`
 * 这条**画笔记账（paint）层**的染色路径在本机不生效 —— 图标一律画成 `AppIcons` 里
 * 那个占位色 `Color.Black`，深色卡片上等于看不见（概览状态卡那个「绿对勾」多年
 * 也是一直画成黑的，只是它 30% 透明当水印，没人当 bug 报）。
 *
 * 改走 miuix `Icon(painter, tint = ColorProducer, ...)` 这个重载：它把染色挂在
 * **GraphicsLayer 的 colorFilter** 上（`layer.colorFilter = ColorFilter.tint(tint())`），
 * 与 Miuix 底栏图标同一条路 —— 实测这条能正常出颜色。
 *
 * 新增「需要染色的自绘图标」时统一走这里，别再用 `Icon(imageVector, tint = ...)`。
 */
@Composable
internal fun TintedIcon(
    imageVector: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        painter = rememberVectorPainter(imageVector),
        tint = remember(tint) { ColorProducer { tint } },
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

/** 卡片内的一行说明文字（用于放补充提示） */
@Composable
internal fun CardNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
        )
    }
}

/** 用系统浏览器打开链接；失败返回 false（调用方负责提示） */
internal fun openUrl(context: Context, url: String): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrElse { e ->
    if (e is ActivityNotFoundException) false else false
}
