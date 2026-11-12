package io.github.MVennn.GSAuxiliary.feature.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 筛选模型（八项两组）的语义真值表。
 *
 * ## 这条测试为什么重要
 *
 * 筛选条件是策略页与行情页**共用**的一套（`feature/filter/`）—— 两边任一处改动
 * 都会同时影响两个页面。而这个模型承载的是「哪些股票该显示」，
 * 一旦语义被悄悄改掉，用户看到的是「筛选结果不对」，且**没有任何日志**。
 *
 * 历史上有过「改判定语义（不是纯缓存）」被否的先例 ——
 * 「可直读 zdf 整段跳过兜底」就因为会改变筛选结果而没做。
 * 这份真值表把语义钉死，让以后任何改动都必须显式更新它。
 */
class StockFiltersTest {

    @Test
    fun `默认什么都不筛`() {
        val f = StockFilters.OFF
        assertFalse(f.directionFiltering, "默认不该做方向过滤")
        assertFalse(f.typeFiltering, "默认不该做类型过滤")
        assertFalse(f.active, "默认不该有任何筛选生效")
        // 不筛 = 全部保留
        assertTrue(f.keepsDirection(inWatchlist = true, percent = 3.0))
        assertTrue(f.keepsDirection(inWatchlist = false, percent = -3.0))
        assertTrue(f.keepsDirection(inWatchlist = false, percent = null))
    }

    @Test
    fun `自选优先 - 自选股即使在涨也不归到涨家`() {
        val f = StockFilters(up = true)   // 只勾涨家
        assertEquals(Category.ZX, StockFilters.categoryOf(inWatchlist = true, percent = 3.0))
        // 自选优先 → 自选股按 ZX 类别判，而 ZX 没勾 → 不显示
        assertFalse(f.keepsDirection(inWatchlist = true, percent = 3.0))
        // 非自选且在涨 → 涨家 → 勾了 → 显示
        assertTrue(f.keepsDirection(inWatchlist = false, percent = 3.0))
    }

    @Test
    fun `平盘不属于任何类别 - 开了方向筛选就一律不显示`() {
        val f = StockFilters(up = true, down = true, zx = true)   // 三个全勾
        assertEquals(Category.FLAT, StockFilters.categoryOf(inWatchlist = false, percent = 0.0))
        assertEquals(Category.FLAT, StockFilters.categoryOf(inWatchlist = false, percent = null))
        assertFalse(
            f.keepsDirection(inWatchlist = false, percent = 0.0),
            "平盘在开了方向筛选时必须被排除（未勾选的类别不显示）",
        )
        assertFalse(f.keepsDirection(inWatchlist = false, percent = null))
    }

    @Test
    fun `方向三项都不勾就不做方向过滤`() {
        val f = StockFilters(up = false, down = false, zx = false)
        assertFalse(f.directionFiltering)
        assertTrue(f.keepsDirection(inWatchlist = false, percent = 0.0))
        assertTrue(f.keepsDirection(inWatchlist = true, percent = null))
    }

    @Test
    fun `涨跌两个方向互斥`() {
        val f = StockFilters(up = true, down = false)
        assertTrue(f.keepsDirection(inWatchlist = false, percent = 1.5))
        assertFalse(f.keepsDirection(inWatchlist = false, percent = -1.5))
    }

    @Test
    fun `类型五项全勾等于不做类型过滤`() {
        val f = StockFilters(cyb = true, kcb = true, bzA = true, wkb = true, st = true)
        assertFalse(f.typeFiltering)
        assertTrue(f.keepsTypes(StockTypes.NONE))
        assertTrue(f.keepsTypes(StockTypes.CYB))
        assertTrue(f.keepsTypes(StockTypes.ST))
    }

    @Test
    fun `类型只要有一个没勾 命中它的就被排除`() {
        // 只取消勾 ST：命中 ST 的被排除，其它类型不受影响
        val f = StockFilters(cyb = true, kcb = true, bzA = true, wkb = true, st = false)
        assertTrue(f.typeFiltering)
        assertFalse(f.keepsTypes(StockTypes.ST), "ST 没勾 → 命中 ST 的要排除")
        assertTrue(f.keepsTypes(StockTypes.CYB), "ST 没勾不该影响创业板")
    }

    @Test
    fun `ST 科创板这种同时属于两类的 取消任一项都要排除`() {
        val f = StockFilters(cyb = true, kcb = true, bzA = true, wkb = true, st = false)
        val both = StockTypes.KCB or StockTypes.ST
        assertFalse(
            f.keepsTypes(both),
            "*ST 卓然 = 科创板 + ST股；只取消 ST 也必须排除它（多类型的判定是「任一没勾就排除」）",
        )
    }

    @Test
    fun `两个维度取交集`() {
        // 方向勾涨家，类型取消创业板
        val f = StockFilters(up = true, cyb = false)
        // 一只「创业板 + 涨」的股票：方向满足但类型不满足 → 排除
        assertFalse(f.keeps(inWatchlist = false, percent = 3.0, types = StockTypes.CYB))
        // 一只「主板 + 涨」的股票：两个维度都满足 → 保留
        assertTrue(f.keeps(inWatchlist = false, percent = 3.0, types = StockTypes.NONE))
    }

    @Test
    fun `自选叠方向 - 勾自选又勾涨家 自选里的跌家要剔除`() {
        val f = StockFilters(up = true, zx = true)
        // 自选 + 涨 → 留下
        assertTrue(f.keepsDirection(inWatchlist = true, percent = 2.5), "自选里的涨家该留下")
        // 自选 + 跌 → 用户报的就是这一只：只勾自选时它会留下，勾了涨家之后必须剔除
        assertFalse(f.keepsDirection(inWatchlist = true, percent = -1.2), "自选里的跌家必须被剔除")
        // 自选 + 平盘 → 不属于任何一侧
        assertFalse(f.keepsDirection(inWatchlist = true, percent = 0.0), "自选里的平盘不属于涨家")
        // 自选 + 涨幅读不到 → 不能当涨家
        assertFalse(f.keepsDirection(inWatchlist = true, percent = null), "读不到涨跌幅时不能算涨家")
        // 非自选：照旧按类别判
        assertTrue(f.keepsDirection(inWatchlist = false, percent = 2.5))
        assertFalse(f.keepsDirection(inWatchlist = false, percent = -1.2))
    }

    @Test
    fun `自选叠方向 - 只勾自选时仍然显示全部自选`() {
        val f = StockFilters(zx = true)
        assertTrue(f.keepsDirection(inWatchlist = true, percent = -5.0), "没勾涨跌，自选里的跌家也要留")
        assertTrue(f.keepsDirection(inWatchlist = true, percent = 0.0))
        assertTrue(f.keepsDirection(inWatchlist = true, percent = null))
        assertFalse(f.keepsDirection(inWatchlist = false, percent = 5.0), "非自选照旧按类别排除")
    }

    @Test
    fun `自选叠方向 - 勾自选加跌家只留自选里的跌家`() {
        val f = StockFilters(down = true, zx = true)
        assertTrue(f.keepsDirection(inWatchlist = true, percent = -3.0))
        assertFalse(f.keepsDirection(inWatchlist = true, percent = 3.0), "自选里的涨家被跌家条件排除")
        assertTrue(f.keepsDirection(inWatchlist = false, percent = -3.0))
        assertFalse(f.keepsDirection(inWatchlist = false, percent = 3.0))
    }

    @Test
    fun `自选叠方向 - 三项全勾仍按方向限制自选`() {
        val f = StockFilters(up = true, down = true, zx = true)
        assertTrue(f.keepsDirection(inWatchlist = true, percent = 4.0))
        assertTrue(f.keepsDirection(inWatchlist = true, percent = -4.0))
        assertFalse(f.keepsDirection(inWatchlist = true, percent = 0.0), "平盘仍不属任何类别")
        assertFalse(f.keepsDirection(inWatchlist = true, percent = null))
    }

    @Test
    fun `自选叠方向 - 读不到涨幅的自选股在勾了方向后要剔除`() {
        // 涨幅字段读不到（percent == null）时无法判断它落在哪一侧：勾自选 + 勾涨家，
        // 这只股票既不能证明自己涨了，也不能算平盘保留 —— 只能当作「未勾选的类别」排除。
        // 早先这条分支直接 return true，结果是筛了跟没筛一样。
        val f = StockFilters(up = true, zx = true)
        assertFalse(f.keepsDirection(inWatchlist = true, percent = null))
        assertTrue(f.keepsDirection(inWatchlist = true, percent = 3.0))
        // 只勾自选（不看方向）时才保留全部自选，包括读不到涨幅的
        assertTrue(StockFilters(zx = true).keepsDirection(inWatchlist = true, percent = null))
    }

    @Test
    fun `八项每一项都能独立改变结果 - 没有哑项`() {
        // 逐项翻转后「这只股票是否显示」必须变化 —— 防止「界面上有这一项、底下不起作用」
        // （历史上出过一次「跳过保存确认」是假开关：isEnabled 的 or 链漏了一位）
        val base = StockFilters.OFF
        // 一只「涨 + 创业板 + ST」的股票：方向与类型两个维度都能被单独影响
        fun result(f: StockFilters) = f.keeps(
            inWatchlist = false,
            percent = 2.0,
            types = StockTypes.CYB or StockTypes.ST,
        )

        val baseResult = result(base)
        assertTrue(baseResult, "基准场景下应当显示")

        // 方向三项：取消勾选后这只股票必须被排除
        val directionVariants = listOf(
            base.copy(up = true),      // 涨家勾上 → 仍然显示（方向通过）
            base.copy(down = true),    // 只勾跌家 → 这只涨的股票被排除
            base.copy(zx = true),      // 只勾自选 → 这只非自选的被排除
        )
        // 「只勾跌家」与「只勾自选」必须改变结果
        assertFalse(result(directionVariants[1]), "只勾跌家时这只涨的股票应被排除")
        assertFalse(result(directionVariants[2]), "只勾自选时这只非自选的应被排除")

        // 类型五项：拿一只**命中该类型**的股票来验，取消勾选它就必须被排除。
        // （用不命中该类型的样本验是错的 —— 取消勾选一个它本来就不属于的类型，
        //   本来就不该改变结果。）
        for (row in FilterRow.TYPE_ROWS) {
            val bit = StockTypes.bitOf(row)
            val v = base.set(row, false)
            assertFalse(
                v.keeps(inWatchlist = false, percent = 2.0, types = bit),
                "取消勾选 ${row.label} 后命中它的股票应被排除 —— 这一项没起作用",
            )
        }
    }

    @Test
    fun `set 与 get 互为逆操作`() {
        var f = StockFilters.OFF
        for (row in FilterRow.entries) {
            val before = f[row]
            f = f.set(row, !before)
            assertEquals(!before, f[row], "$row 的 set/get 不匹配")
            f = f.set(row, before)
            assertEquals(before, f[row], "$row 的 set/get 不匹配")
        }
    }

    @Test
    fun `类型行的下标与 TYPE_ROWS 的顺序一致`() {
        FilterRow.TYPE_ROWS.forEachIndexed { i, row ->
            assertEquals(i, row.typeIndex, "$row 的 typeIndex 与 TYPE_ROWS 顺序不一致")
        }
    }
}
