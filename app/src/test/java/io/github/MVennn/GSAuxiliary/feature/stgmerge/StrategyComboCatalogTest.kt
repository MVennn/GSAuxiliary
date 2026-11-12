package io.github.MVennn.GSAuxiliary.feature.stgmerge

import io.github.MVennn.GSAuxiliary.core.MergeMember
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 聚合组「到底几条」的**唯一口径**：设置页「N 个成员」、列表入口卡「（N 条）」、
 * 详情页头部三处必须是同一个数，也就是口径成员展开后的条数。
 *
 * 2026-11-12 真机症状：列表卡写「聚合 · 中盘 1.5-4（2 条）」，点进去却是 13 个成员、
 * 896 只股票 —— 列表页当时按配置里的原始成员数算（1 个口径 + 1 条真实策略 = 2），
 * 而详情页按展开后算。用户看到「（2 条）」会以为聚合没生效，也解释了为什么
 * 「加了成员条数不动」。这条测试把两边的口径钉在同一个函数上。
 */
class StrategyComboCatalogTest {

    private fun m(name: String, stId: String = "") = MergeMember(stId = stId, name = name)

    @Test
    fun 口径成员按展开后的组合数算() {
        for (b in StrategyComboCatalog.BUCKETS) {
            assertEquals(b.comboCount, StrategyComboCatalog.expandedCount(listOf(m(b.name))))
        }
    }

    @Test
    fun 四个口径的组合数与档位表一致() {
        val byKey = StrategyComboCatalog.BUCKETS.associate { it.key to it.comboCount }
        assertEquals(12, byKey["mid-15-4"], "中盘 1.5-4 = 3 市值档 × 4 量比档")
        assertEquals(3, byKey["mid-4p"], "中盘 4+ = 3 市值档 × 1 量比档")
        assertEquals(4, byKey["big-15-4"], "大盘 1.5-4 = 1 × 4")
        assertEquals(1, byKey["big-4p"], "大盘 4+ = 1 × 1")
    }

    @Test
    fun 口径成员加真实策略就是十三() {
        val members = listOf(m("中盘 1.5-4"), m("量50", stId = "1569199013536731136"))
        assertEquals(13, StrategyComboCatalog.expandedCount(members))
    }

    @Test
    fun 口径成员认ASCII_key() {
        assertEquals(12, StrategyComboCatalog.expandedCount(listOf(m("mid-15-4"))))
    }

    @Test
    fun 填了stId的同名成员不展开() {
        // 用户真建了一条叫「中盘 1.5-4」的策略：那条能直接取数，按普通成员算 1 条
        val members = listOf(m("中盘 1.5-4", stId = "1569199013536731136"))
        assertEquals(1, StrategyComboCatalog.expandedCount(members))
    }

    @Test
    fun 普通成员按名称去重() {
        val members = listOf(m("量50", "111"), m("量50", "222"), m("量70", "333"))
        assertEquals(2, StrategyComboCatalog.expandedCount(members))
    }

    @Test
    fun 名字为空的成员按stId去重() {
        val members = listOf(m("", "111"), m("", "111"), m("", "222"))
        assertEquals(2, StrategyComboCatalog.expandedCount(members))
    }

    @Test
    fun 口径标签反推后能合并成整段区间() {
        val b = StrategyComboCatalog.BUCKETS.first { it.key == "mid-15-4" }
        assertEquals(
            listOf("流通市值50-500亿", "量比1.5-4"),
            mergeTagNames(StrategyComboCatalog.tagNamesOf(b)),
            "口径成员的标签只能按档位表反推；反推不出来，列表卡与详情页的标签条就整个缺掉市值维度",
        )
    }

    @Test
    fun 组合占位key反推两条条件() {
        assertEquals(
            listOf("流通市值50-70亿", "量比1.5-2.0"),
            StrategyComboCatalog.tagNamesOfKey("combo:mid-15-4|50-70亿|1.5-2.0"),
        )
        assertEquals(emptyList(), StrategyComboCatalog.tagNamesOfKey("not-a-combo"))
    }

    @Test
    fun 口径判据只认空stId() {
        assertEquals("mid-15-4", StrategyComboCatalog.bucketOfMember("中盘 1.5-4", "")?.key)
        assertEquals(null, StrategyComboCatalog.bucketOfMember("中盘 1.5-4", "1569199013536731136"))
        assertEquals(null, StrategyComboCatalog.bucketOfMember("量50", ""))
    }

    @Test
    fun 大盘口径可叠加两组() {
        val members = listOf(m("大盘 1.5-4"), m("大盘 4+"), m("量500", stId = "1569199013536731199"))
        assertEquals(6, StrategyComboCatalog.expandedCount(members))
    }

    @Test
    fun 口径说明写成首尾区间且与合并结果一致() {
        val mid = StrategyComboCatalog.BUCKETS.first { it.key == "mid-15-4" }
        assertEquals(
            "流通市值50-500亿 · 量比1.5-4",
            StrategyComboCatalog.summaryText(mid),
            "设置页副标题与添加成员面板都读这一条：跨档要写成首尾区间，否则用户看不出这一行到底选了哪些条件",
        )
        // 与目标进程合并标签的写法必须逐字一致，否则同一个口径在标签条上写 1.5-4、在设置页写 1.5-4.0
        assertEquals(mergeTagNames(StrategyComboCatalog.tagNamesOf(mid)), StrategyComboCatalog.summaryText(mid).split(" · "))
    }

    @Test
    fun 单档口径原样输出不加连字符() {
        val big = StrategyComboCatalog.BUCKETS.first { it.key == "big-4p" }
        assertEquals("流通市值500亿及以上 · 量比4.0以上", StrategyComboCatalog.summaryText(big))
    }

    // ---------------- 自选条件（cond:）----------------

    private fun cond(markets: List<String>, ratios: List<String>) = StrategyComboCatalog.Condition(
        markets.mapNotNull { StrategyComboCatalog.marketTier(it) },
        ratios.mapNotNull { StrategyComboCatalog.ratioTier(it) },
    )

    private fun condKey(markets: List<String>, ratios: List<String>): String {
        val c = cond(markets, ratios)
        return StrategyComboCatalog.condKey(c.markets, c.ratios)
    }

    @Test
    fun 条件成员的条数是两个维度的乘积() {
        val c = cond(listOf("50-70亿", "70-100亿"), listOf("1.5-2.0", "2.0-2.5", "2.5-3.0"))
        assertEquals(6, c.comboCount)
        assertEquals(
            6,
            StrategyComboCatalog.expandedCount(listOf(m(StrategyComboCatalog.condKey(c.markets, c.ratios)))),
            "设置页「N 个成员」与列表卡「（N 条）」都读 expandedCount，条件成员必须按展开后算",
        )
    }

    @Test
    fun 条件成员编码能原样还原() {
        val key = condKey(listOf("50-70亿", "70-100亿"), listOf("1.5-2.0"))
        // 新写法只写 labelId（顺序：市值档、量比档），与宿主的 labelId 一一对应
        assertEquals("cond:332921024326664192,332921124713136128,1363924975269969920", key)
        val back = StrategyComboCatalog.conditionOfKey(key)
        assertEquals(listOf("50-70亿", "70-100亿"), back?.markets?.map { it.name })
        assertEquals(listOf("1.5-2.0"), back?.ratios?.map { it.name })
    }

    @Test
    fun 条件判据只认空stId() {
        val key = condKey(listOf("50-70亿"), listOf("1.5-2.0"))
        assertEquals(key, StrategyComboCatalog.conditionOfMember(key, "")?.let {
            StrategyComboCatalog.condKey(it.markets, it.ratios)
        })
        // 填了 stId 就当普通策略，不展开（用户可能真建了一条叫这个名字的策略）
        assertEquals(null, StrategyComboCatalog.conditionOfMember(key, "1569199013536731136"))
        assertEquals(null, StrategyComboCatalog.conditionOfMember("量50", ""))
    }

    @Test
    fun 条件展开的每个组合都能还原成取数请求() {
        val c = cond(listOf("50-70亿", "100-500亿"), listOf("1.5-2.0", "4.0以上"))
        val combos = StrategyComboCatalog.combosOf(c)
        assertEquals(4, combos.size)
        for (combo in combos) {
            assertEquals(2, combo.key.split(',').size, "条件组合的 key 是两段 labelId：市值,量比")
            val back = StrategyComboCatalog.comboOfKey(combo.key)
            assertEquals(combo.labelIds, back?.labelIds, "两段 key 必须能还原出同样的 labelId 才能真取数")
        }
    }

    @Test
    fun 条件标签反推后能合并成整段区间() {
        val c = cond(listOf("50-70亿", "70-100亿"), listOf("1.5-2.0", "2.0-2.5", "2.5-3.0"))
        // 合并后统一裁掉「.0」（与宿主原生写法一致：1.5-3，不写 1.5-3.0）
        assertEquals(
            listOf("流通市值50-100亿", "量比1.5-3"),
            mergeTagNames(StrategyComboCatalog.tagNamesOf(c)),
        )
        assertEquals("流通市值50-100亿 · 量比1.5-3", StrategyComboCatalog.summaryText(c))
    }

    @Test
    fun 条件组合的占位key能反推两条条件() {
        assertEquals(
            listOf("流通市值50-70亿", "量比1.5-2.0"),
            StrategyComboCatalog.tagNamesOfKey("combo:50-70亿|1.5-2.0"),
        )
        assertEquals("50-70亿 × 1.5-2.0", StrategyComboCatalog.displayName("50-70亿|1.5-2.0"))
        // 档位表里没有的写法不许瞎认，否则脏配置会让详情页多出一条不存在的标签
        assertEquals(emptyList(), StrategyComboCatalog.tagNamesOfKey("combo:999亿|9.9"))
    }

    @Test
    fun 标签成员的标签按档位表反推() {
        // label:1363924975269969920 = 量比1.5-2.0；label:332921024326664192 = 流通市值50-70亿
        assertEquals(
            listOf("量比1.5-2.0"),
            StrategyComboCatalog.tagNamesOfKey("label:1363924975269969920"),
        )
        assertEquals(
            listOf("流通市值50-70亿"),
            StrategyComboCatalog.tagNamesOfKey("label:332921024326664192"),
        )
    }

    @Test
    fun 队列里的标签成员也认得出() {
        // 聚合队列把成员 key 统一包成 combo: 前缀，裸 label: 认不出就会整条丢标签
        val key = StrategyComboCatalog.COMBO_PREFIX + "label:1363924975269969920"
        assertEquals("1363924975269969920", StrategyComboCatalog.labelIdOf(key))
        assertEquals(listOf("量比1.5-2.0"), StrategyComboCatalog.tagNamesOfKey(key))
        assertEquals("量比1.5-2.0", StrategyComboCatalog.displayName(key))
        assertEquals("1363924975269969920", StrategyComboCatalog.comboOfKey(key)?.labelIds?.first())
    }

    @Test
    fun 标签成员按一条算() {
        assertEquals(1, StrategyComboCatalog.expandedCount(listOf(m("label:1363924975269969920"))))
        assertEquals(2, StrategyComboCatalog.expandedCount(listOf(
            m("label:1363924975269969920"),
            m("label:332921024326664192"),
        )))
    }

    @Test
    fun 任意两维组合按维展开成笛卡尔积() {
        val marketAll = StrategyComboCatalog.dimOf(StrategyLabelTable.nodeOf("332920492044320768")!!)
        val market = marketAll.copy(
            tiers = marketAll.tiers.filter { it.labelId == "332921024326664192" || it.labelId == "332921124713136128" },
        )
        val newHighAll = StrategyComboCatalog.dimOf(StrategyLabelTable.nodeOf("313635226150502400")!!)
        val newHigh = newHighAll.copy(
            tiers = newHighAll.tiers.filter { it.labelId == "313635411240943616" },
        )
        val c = StrategyComboCatalog.Condition(listOf(market, newHigh))
        assertEquals(2, c.comboCount, "两段市值 × 一档创新高 = 两条组合，与量比无关")
        assertEquals(
            listOf("流通市值50-70亿", "流通市值70-100亿", "创新高20交易日"),
            StrategyComboCatalog.tagNamesOf(c),
        )
        assertEquals("流通市值50-100亿 · 创新高20交易日", StrategyComboCatalog.summaryText(c))
        val combos = StrategyComboCatalog.combosOf(c)
        assertEquals(2, combos.size)
        for (combo in combos) {
            assertEquals(2, combo.key.split(',').size)
            assertEquals(combo.labelIds, StrategyComboCatalog.comboOfKey(combo.key)?.labelIds)
        }
        // 编码 → 还原：两维各自并回去，维度顺序不变
        val back = StrategyComboCatalog.conditionOfKey(StrategyComboCatalog.condKey(c))!!
        assertEquals(listOf("流通市值", "创新高"), back.dims.map { it.title })
        assertEquals(2, back.comboCount)
    }

    @Test
    fun 三维组合的条数是各维档数连乘() {
        val marketAll = StrategyComboCatalog.dimOf(StrategyLabelTable.nodeOf("332920492044320768")!!)
        val ratioAll = StrategyComboCatalog.dimOf(StrategyLabelTable.nodeOf("1363923810041987072")!!)
        val newHighAll = StrategyComboCatalog.dimOf(StrategyLabelTable.nodeOf("313635226150502400")!!)
        val c = StrategyComboCatalog.Condition(
            listOf(
                marketAll.copy(tiers = marketAll.tiers.take(2)),
                ratioAll.copy(tiers = ratioAll.tiers.take(3)),
                newHighAll.copy(tiers = newHighAll.tiers.filter { it.labelId == "313635411240943616" }),
            ),
        )
        assertEquals(6, c.comboCount)
        assertEquals(6, StrategyComboCatalog.combosOf(c).size)
        // 加一维以后，老的两维读法（markets / ratios）仍然读得到，历史配置不受影响
        assertEquals(2, c.markets.size)
        assertEquals(3, c.ratios.size)
    }
}