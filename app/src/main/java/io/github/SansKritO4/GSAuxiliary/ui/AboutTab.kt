package io.github.SansKritO4.GSAuxiliary.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.SansKritO4.GSAuxiliary.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** Tab 4 · 关于 —— 作者、开源鸣谢、免责声明。 */
@Composable
internal fun AboutTab(onOpenDisclaimer: () -> Unit) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    val linkFailed = stringResource(R.string.oss_link_failed)
    // 可跳转地址一律先取出来，避免在 onClick 里调 stringResource
    val githubUrl = stringResource(R.string.about_value_github)
    val repoUrl = stringResource(R.string.about_value_repo_url)

    fun open(url: String) {
        if (!openUrl(ctx, url)) {
            Toast.makeText(ctx, linkFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        // 玻璃顶栏时内容铺到屏幕顶边，首屏空白在这里补（见 Glass.kt 的 LocalTopInset）
        Spacer(Modifier.height(8.dp + LocalTopInset.current))

        // 2.17.2 起关于页只回答「谁写的、用了谁的东西、有什么后果」：
        // 「模块信息」整组（模块名称 / 版本 / 作用范围 / 目标框架）与「技术细节」
        // 整组（Hook 入口 / 挂钩点 / 判定依据 / 控件 ID / 配置通道）都请出了界面 ——
        // 模块名称与顶栏标题重复、版本在「概览」页已有（一页一处，不重复摆）、
        // 其余是用户看不懂的开发者向字段。完整档案在 docs/ARCHITECTURE.md、
        // docs/HOOK-NOTES.md 与 docs/REVERSE-ENGINEERING.md。
        SmallTitle(stringResource(R.string.section_author))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.about_developer),
                summary = stringResource(R.string.about_value_developer),
            )
            // GitHub 主页：可点击跳转，方便反馈与查看源码。
            ArrowPreference(
                title = stringResource(R.string.about_github),
                summary = githubUrl,
                onClick = { open(githubUrl) },
            )
            BasicComponent(
                title = stringResource(R.string.about_license),
                summary = stringResource(R.string.about_value_license),
            )
            // 项目仓库：可点击跳转（与上面 GitHub 主页同一套交互）
            ArrowPreference(
                title = stringResource(R.string.about_repo),
                summary = repoUrl,
                onClick = { open(repoUrl) },
            )
        }

        // --- 开源鸣谢：逐条可点击跳转 ---
        SmallTitle(stringResource(R.string.section_open_source))
        Card(modifier = Modifier.cardPadding()) {
            ArrowPreference(
                title = stringResource(R.string.oss_miuix),
                summary = stringResource(R.string.oss_miuix_desc),
                onClick = { open("https://github.com/miuix-kotlin-multiplatform/miuix") },
            )
            ArrowPreference(
                title = stringResource(R.string.oss_lsposed),
                summary = stringResource(R.string.oss_lsposed_desc),
                onClick = { open("https://github.com/LSPosed/LSPosed") },
            )
            ArrowPreference(
                title = stringResource(R.string.oss_liquid_glass),
                summary = stringResource(R.string.oss_liquid_glass_desc),
                onClick = { open("https://github.com/Kyant0/AndroidLiquidGlass") },
            )
            ArrowPreference(
                title = stringResource(R.string.oss_material_icons),
                summary = stringResource(R.string.oss_material_icons_desc),
                onClick = { open("https://fonts.google.com/icons") },
            )
        }

        // --- 免责声明：一行摘要，点进去看完整文本 ---
        SmallTitle(stringResource(R.string.section_disclaimer))
        Card(modifier = Modifier.cardPadding()) {
            ArrowPreference(
                title = stringResource(R.string.disclaimer_title),
                summary = stringResource(R.string.disclaimer_body),
                onClick = onOpenDisclaimer,
            )
        }

        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

/**
 * 免责声明全文（二级页内容）。
 *
 * 正文是一整块 Markdown（见 `R.string.disclaimer_full`），交给 [MarkdownText] 渲染：
 * 一级标题、二级标题、加粗、斜体、列表、引用各走各的字号与字重，长文才有结构。
 *
 * 文案必须是「单行 + `\n` 转义」形态 —— Android 会折叠字符串里的真实换行，
 * 一折叠空行就没了，Markdown 的分段和标题全部失效。
 */
@Composable
internal fun DisclaimerContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            MarkdownText(
                markdown = stringResource(R.string.disclaimer_full),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        // 二级页里 LocalDockInset 恒为 0（那口子是给一级页悬浮底栏的），
        // 这里让的是手势导航条的高度 —— 见 SubScreen 的注释。
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

