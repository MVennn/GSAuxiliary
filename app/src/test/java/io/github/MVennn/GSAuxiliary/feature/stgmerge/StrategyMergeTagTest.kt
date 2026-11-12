package io.github.MVennn.GSAuxiliary.feature.stgmerge

import kotlin.test.Test
import kotlin.test.assertEquals
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
        val got = mergeTagNames(
            listOf("流通市值50-70亿", "流通市值100-500亿", "流通市值500-1000亿"),
        )
        assertEquals(listOf("流通市值50-1000亿"), got)
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
            listOf("创新高20交易日", "流通市值50-70亿", "涨停", "流通市值100-500亿"),
        )
        assertEquals(listOf("创新高20交易日", "流通市值50-500亿", "涨停"), got)
    }

    @Test
    fun `小数区间按最短有效位写回`() {
        val got = mergeTagNames(listOf("量比1.50-2.00", "量比2.00-2.50"))
        assertEquals(listOf("量比1.5-2.5"), got)
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
    fun `找不到任何可借词条时只写 labelName`() {
        val raw = JSONArray().put(tag("创新高20交易日", "t1")).toString()
        val out = JSONArray(mergeTagsJson(listOf(raw), listOf("涨停"))!!)
        assertEquals(1, out.length())
        assertEquals("涨停", out.getJSONObject(0).getString("labelName"))
        assertTrue(!out.getJSONObject(0).has("tagId"))
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
    fun `没有目标标签或原 JSON 解析不出来时返回 null`() {
        val raw = JSONArray().put(tag("涨停", "t3")).toString()
        assertNull(mergeTagsJson(listOf(raw), emptyList()))
        assertNull(mergeTagsJson(listOf(""), listOf("涨停")))
        assertNull(mergeTagsJson(listOf("not json"), listOf("涨停")))
    }
}
