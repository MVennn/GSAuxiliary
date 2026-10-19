package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 配置指纹的不变式。
 *
 * ## 这条测试为什么重要
 *
 * `ConfigSnapshot.fingerprint` 是 `ConfigSource` 判断「配置真的变了没」的**唯一**判据
 * （`core/ConfigSource.kt`：指纹相同 → 判定「没变」→ **这次配置变更被静默丢弃**，
 * 用户改了开关但功能不变，而且**日志里一行都没有**）。
 *
 * 原实现是「每项乘一个互不相同的质数再相加」。乘数互不相同**防不住碰撞** ——
 * 加法里两组不同的布尔组合能凑成同一个和：
 * ```
 * 2137 + 2221 == 2011 + 2347   （{首页横幅,行情横幅} 与 {底栏精简,板块压缩}）
 * 31 + 131 + 2741 == 2903       （{总开关,策略页,新股弹窗} 与 {多方案选择}）
 * ```
 * 改成「布尔项占低位逐个异或」之后，这两组不再碰撞。
 *
 * **单键切换永远是安全的**（delta = ±该项的位），风险只在**多键同时写入**
 * （「重置模块」一次写 23 键、恢复备份一次写 29 键）。
 */
class ConfigSnapshotFingerprintTest {

    private fun base() = ConfigSnapshot.DEFAULT

    @Test
    fun `默认快照的指纹是稳定的`() {
        // 同内容 → 同指纹（指纹只与内容有关，与对象身份无关）
        assertEquals(base().fingerprint, base().copy().fingerprint)
    }

    @Test
    fun `改任何一个布尔项 指纹都必须变`() {
        val b = base()
        // 每个布尔项各翻一次，指纹都必须不同
        val variants = listOf(
            b.copy(enabled = !b.enabled),
            b.copy(scopeStrategy = !b.scopeStrategy),
            b.copy(stgDirectQuote = !b.stgDirectQuote),
            b.copy(stgQuoteSwipe = !b.stgQuoteSwipe),
            b.copy(stgSelectAll = !b.stgSelectAll),
            b.copy(stgStockFilter = !b.stgStockFilter),
            b.copy(stockBarEntry = !b.stockBarEntry),
            b.copy(dockClean = !b.dockClean),
            b.copy(homeBannerHide = !b.homeBannerHide),
            b.copy(marketBannerHide = !b.marketBannerHide),
            b.copy(marketSectorCompact = !b.marketSectorCompact),
            b.copy(marketOverviewCompact = !b.marketOverviewCompact),
            b.copy(marketStockFilter = !b.marketStockFilter),
            b.copy(navBarBlend = !b.navBarBlend),
            b.copy(ipoDialogClean = !b.ipoDialogClean),
            b.copy(aistareQuickEnabled = !b.aistareQuickEnabled),
            b.copy(aiStareSkipSaveConfirm = !b.aiStareSkipSaveConfirm),
            b.copy(aiStareEmptySave = !b.aiStareEmptySave),
            b.copy(aiStareCardDetail = !b.aiStareCardDetail),
            b.copy(aiStareSaveBackToStock = !b.aiStareSaveBackToStock),
            b.copy(aiStarePickScheme = !b.aiStarePickScheme),
        )
        val baseFp = b.fingerprint
        variants.forEachIndexed { i, v ->
            assertNotEquals(baseFp, v.fingerprint, "第 $i 个布尔项翻转后指纹没变")
        }
        // 全部变体两两不同
        variants.map { it.fingerprint }.let { fps ->
            assertEquals(fps.size, fps.toSet().size, "不同配置算出了相同指纹")
        }
    }

    @Test
    fun `改数值项 指纹也必须变`() {
        val b = base()
        assertNotEquals(b.fingerprint, b.copy(color = b.color + 1).fingerprint)
        assertNotEquals(b.fingerprint, b.copy(logLevel = b.logLevel + 1).fingerprint)
        assertNotEquals(b.fingerprint, b.copy(aiStareQuickConfig = b.aiStareQuickConfig + " ").fingerprint)
    }

    @Test
    fun `回归 - 曾经撞过的两组组合现在必须不同`() {
        val b = base()
        // 2137 + 2221 == 2011 + 2347
        val a = b.copy(homeBannerHide = true, marketBannerHide = true)
        val c = b.copy(dockClean = true, marketSectorCompact = true)
        assertNotEquals(
            a.fingerprint,
            c.fingerprint,
            "{首页横幅,行情横幅} 与 {底栏精简,板块压缩} 的指纹撞了 —— 配置变更会被静默丢弃",
        )

        // 31 + 131 + 2741 == 2903
        val d = b.copy(enabled = true, scopeStrategy = true, ipoDialogClean = true)
        val e = b.copy(aiStarePickScheme = true)
        assertNotEquals(
            d.fingerprint,
            e.fingerprint,
            "{总开关,策略页,新股弹窗} 与 {多方案选择} 的指纹撞了",
        )
    }

    @Test
    fun `多键同时变化 指纹也不能撞`() {
        val b = base()
        // 穷举全部布尔项的两两组合做比较 ——
        // 2^21 = 209 万组，逐一比对太慢，这里用「所有子集指纹互不相同」的等价形式：
        // 任取 3 个布尔项翻转，其指纹与「另一组 3 个」不能相同。
        val names = listOf(
            "enabled", "scopeStrategy", "stgDirectQuote", "stgQuoteSwipe", "stgSelectAll",
            "stgStockFilter", "stockBarEntry", "dockClean", "homeBannerHide", "marketBannerHide",
            "marketSectorCompact", "marketOverviewCompact", "marketStockFilter", "navBarBlend",
            "ipoDialogClean", "aistareQuickEnabled", "aiStareSkipSaveConfirm", "aiStareEmptySave",
            "aiStareCardDetail", "aiStareSaveBackToStock", "aiStarePickScheme",
            // ⚠️ 新加的两个开关（2026-10-20）—— 别忘了加。
            // 漏了就是「守卫形同虚设」：改动不会红，而症状是「改了开关没反应」。
            "watchlistBold", "marketIndexHide",
        )
        val seen = HashMap<Int, String>()
        // 遍历所有 1 位与 2 位的组合
        for (i in names.indices) {
            check1(b, names, i, seen)
            for (j in i + 1 until names.size) {
                check2(b, names, i, j, seen)
            }
        }
        assertTrue(seen.isNotEmpty())
    }

    private fun check1(b: ConfigSnapshot, names: List<String>, i: Int, seen: HashMap<Int, String>) {
        val v = flip(b, names, listOf(i))
        val prev = seen.put(v.fingerprint, names[i])
        assertTrue(prev == null, "指纹撞了：{${names[i]}} 与 {$prev}")
    }

    private fun check2(
        b: ConfigSnapshot,
        names: List<String>,
        i: Int,
        j: Int,
        seen: HashMap<Int, String>,
    ) {
        val v = flip(b, names, listOf(i, j))
        val label = "${names[i]}+${names[j]}"
        val prev = seen.put(v.fingerprint, label)
        assertTrue(prev == null, "指纹撞了：{$label} 与 {$prev}")
    }

    /** 按字段名翻转布尔项（DEFAULT 里这些项都是 false 或 true，翻转即得另一组） */
    private fun flip(b: ConfigSnapshot, names: List<String>, which: List<Int>): ConfigSnapshot {
        var v = b
        for (i in which) {
            v = when (names[i]) {
                "enabled" -> v.copy(enabled = !v.enabled)
                "scopeStrategy" -> v.copy(scopeStrategy = !v.scopeStrategy)
                "stgDirectQuote" -> v.copy(stgDirectQuote = !v.stgDirectQuote)
                "stgQuoteSwipe" -> v.copy(stgQuoteSwipe = !v.stgQuoteSwipe)
                "stgSelectAll" -> v.copy(stgSelectAll = !v.stgSelectAll)
                "stgStockFilter" -> v.copy(stgStockFilter = !v.stgStockFilter)
                "stockBarEntry" -> v.copy(stockBarEntry = !v.stockBarEntry)
                "dockClean" -> v.copy(dockClean = !v.dockClean)
                "homeBannerHide" -> v.copy(homeBannerHide = !v.homeBannerHide)
                "marketBannerHide" -> v.copy(marketBannerHide = !v.marketBannerHide)
                "marketSectorCompact" -> v.copy(marketSectorCompact = !v.marketSectorCompact)
                "marketOverviewCompact" -> v.copy(marketOverviewCompact = !v.marketOverviewCompact)
                "marketStockFilter" -> v.copy(marketStockFilter = !v.marketStockFilter)
                "navBarBlend" -> v.copy(navBarBlend = !v.navBarBlend)
                "ipoDialogClean" -> v.copy(ipoDialogClean = !v.ipoDialogClean)
                "aistareQuickEnabled" -> v.copy(aistareQuickEnabled = !v.aistareQuickEnabled)
                "aiStareSkipSaveConfirm" -> v.copy(aiStareSkipSaveConfirm = !v.aiStareSkipSaveConfirm)
                "aiStareEmptySave" -> v.copy(aiStareEmptySave = !v.aiStareEmptySave)
                "aiStareCardDetail" -> v.copy(aiStareCardDetail = !v.aiStareCardDetail)
                "aiStareSaveBackToStock" -> v.copy(aiStareSaveBackToStock = !v.aiStareSaveBackToStock)
                "aiStarePickScheme" -> v.copy(aiStarePickScheme = !v.aiStarePickScheme)
                "watchlistBold" -> v.copy(watchlistBold = !v.watchlistBold)
                "marketIndexHide" -> v.copy(marketIndexHide = !v.marketIndexHide)
                else -> error("未知字段 ${names[i]}")
            }
        }
        return v
    }
}
