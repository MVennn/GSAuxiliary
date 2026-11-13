package io.github.MVennn.GSAuxiliary.feature.stgrow

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 跨功能勾选通道的键归一、快照时效与行集合版本号。
 *
 * ## 为什么值得单测
 *
 * 这三样东西是「策略详情页上多个功能同时改勾选」时唯一的共同口径：
 *
 *  - 键必须带市场号 —— 旧口径只取代码，同代码不同市场会互相认领，
 *    搬勾选时把 A 市场的勾搬到 B 市场那只上（用户看到的就是「加错票」）；
 *  - 快照必须会过期 —— 用户随时可能手点改勾选，8 s 前的结论不能当屏幕真值；
 *  - 行集合版本号必须单调 —— 它是「批量点行途中行顺序被换了」的唯一廉价判据，
 *    回绕/倒退会让「全选」接着往错行上点。
 *
 * 纯 JVM 可跑：[StgRowSelection] 刻意不碰 Android 时钟（用 nanoTime）也不碰 View API
 * （owner 只做弱引用、不调方法），所以这一层的行为能在这里钉死。
 */
class StgRowSelectionTest {

    @BeforeTest
    fun reset() {
        StgRowSelection.clear()
    }

    @Test
    fun `键带市场号`() {
        assertEquals("600000@1", StgRowSelection.keyOf("600000", "1"))
        assertEquals("000001@2", StgRowSelection.keyOf("000001", "2"))
    }

    @Test
    fun `没有市场号时退化成纯代码`() {
        assertEquals("600000", StgRowSelection.keyOf("600000", null))
        assertEquals("600000", StgRowSelection.keyOf("600000", ""))
    }

    @Test
    fun `两端空白被去掉`() {
        assertEquals("600000@1", StgRowSelection.keyOf(" 600000 ", " 1 "))
    }

    @Test
    fun `空代码不给键`() {
        assertNull(StgRowSelection.keyOf(null, "1"))
        assertNull(StgRowSelection.keyOf("", "1"))
        assertNull(StgRowSelection.keyOf("   ", "1"))
    }

    @Test
    fun `cam 拆成键`() {
        assertEquals("600000@1", StgRowSelection.keyOfCam("600000.1"))
    }

    @Test
    fun `cam 没有点时整串当代码`() {
        // 宿主 StockInfo 是构造后填充的，「只有代码没有市场」是真实存在的中间态
        assertEquals("600000", StgRowSelection.keyOfCam("600000"))
    }

    @Test
    fun `点号在首位时不当作切分`() {
        // CodeMarketParts 的口径：dot > 0 才切；dot == 0 时整串当代码
        assertEquals(".600000", StgRowSelection.keyOfCam(".600000"))
    }

    @Test
    fun `cam 为空不给键`() {
        assertNull(StgRowSelection.keyOfCam(null))
        assertNull(StgRowSelection.keyOfCam(""))
    }

    @Test
    fun `同一列表的发布能被读到`() {
        val owner = Any()
        StgRowSelection.publish(owner, setOf("600000@1", "000001@2"))
        assertEquals(setOf("600000@1", "000001@2"), StgRowSelection.freshOf(owner))
    }

    @Test
    fun `别的列表读不到`() {
        val mine = Any()
        val other = Any()
        StgRowSelection.publish(mine, setOf("600000@1"))
        assertNull(StgRowSelection.freshOf(other))
        assertNull(StgRowSelection.freshOf(null))
    }

    @Test
    fun `空集合等于撤回`() {
        val owner = Any()
        StgRowSelection.publish(owner, setOf("600000@1"))
        StgRowSelection.publish(owner, emptySet())
        assertNull(StgRowSelection.freshOf(owner))
        assertNull(StgRowSelection.latest())
    }

    @Test
    fun `过期快照读不到`() {
        val owner = Any()
        StgRowSelection.publish(owner, setOf("600000@1"))
        val snap = StgRowSelection.latest()
        assertNotNull(snap)
        assertTrue(snap.isFresh(snap.at))
        assertTrue(snap.isFresh(snap.at + StgRowSelection.FRESH_MS))
        assertFalse(snap.isFresh(snap.at + StgRowSelection.FRESH_MS + 1))
    }

    @Test
    fun `发布是防御性拷贝`() {
        val owner = Any()
        val keys = HashSet<String>()
        keys.add("600000@1")
        StgRowSelection.publish(owner, keys)
        keys.add("000001@2")
        assertEquals(setOf("600000@1"), StgRowSelection.freshOf(owner))
    }

    @Test
    fun `行集合版本号递增`() {
        val before = StgRowSelection.rowEpoch()
        val after = StgRowSelection.bumpRowEpoch()
        assertEquals(before + 1, after)
        assertEquals(after, StgRowSelection.rowEpoch())
        assertEquals(after + 1, StgRowSelection.bumpRowEpoch())
    }
}
