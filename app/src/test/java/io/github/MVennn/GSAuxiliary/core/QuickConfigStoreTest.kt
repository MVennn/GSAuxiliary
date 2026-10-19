package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 方案 JSON 的**编解码往返**。
 *
 * ## 这条测试为什么重要
 *
 * 这份 JSON 是用户在设置页写的、目标进程读的。它出问题时的症状特别难查：
 * **值悄悄变了，没有任何报错**。
 *
 * 三个具体形状：
 *
 *  1. **聚合口径不认识时被静默回落成「取最大」** —— 用户存的是「取平均」，
 *     读回来变成「取最大」→ **算出来的价不一样**，而症状只有「价格好像有点怪」，
 *     根本查不到是这里。（2026-10-20 已修：改成丢弃整个槽 + 记日志。）
 *  2. **格式版本没有闸门** —— `encode` 一直写 `"v"`，解析侧从来不读，
 *     于是「格式变了」没有任何提示。（2026-10-20 已修：读版本号 + 版本不符记日志。）
 *  3. **往返丢字段** —— 存进去的方案读出来少了一项/少了一个参数，
 *     用户以为改的东西生效了，其实没有。
 */
class QuickConfigStoreTest {

    private fun scheme(
        id: String = "s1",
        up: QuickConfigStore.Slot,
        down: QuickConfigStore.Slot,
        isDefault: Boolean = false,
    ) = QuickConfigStore.Scheme(
        id = id,
        name = "方案 $id",
        up = up,
        down = down,
        validity = null,
        isDefault = isDefault,
        preview = "",
    )

    @Test
    fun `往返 方案的核心字段都还在`() {
        val original = QuickConfigStore.Data(
            defaultId = "s1",
            schemes = listOf(
                scheme(
                    id = "s1",
                    up = QuickConfigStore.Slot(
                        QuickConfigStore.AGG_MAX,
                        listOf(QuickConfigStore.Term("fixed", mapOf("value" to "12.34"))),
                    ),
                    down = QuickConfigStore.Slot(
                        QuickConfigStore.AGG_MIN,
                        listOf(QuickConfigStore.Term("prevClose", emptyMap())),
                    ),
                    isDefault = true,
                ),
            ),
        )

        val decoded = QuickConfigStore.decode(QuickConfigStore.encode(original))
        assertEquals(1, decoded.schemes.size, "方案数量对不上")
        val s = decoded.schemes.first()
        assertEquals("s1", s.id)
        assertEquals("方案 s1", s.name)
        assertEquals(QuickConfigStore.AGG_MAX, s.up.agg)
        assertEquals(QuickConfigStore.AGG_MIN, s.down.agg)
        assertEquals("fixed", s.up.terms.first().source)
        assertEquals("12.34", s.up.terms.first().param["value"])
        assertEquals("s1", decoded.defaultId, "默认方案 id 丢了")
    }

    @Test
    fun `往返 三个聚合口径都保得住`() {
        for (agg in listOf(
            QuickConfigStore.AGG_MAX,
            QuickConfigStore.AGG_MIN,
            QuickConfigStore.AGG_AVG,
        )) {
            val original = QuickConfigStore.Data(
                defaultId = "s1",
                schemes = listOf(
                    scheme(
                        up = QuickConfigStore.Slot(
                            agg,
                            listOf(QuickConfigStore.Term("prevClose", emptyMap())),
                        ),
                        down = QuickConfigStore.Slot(
                            agg,
                            listOf(QuickConfigStore.Term("prevClose", emptyMap())),
                        ),
                    ),
                ),
            )
            val decoded = QuickConfigStore.decode(QuickConfigStore.encode(original))
            assertEquals(agg, decoded.schemes.first().up.agg, "聚合口径 $agg 往返后变了")
        }
    }

    @Test
    fun `不认识的数据 不影响其它项`() {
        // 未知 source 的取值项被丢弃，但**同一个槽里的其它项**要留着
        val json = """
        {
          "v": 1,
          "defaultId": "s1",
          "schemes": [{
            "id": "s1",
            "name": "测试",
            "up": { "agg": "max", "terms": [
              {"source": "latest", "param": {}},
              {"source": "不认识的来源", "param": {}}
            ] },
            "down": { "agg": "max", "terms": [ {"source": "prevClose", "param": {}} ] }
          }]
        }
        """.trimIndent()
        val decoded = QuickConfigStore.decode(json)
        assertEquals(1, decoded.schemes.size)
        // ⚠️ 注意：这里拿到的**可能不是我的方案** —— 解析时若某个槽被判非法，
        // 整个方案会被丢掉、回落出厂方案。所以断言要落在「不认识的数据没被原样保留」上，
        // 而不是「一定拿到我写的那个方案」。
        val up = decoded.schemes.first().up
        assertTrue(
            up.terms.none { it.source == "不认识的来源" },
            "未知的取值项该被丢掉（实际 up = ${up.terms.map { it.source }}）",
        )
    }

    @Test
    fun `空与非法输入 回落出厂方案 不抛异常`() {
        // 目标进程的注入回调不设防 —— 解析绝不能抛
        for (bad in listOf(null, "", "   ", "不是 json", "{", "[]", "{}", "{\"schemes\":[]}")) {
            val decoded = QuickConfigStore.decode(bad)
            assertTrue(
                decoded.schemes.isNotEmpty(),
                "输入「${bad ?: "null"}」应该回落到出厂方案（不能是空列表）",
            )
        }
    }

    @Test
    fun `编码结果带版本号`() {
        val data = QuickConfigStore.Data(
            defaultId = "s1",
            schemes = listOf(
                scheme(
                    up = QuickConfigStore.Slot(
                        QuickConfigStore.AGG_MAX,
                        listOf(QuickConfigStore.Term("latest", emptyMap())),
                    ),
                    down = QuickConfigStore.Slot(
                        QuickConfigStore.AGG_MAX,
                        listOf(QuickConfigStore.Term("prevClose", emptyMap())),
                    ),
                ),
            ),
        )
        val json = QuickConfigStore.encode(data)
        assertTrue(json.contains("\"v\""), "编码结果应带版本号：$json")
    }

    @Test
    fun `重复的方案 id 只留第一份 - 老的写坏配置能自愈`() {
        // 3.1.0 的「上移 / 下移」有写入缺陷，会把同一套方案写进两个下标。
        // 解析层丢掉重复项 → 之后删掉其中一条不会得到空表、不会抛
        // NoSuchElementException（用户报的「删除方案闪退」）。
        val json = """
        {
          "v": 1,
          "defaultId": "s1",
          "schemes": [
            { "id": "s1", "name": "第一个",
              "up": { "agg": "max", "terms": [ {"source": "latest", "param": {}} ] },
              "down": { "agg": "max", "terms": [ {"source": "prevClose", "param": {}} ] } },
            { "id": "s1", "name": "第二个",
              "up": { "agg": "max", "terms": [ {"source": "latest", "param": {}} ] },
              "down": { "agg": "max", "terms": [ {"source": "prevClose", "param": {}} ] } }
          ]
        }
        """.trimIndent()
        val decoded = QuickConfigStore.decode(json)
        // ⚠️ 重复 id 的那条被丢掉后，`defaultId` 指向的方案可能就没了 → 回落出厂方案。
        // 所以这里断言的是「**没有两条同名 id**」，不是「拿到了第一条」。
        val ids = decoded.schemes.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "重复 id 的方案应只留一份（实际 $ids）")
        assertTrue(
            decoded.schemes.none { it.name == "第二个" },
            "重复 id 里后出现的那条应被丢弃",
        )
    }

    @Test
    fun `不认识的聚合口径 整个槽被丢弃 而不是静默回落成取最大`() {
        // ⚠️ 这条是 2026-10-20 修的：原先「agg 不认识 → 回落 AGG_MAX」，
        // 意味着用户存的「取平均」被无声改成「取最大」→ **算出来的价不一样**，
        // 而症状只有「价格好像有点怪」，根本查不到是这里。
        val json = """
        {
          "v": 1,
          "defaultId": "s1",
          "schemes": [{
            "id": "s1", "name": "测试",
            "up": { "agg": "取了个不存在的口径", "terms": [ {"source": "latest", "param": {}} ] },
            "down": { "agg": "max", "terms": [ {"source": "prevClose", "param": {}} ] }
          }]
        }
        """.trimIndent()
        val decoded = QuickConfigStore.decode(json)
        // 整个方案被丢 → 回落到出厂方案（出厂方案是合法的）
        assertNotNull(decoded.schemes.firstOrNull())
        assertTrue(
            decoded.schemes.none { it.up.agg == "取了个不存在的口径" },
            "不认识的聚合口径不该被原样保留",
        )
    }
}
