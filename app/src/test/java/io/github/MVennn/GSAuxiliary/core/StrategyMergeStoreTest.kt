package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 聚合组 JSON 的**编解码往返**。
 *
 * ## 这条测试为什么重要
 *
 * 成员只填名称、stId 留空是**正路**（用户看不到宿主内部的 15~19 位编号，名称才是他
 * 天天见的东西；目标进程靠 StrategyStgIndex 按名称补 stId）。2026-11-11 线上症状是
 * 「添加成员点确定后列表没变」：界面把空 stId 成员交给 encode，encode 按「stId 必填」
 * 直接 continue 丢掉，写盘后再 observe 回流，列表自然一条没多 —— **值悄悄没了，
 * 没有任何报错**。这里的往返断言就是防止它再悄悄丢一次。
 *
 * 同时钉住三个同源口径：
 *
 *  1. 组内按**名称**去重（名称空时按 stId）—— 两个只填名称的成员不能互相覆盖；
 *  2. 名称与 stId **都**空才算坏数据、才丢；
 *  3. 同一份配置 encode 两次逐字相同（字段顺序固定，备份 diff 不抖）。
 */
class StrategyMergeStoreTest {

    private fun group(
        id: String = "g1",
        name: String = "市值 50-500e",
        enabled: Boolean = true,
        members: List<MergeMember> = emptyList(),
    ) = MergeGroup(id = id, name = name, enabled = enabled, members = members)

    private fun config(vararg groups: MergeGroup) = MergeConfig(groups.toList())

    @Test
    fun 往返_空stId的成员不会被丢掉() {
        val original = config(
            group(members = listOf(MergeMember(stId = "", name = "流通市值 50-70e"))),
        )

        val json = StrategyMergeStore.encode(original)
        val decoded = StrategyMergeStore.decode(json)

        assertEquals(1, decoded.groups.size, "组丢了")
        val members = decoded.groups.first().members
        assertEquals(1, members.size, "空 stId 成员在往返里被丢了")
        assertEquals("流通市值 50-70e", members.first().name, "成员名被改了")
        assertEquals("", members.first().stId, "空 stId 不该被编成别的东西")
    }

    @Test
    fun 往返_encode的JSON里能看见只填名称的成员() {
        val json = StrategyMergeStore.encode(
            config(group(members = listOf(MergeMember(stId = "", name = "创新高 20 日")))),
        )
        assertTrue(json.contains("创新高 20 日"), "encode 没把名称写进去")
        assertTrue(json.contains("\"stId\":\"\""), "空 stId 字段应照写（字段顺序固定）")
    }

    @Test
    fun 往返_带stId的老成员照旧() {
        val decoded = StrategyMergeStore.decode(
            StrategyMergeStore.encode(
                config(
                    group(
                        members = listOf(
                            MergeMember(stId = "600036", name = "招商银行"),
                            MergeMember(stId = "", name = "创新高 20 日"),
                        ),
                    ),
                ),
            ),
        )
        val members = decoded.groups.first().members
        assertEquals(2, members.size)
        assertEquals("600036", members[0].stId)
        assertEquals("招商银行", members[0].name)
        assertEquals("", members[1].stId)
        assertEquals("创新高 20 日", members[1].name)
    }

    @Test
    fun 组内按名称去重_只留第一条() {
        val decoded = StrategyMergeStore.decode(
            StrategyMergeStore.encode(
                config(
                    group(
                        members = listOf(
                            MergeMember(stId = "", name = "A"),
                            MergeMember(stId = "123456", name = "A"),
                        ),
                    ),
                ),
            ),
        )
        val members = decoded.groups.first().members
        assertEquals(1, members.size, "同名成员没有去重")
        assertEquals("", members.first().stId, "应保留第一次出现的那条")
    }

    @Test
    fun 名称与stId都空的成员才丢() {
        val decoded = StrategyMergeStore.decode(
            StrategyMergeStore.encode(
                config(
                    group(
                        members = listOf(
                            MergeMember(stId = "  ", name = "  "),
                            MergeMember(stId = "  ", name = "B"),
                        ),
                    ),
                ),
            ),
        )
        val members = decoded.groups.first().members
        assertEquals(1, members.size, "全空成员应被丢掉、有名成员应留下")
        assertEquals("B", members.first().name)
    }

    @Test
    fun 名称空时回落stId并按stId去重() {
        val decoded = StrategyMergeStore.decode(
            StrategyMergeStore.encode(
                config(
                    group(
                        members = listOf(
                            MergeMember(stId = "600036", name = ""),
                            MergeMember(stId = "600036", name = "  "),
                        ),
                    ),
                ),
            ),
        )
        val members = decoded.groups.first().members
        assertEquals(1, members.size, "同 stId 成员没有去重")
        assertEquals("600036", members.first().name, "名称空时应回落成 stId")
    }

    @Test
    fun 同一份配置encode两次逐字相同() {
        val c = config(
            group(
                members = listOf(
                    MergeMember(stId = "", name = "创新高 20 日"),
                    MergeMember(stId = "600036", name = "招商银行"),
                ),
            ),
        )
        assertEquals(StrategyMergeStore.encode(c), StrategyMergeStore.encode(c))
    }

    @Test
    fun decode坏JSON回落空配置不抛异常() {
        for (raw in listOf("", "   ", "{", "[]", "{\"v\":2,\"groups\":[]}")) {
            assertEquals(0, StrategyMergeStore.decode(raw).groups.size, "坏数据没有回落空配置")
        }
    }

    @Test
    fun 组id为空被丢_组名空回落聚合组N() {
        val raw = "{\"v\":1,\"groups\":[{\"id\":\"\",\"name\":\"x\",\"members\":[]},{\"id\":\"g2\",\"name\":\"\",\"members\":[]}]}"
        val decoded = StrategyMergeStore.decode(raw)
        assertEquals(1, decoded.groups.size, "空 id 组没被丢")
        assertEquals("g2", decoded.groups.first().id)
        assertEquals("聚合组 1", decoded.groups.first().name, "组名空应回落成序号名")
    }
}
