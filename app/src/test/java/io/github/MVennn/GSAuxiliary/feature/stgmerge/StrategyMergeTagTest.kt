package io.github.MVennn.GSAuxiliary.feature.stgmerge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聚合入口卡片标签的两段纯逻辑。
 *
 * ## 为什么单独测
 *
 * 真机症状（2026-11-11 截图）：「聚合 · 创新高20日（4 条）」那几张卡下面显示的还是各自的原生
 * 两个 chip，用户读到的意思是「这组只筛了流通市值50-70亿」，跟实际聚合出来的股票对不上。
 * 标签是纯字符串变换，逻辑错在手机上很难看出来 —— 只有把「成员标签 → 合并结果」这层钉死，
 * 才能保证组里三条区间条件真的并成一条。
 *
 * 两个函数分工：
 *  - [mergeTagNames]：算这一组**要显示什么**（区间取并集、离散去重保序）；
 *  - [mergeTagsJson]：把结果写回卡片的 `tags` JSON，同名词条原样复制（保住 tagId，
 *    点标签跳转才不会坏），只有前缀相同的区间词条才借用并改 labelName。
 */
class StrategyMergeTagTest {

    // ---- mergeTagNames ----

    @Test
    fun `区间标签按同前缀同后缀取并集`() {
        // 三档首尾相接（70 接 70、100 接 100）才算一段；中间留空档不并（见下面那条）
        val got = mergeTagNames(
            listOf("流通市值50-70亿", "流通市值70-100亿", "流通市值100-1000亿"),
        )
        assertEquals(listOf("流通市值50-1000亿"), got)
    }

    @Test
    fun `同维度相邻的开区间和闭区间接成一条`() {
        // 真机聚合组2 的两个量比条件：1-4 与 4.0以上，并集就是「1 以上」
        assertEquals(listOf("量比1以上"), mergeTagNames(listOf("量比1-4", "量比4.0以上")))
        assertEquals(
            listOf("流通市值50亿及以上"),
            mergeTagNames(listOf("流通市值50-500亿", "流通市值500亿及以上")),
        )
    }

    @Test
    fun `不相接的同维度区间保持两条`() {
        // 中间空着的那段没选，写成一条包络会把没选的范围也写进去
        assertEquals(
            listOf("量比1-2", "量比3-4"),
            mergeTagNames(listOf("量比1-2", "量比3-4")),
        )
    }

    @Test
    fun `同前缀不同量纲的区间不互相合并`() {
        // 金额族内部跨单位会并（见下面「金额」那两条），但 % 与 元 是两个量纲，不能并成一段
        assertEquals(
            listOf("股价3元以下", "股价3%以下"),
            mergeTagNames(listOf("股价3元以下", "股价3%以下")),
        )
    }

    @Test
    fun `单侧下界写法原样保留`() {
        assertEquals(listOf("股价3元以下"), mergeTagNames(listOf("股价3元以下")))
        // 上下两段开区间不相接，各留一条，不会并成整轴
        assertEquals(
            listOf("量比0.5以下", "量比4.0以上"),
            mergeTagNames(listOf("量比0.5以下", "量比4.0以上")),
        )
    }

    @Test
    fun `不同后缀的区间各算一条`() {
        val got = mergeTagNames(listOf("量比1.5-2.0", "流通市值50-70亿", "量比2.0-2.5"))
        assertEquals(listOf("量比1.5-2.5", "流通市值50-70亿"), got)
    }

    @Test
    fun `离散标签去重后按首次出现顺序保留`() {
        val got = mergeTagNames(
            listOf("创新高20交易日", "创新高20交易日", "涨停", "创新高20交易日", "ST"),
        )
        assertEquals(listOf("创新高20交易日", "涨停", "ST"), got)
    }

    @Test
    fun `区间与离散混排时区间占首次出现的位置`() {
        val got = mergeTagNames(
            listOf("创新高20交易日", "流通市值50-70亿", "涨停", "流通市值70-500亿"),
        )
        assertEquals(listOf("创新高20交易日", "流通市值50-500亿", "涨停"), got)
    }

    @Test
    fun `区间端点保留用户看到的原文写法`() {
        // 合成后的两个数字直接取两端标签的原文，不靠 Double 反推字符串：
        // 宿主的档位写成 1.50 就还写 1.50，写成 4.0 就还写 4.0
        assertEquals(
            listOf("量比1.50-2.50"),
            mergeTagNames(listOf("量比1.50-2.00", "量比2.00-2.50")),
        )
    }

    @Test
    fun `空列表与空白串不产出标签`() {
        assertTrue(mergeTagNames(emptyList()).isEmpty())
        assertTrue(mergeTagNames(listOf("", "   ")).isEmpty())
    }

    // ---- takeTagPrefix ----

    @Test
    fun `前缀只对区间型标签有意义`() {
        assertEquals("流通市值", takeTagPrefix("流通市值50-70亿"))
        assertEquals("量比", takeTagPrefix("量比1.5-2.0"))
        assertEquals("", takeTagPrefix("创新高20交易日"))
        assertEquals("", takeTagPrefix("   "))
    }

    // ---- 入口卡名字 ----

    @Test
    fun `入口名是前缀加组名且不带条数`() {
        // 2026-11-12 用户要求：卡名后面的「（N 条）」去掉，条数是实现细节、过期就难看
        assertEquals("聚合 · 创新高20日", entryLabelOf("创新高20日", 4))
        assertEquals("聚合 · 组名", entryLabelOf("组名", 1))
        assertEquals(entryLabelBare("创新高20日"), entryLabelOf("创新高20日", 4))
    }

    @Test
    fun `组名自带聚合前缀时不再叠加`() {
        assertEquals("聚合测试", entryLabelOf("聚合测试", 2))
    }

    @Test
    fun `组名空时回落到聚合策略`() {
        // 回落名「聚合策略」自己就以「聚合」开头，所以不再叠「聚合 · 」
        assertEquals("聚合策略", entryLabelOf("  ", 3))
    }

    @Test
    fun `入口名能还原成组名`() {
        val got = entryLabelGroupNames("聚合 · 创新高20日（4 条）")
        assertTrue(got.contains("创新高20日"), "候选里必须有裸组名：$got")
        assertEquals("创新高20日", got.last())
    }

    @Test
    fun `普通策略名不会被当成入口名`() {
        assertTrue(entryLabelGroupNames("50e").isEmpty())
        assertTrue(entryLabelGroupNames("创新高20日").isEmpty())
    }

    @Test
    fun `详情页那种不带条数的入口名也能还原组名`() {
        // 详情页头部写「聚合 · 组名」，宿主会把这个名字带回列表卡片，点进去还得认出是同一组
        val got = entryLabelGroupNames(entryLabelBare("创新高20日"))
        assertEquals(listOf("聚合 · 创新高20日", "创新高20日"), got)
    }

    @Test
    fun `入口名与裸名一致且旧带条数写法仍能认出同一组`() {
        assertEquals(entryLabelBare("创新高20日"), "聚合 · 创新高20日")
        assertEquals(entryLabelOf("创新高20日", 4), entryLabelBare("创新高20日"))
        // 升级前写进宿主的旧卡名带条数，巡检仍要认得出是同一组，否则会再叠一张卡
        assertTrue(entryLabelGroupNames("聚合 · 创新高20日（4 条）").contains("创新高20日"))
    }

    // ---- mergeTagsJson ----

    private fun tag(labelName: String, tagId: String) =
        JSONObject().put("labelName", labelName).put("tagId", tagId).put("isValid", true)

    @Test
    fun `同名词条原样复制保住 tagId`() {
        val raw = JSONArray().put(tag("创新高20交易日", "t1")).toString()
        val out = JSONArray(mergeTagsJson(listOf(raw), listOf("创新高20交易日"))!!)
        assertEquals(1, out.length())
        assertEquals("t1", out.getJSONObject(0).getString("tagId"))
    }

    @Test
    fun `区间并集借同前缀词条复制并改成合并后的文本`() {
        val raw = JSONArray()
            .put(tag("流通市值50-70亿", "t2"))
            .put(tag("创新高20交易日", "t1"))
            .toString()
        val want = listOf("流通市值50-1000亿", "创新高20交易日")
        val out = JSONArray(mergeTagsJson(listOf(raw), want)!!)
        assertEquals(want.size, out.length())
        assertEquals("流通市值50-1000亿", out.getJSONObject(0).getString("labelName"))
        // tagId 借用原区间词条，宿主的点击逻辑仍指向「流通市值」这个筛选维度
        assertEquals("t2", out.getJSONObject(0).getString("tagId"))
        assertEquals("t1", out.getJSONObject(1).getString("tagId"))
    }

    @Test
    fun `档位表里查不到的词条连空数组都不写`() {
        // 「涨停」这种离散词在宿主档位表（1119 条）里确实没有，合成出来的词条拿不到 labelId，
        // 写上去宿主会画成「无效标签」（真机卡面挂感叹号），所以宁可不写。
        // 一条都合不出来时必须返回 null（调用方按「保持原样」处理）：返回空数组会把借来的
        // 原生卡上真实的标签整行清空，而且下一轮 written == cur 也不会再补回来。
        // 注意：单测里 StrategyLabelTable 不是空表，区间/档位类词条是能反查到 labelId 的。
        val raw = JSONArray().put(tag("创新高20交易日", "t1")).toString()
        assertNull(mergeTagsJson(listOf(raw), listOf("涨停")))
    }

    @Test
    fun `自建入口卡的空数组标签仍可合成`() {
        // 页面上一张可借的策略卡都没有时，入口卡是 new 出来的宿主 bean，tags 就是 `[]`。
        // 旧实现在「原 JSON 一个元素都解析不出来」时返回 null，这种卡就永远写不出标签。
        // 这里用一个能反查到档位的词：全部查不到的情况见上一条（返回 null，保持原样）。
        assertNotNull(mergeTagsJson(listOf("[]"), listOf("量比1以上")))
    }

    @Test
    fun `多个来源 JSON 合并成一个词条池`() {
        val a = JSONArray().put(tag("涨停", "t3")).toString()
        val b = JSONArray().put(tag("流通市值50-70亿", "t2")).toString()
        val out = JSONArray(mergeTagsJson(listOf(a, b), listOf("涨停", "流通市值50-500亿"))!!)
        assertEquals(2, out.length())
        assertEquals("t3", out.getJSONObject(0).getString("tagId"))
        assertEquals("t2", out.getJSONObject(1).getString("tagId"))
    }

    @Test
    fun `跨档合成文本按区间反查档位补 labelId`() {
        // 用户看到的是详情页四条条件、列表卡只有两条：「量比1-4」「流通市值50-500亿」
        // 是 mergeTagNames 把多档并出来的合成文本，档位表里没有原样的一条，
        // 以前被当成「查不到」直接跳过（卡片就少两条）。
        val eRatio = StrategyLabelTable.entryForDisplay("量比1-4")
        val eMarket = StrategyLabelTable.entryForDisplay("流通市值50-500亿")
        assertNotNull(eRatio, "「量比1-4」应能反查到相交的第一档")
        assertNotNull(eMarket, "「流通市值50-500亿」应能反查到相交的第一档")
        assertEquals("量比1.0-1.5", eRatio.display)
        assertEquals("流通市值50-70亿", eMarket.display)

        val out = JSONArray(mergeTagsJson(listOf("[]"), listOf("量比1-4", "流通市值50-500亿"))!!)
        assertEquals(2, out.length())
        val a = out.getJSONObject(0)
        assertEquals("量比1-4", a.getString("labelName"), "chip 上仍写合成后的跨档文本")
        assertEquals(eRatio.labelId, a.getString("labelId"))
        assertEquals("1", a.getString("isValid"))
        assertEquals(eMarket.labelId, out.getJSONObject(1).getString("labelId"))
    }

    @Test
    fun `单侧合成文本也能反查到相交档位`() {
        // mergeTagNames 把相邻区间接上之后，合成出来的是「量比1以上」这种只有一个数字的写法
        val eRatio = StrategyLabelTable.entryForDisplay("量比1以上")
        val eMarket = StrategyLabelTable.entryForDisplay("流通市值50亿及以上")
        assertNotNull(eRatio, "「量比1以上」应按 [1, +∞) 反查")
        assertNotNull(eMarket, "「流通市值50亿及以上」应按 [50, +∞) 反查")
        assertEquals("量比", eRatio.parentName)
        assertEquals("流通市值", eMarket.parentName)
        assertEquals("量比1.0-1.5", eRatio.display)
        assertEquals("流通市值50-70亿", eMarket.display)

        val out = JSONArray(mergeTagsJson(listOf("[]"), listOf("量比1以上", "流通市值50亿及以上"))!!)
        assertEquals(2, out.length())
        assertEquals("量比1以上", out.getJSONObject(0).getString("labelName"))
        assertEquals("1", out.getJSONObject(0).getString("isValid"))
        assertEquals(eMarket.labelId, out.getJSONObject(1).getString("labelId"))
    }

    @Test
    fun `没有目标标签或原 JSON 解析不出来时返回 null`() {
        val raw = JSONArray().put(tag("涨停", "t3")).toString()
        assertNull(mergeTagsJson(listOf(raw), emptyList()))
        assertNull(mergeTagsJson(listOf(""), listOf("涨停")))
        assertNull(mergeTagsJson(listOf("not json"), listOf("涨停")))
    }

    // ---- 档位写法白名单：各维度原有的写法都要能识别 ----

    @Test
    fun `涨跌幅的全角破折号档位也能接成一条`() {
        // 宿主涨跌幅档位用的是全角破折号：「-7%—-5%」「-5%—-3%」「-3%—0」
        assertEquals(
            listOf("-7%—0%"),
            mergeTagNames(listOf("-7%—-5%", "-5%—-3%", "-3%—0")),
        )
    }

    @Test
    fun `倍为单位的市盈率档位也能合并`() {
        assertEquals(
            listOf("市盈率5倍及以下"),
            mergeTagNames(listOf("市盈率0倍及以下", "市盈率5倍及以下")),
        )
        assertEquals(
            listOf("市盈率50倍以上"),
            mergeTagNames(listOf("市盈率50倍以上", "市盈率100倍以上")),
        )
    }

    @Test
    fun `金额跨单位按同一根轴合并`() {
        // 主力资金净流入的档位单位一路在变（百万元 / 千万元 / 亿元），但都是金额，落在同一根轴上
        assertEquals(
            listOf("主力资金净流入5百万元-1亿元"),
            mergeTagNames(
                listOf(
                    "主力资金净流入5百万元-1千万元",
                    "主力资金净流入1千万元-1亿元",
                ),
            ),
        )
    }

    @Test
    fun `金额跨单位相接后并成单侧`() {
        assertEquals(
            listOf("主力资金净流入5百万元及以上"),
            mergeTagNames(
                listOf("主力资金净流入5百万元-1亿元", "主力资金净流入1亿元及以上"),
            ),
        )
    }

    @Test
    fun `中文量级单位能识别且与亿同轴`() {
        assertEquals(listOf("总市值1千亿及以上"), mergeTagNames(listOf("总市值1千亿及以上")))
        assertEquals(
            listOf("总市值500亿及以上"),
            mergeTagNames(listOf("总市值1千亿及以上", "总市值500亿及以上")),
        )
    }

    @Test
    fun `比较词写法原样保留`() {
        assertEquals(listOf("大于1亿元"), mergeTagNames(listOf("大于1亿元")))
        // prefix 里带数字的「近N交易日」是不同时间窗口，不能被并成一条
        assertEquals(
            listOf("近10交易日>=10%", "近20交易日>=10%"),
            mergeTagNames(listOf("近10交易日>=10%", "近20交易日>=10%")),
        )
    }

    @Test
    fun `时间单位档位也能合并`() {
        assertEquals(
            listOf("最近连涨3天及以上"),
            mergeTagNames(listOf("最近连涨3天及以上", "最近连涨5天及以上")),
        )
    }

    /**
     * 拿真实档位表跑一遍全部多档维度：合成结果必须条条反查得回 labelId。
     *
     * 卡面 chip 的 labelId 是反查表体补的，查不回来宿主就当无效标签，渲染成带感叹号的 ⓘ
     * （2026-11-12 真机症状）。这条断言把「整张表 × 合成逻辑」的组合钉死，新增档位写法
     * 一旦漏解析，这里第一个红。
     */
    @Test
    fun `真实档位表每个维度的合成结果都能反查到档位`() {
        val byParent = LinkedHashMap<String, MutableList<String>>()
        for (e in StrategyLabelTable.entries) {
            if (e.parentName.isEmpty()) continue
            byParent.getOrPut(e.parentName) { ArrayList() }.add(e.display)
        }
        var multi = 0
        var shrunk = 0
        val bad = ArrayList<String>()
        for ((p, names) in byParent) {
            if (names.size < 2) continue
            multi++
            val out = mergeTagNames(names)
            if (out.size < names.size) shrunk++
            for (o in out) if (StrategyLabelTable.entryForDisplay(o) == null) bad.add(p + " => " + o)
        }
        assertTrue(bad.isEmpty(), "反查不到档位：" + bad)
        assertTrue(multi >= 60, "多档维度只有 $multi 个，档位表没加载全？")
        assertTrue(shrunk * 2 >= multi, "能收缩的维度 $shrunk / $multi，写法白名单退化了")
    }

    @Test
    fun `跨档相接后不再沿用比较词`() {
        // 4 条接不成一条（5百万-1千万 与 3千万-6千万 之间空着），但合成出的开放段起点
        // 是 3千万元、不是原比较词写的 1亿元：再写「大于」会把正好等于 3千万的股票漏掉
        assertEquals(
            listOf("主力资金净流入5百万元-1千万元", "主力资金净流入3千万元以上"),
            mergeTagNames(
                listOf(
                    "主力资金净流入5百万元-1千万元",
                    "主力资金净流入3千万元-6千万元",
                    "主力资金净流入6千万元-1亿元",
                    "主力资金净流入大于1亿元",
                ),
            ),
        )
        // 只有比较词自己一段时，写法原样保留
        assertEquals(
            listOf("主力资金净流入大于1亿元"),
            mergeTagNames(listOf("主力资金净流入大于1亿元")),
        )
    }

    @Test
    fun `口语量级写法解析不了就原样保留`() {
        // 「1千5百万」里的 5 会挡住数字段的解析，宁可变回两条离散标签，也不能把 1 当数值去并
        val raw = listOf("主力资金净流入1千5百万元-3千万元", "主力资金净流入3千万元-6千万元")
        assertEquals(raw, mergeTagNames(raw))
    }
}
