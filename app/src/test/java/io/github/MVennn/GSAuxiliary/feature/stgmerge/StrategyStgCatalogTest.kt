package io.github.MVennn.GSAuxiliary.feature.stgmerge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「从金太阳导入」的清单里不许出现我们自己的入口卡。
 *
 * 入口卡是聚合功能在宿主列表页内存里改的名字，底下借的是别的策略的 stId。
 * 真机上第一版没过滤时，清单里混进了「聚合 · 中盘 1.5-4（13 条）」「聚合组1（12 条）」，
 * 点一下就往组里塞了一条根本不是策略的东西，名字还写着别人的条数。
 */
class StrategyStgCatalogTest {

    @Test
    fun 现行入口卡名被拦下() {
        assertTrue(StrategyStgCatalog.looksLikeEntryLabel("聚合 · 中盘 1.5-4（13 条）"))
        assertTrue(StrategyStgCatalog.looksLikeEntryLabel("聚合 · 创新高20日（4 条）"))
    }

    @Test
    fun 旧版无条数的入口卡也被拦下() {
        assertTrue(StrategyStgCatalog.looksLikeEntryLabel("聚合 · 创新高20日"))
    }

    @Test
    fun 只带条数后缀的旧卡被拦下() {
        assertTrue(StrategyStgCatalog.looksLikeEntryLabel("聚合组1（12 条）"))
    }

    @Test
    fun 用户真策略不误伤() {
        assertFalse(StrategyStgCatalog.looksLikeEntryLabel("量50"))
        assertFalse(StrategyStgCatalog.looksLikeEntryLabel("TPMP50"))
        assertFalse(StrategyStgCatalog.looksLikeEntryLabel("聚合组1"))
        assertFalse(StrategyStgCatalog.looksLikeEntryLabel("50e"))
    }
}
