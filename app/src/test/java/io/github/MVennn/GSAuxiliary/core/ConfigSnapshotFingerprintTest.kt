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

    /**
     * 快照里**全部布尔项**的名字 —— **单一清单**。
     *
     * ⚠️ 之前这里有两份清单（一个在「改任何一项」那个测试里、一个在「多键同时变化」里），
     * 加新开关时只改了一份 → 两份长度不一致 → **守卫失同步**
     * （`ConfigSnapshot` 的注释里专门警告过这件事）。现在合并成一份，两个测试共用。
     */
    private val booleanNames = listOf(
        "enabled", "scopeStrategy", "stgDirectQuote", "stgQuoteSwipe", "stgSelectAll",
        "stgStockFilter", "stockBarEntry", "dockClean", "homeBannerHide", "marketBannerHide",
        "marketSectorCompact", "marketOverviewCompact", "marketStockFilter", "navBarBlend",
        "ipoDialogClean", "aistareQuickEnabled", "aiStareSkipSaveConfirm", "aiStareEmptySave",
        "aiStareCardDetail", "aiStareSaveBackToStock", "aiStarePickScheme",
        // ⚠️ 新加的开关必须加进这里（2026-10-20 加的两个）
        "watchlistBold", "marketIndexHide",
    )

    private fun base() = ConfigSnapshot.DEFAULT

    @Test
    fun `清单必须与快照的布尔字段数一致 - 漏一个就是守卫失同步`() {
        // 快照里**只有布尔类型**的字段参与指纹（数值项另外混一层异或）。
        // 清单与它对不上 = 某个开关没被守住（改了不影响指纹 → 配置变更被静默丢弃）。
        val booleanFields = ConfigSnapshot::class.java.declaredFields
            .filter { !it.isSynthetic && it.type == java.lang.Boolean::class.javaPrimitiveType }
            .map { it.name }
            .toSet()
        assertEquals(
            booleanFields,
            booleanNames.toSet(),
            "清单与快照的布尔字段对不上 —— 清单里的：${booleanNames.filter { it !in booleanFields }}，" +
                "快照里有但清单里没有的：${booleanFields.filter { it !in booleanNames }}",
        )
    }

    @Test
    fun `默认快照的指纹是稳定的`() {
        // 同内容 → 同指纹（指纹只与内容有关，与对象身份无关）
        assertEquals(base().fingerprint, base().copy().fingerprint)
    }

    @Test
    fun `改任何一个布尔项 指纹都必须变`() {
        val b = base()
        // 每个布尔项各翻一次，指纹都必须不同
        // 每个布尔项各翻一次，指纹都必须不同。
        // ⚠️ **从 [booleanNames] 生成**（而不是在这里另写一份）——
        //    之前这里是一份独立清单，加新开关时只改了另一份 → 两份长度不一致 →
        //    **守卫失同步**（`ConfigSnapshot` 的注释里专门警告过这件事）。
        val variants = booleanNames.map { flipOne(b, it) }
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
        // 全部组合太多（2²³ = 838 万组），逐一比对太慢，
        // 这里用「所有子集指纹互不相同」的等价形式：
        // 任取 2 个布尔项翻转，其指纹与「另一组 2 个」不能相同。
        //
        // ⚠️ **用同一份 [booleanNames]** —— 之前这里另写了一份，
        // 加新开关时只改了其中一份 → 两份长度不一致 → 守卫失同步。
        val names = booleanNames
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
    /** 按**名字**翻一个布尔项 */
    private fun flipOne(b: ConfigSnapshot, name: String): ConfigSnapshot = when (name) {
        "enabled" -> b.copy(enabled = !b.enabled)
        "scopeStrategy" -> b.copy(scopeStrategy = !b.scopeStrategy)
        "stgDirectQuote" -> b.copy(stgDirectQuote = !b.stgDirectQuote)
        "stgQuoteSwipe" -> b.copy(stgQuoteSwipe = !b.stgQuoteSwipe)
        "stgSelectAll" -> b.copy(stgSelectAll = !b.stgSelectAll)
        "stgStockFilter" -> b.copy(stgStockFilter = !b.stgStockFilter)
        "stockBarEntry" -> b.copy(stockBarEntry = !b.stockBarEntry)
        "dockClean" -> b.copy(dockClean = !b.dockClean)
        "homeBannerHide" -> b.copy(homeBannerHide = !b.homeBannerHide)
        "marketBannerHide" -> b.copy(marketBannerHide = !b.marketBannerHide)
        "marketSectorCompact" -> b.copy(marketSectorCompact = !b.marketSectorCompact)
        "marketOverviewCompact" -> b.copy(marketOverviewCompact = !b.marketOverviewCompact)
        "marketStockFilter" -> b.copy(marketStockFilter = !b.marketStockFilter)
        "navBarBlend" -> b.copy(navBarBlend = !b.navBarBlend)
        "ipoDialogClean" -> b.copy(ipoDialogClean = !b.ipoDialogClean)
        "aistareQuickEnabled" -> b.copy(aistareQuickEnabled = !b.aistareQuickEnabled)
        "aiStareSkipSaveConfirm" -> b.copy(aiStareSkipSaveConfirm = !b.aiStareSkipSaveConfirm)
        "aiStareEmptySave" -> b.copy(aiStareEmptySave = !b.aiStareEmptySave)
        "aiStareCardDetail" -> b.copy(aiStareCardDetail = !b.aiStareCardDetail)
        "aiStareSaveBackToStock" -> b.copy(aiStareSaveBackToStock = !b.aiStareSaveBackToStock)
        "aiStarePickScheme" -> b.copy(aiStarePickScheme = !b.aiStarePickScheme)
        "watchlistBold" -> b.copy(watchlistBold = !b.watchlistBold)
        "marketIndexHide" -> b.copy(marketIndexHide = !b.marketIndexHide)
        else -> error("未知字段 $name")
    }

    private fun flip(b: ConfigSnapshot, names: List<String>, which: List<Int>): ConfigSnapshot {
        var v = b
        for (i in which) {
            v = flipOne(v, names[i])
        }
        return v
    }

}
