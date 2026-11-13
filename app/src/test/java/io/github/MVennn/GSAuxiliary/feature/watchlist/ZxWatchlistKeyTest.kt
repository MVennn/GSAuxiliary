package io.github.MVennn.GSAuxiliary.feature.watchlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 自选表的键 → 6 位代码。
 *
 * 这一层是「自选判定能不能按代码命中」的地基：宿主 `m_stkGrpMap` 的键形如 `300006.1`，
 * 而筛选要拿它跟屏幕上的 `codeAndMarket` 比。2026-11-13 真机漏判 2 只自选的直接原因，
 * 就是这条链在「只有代码没有市场号」这种中间态上断掉了（缺市场号时旧实现直接判非自选）。
 */
class ZxWatchlistKeyTest {

    @Test
    fun `点前就是代码`() {
        assertEquals("300006", ZxWatchlist.codeOfWatchlistKey("300006.1"))
        assertEquals("600000", ZxWatchlist.codeOfWatchlistKey("600000.2"))
        assertEquals("920575", ZxWatchlist.codeOfWatchlistKey("920575.5"))
    }

    @Test
    fun `没有点时整串当代码`() {
        assertEquals("300006", ZxWatchlist.codeOfWatchlistKey("300006"))
    }

    @Test
    fun `点号在首位时不切`() {
        assertEquals(".300006", ZxWatchlist.codeOfWatchlistKey(".300006"))
    }

    @Test
    fun `两端空白被去掉`() {
        assertEquals("300006", ZxWatchlist.codeOfWatchlistKey(" 300006.1 "))
    }

    @Test
    fun `空键不给代码`() {
        assertNull(ZxWatchlist.codeOfWatchlistKey(null))
        assertNull(ZxWatchlist.codeOfWatchlistKey(""))
        assertNull(ZxWatchlist.codeOfWatchlistKey("   "))
    }
}
