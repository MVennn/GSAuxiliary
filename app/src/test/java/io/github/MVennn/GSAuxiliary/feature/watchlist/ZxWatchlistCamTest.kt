package io.github.MVennn.GSAuxiliary.feature.watchlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 自选判定入口的拆解口径（[ZxWatchlist.splitCodeAndMarket]）。
 *
 * 为什么单独钉：列表行上「拆不出来」不会被发现 —— 表现只是那一行不染色，
 * 与「真的不在自选」一模一样。拆错则会把整行染成高亮色，更难看。
 */
class ZxWatchlistCamTest {

    @Test
    fun splitsStandardShape() {
        assertEquals("300006" to "1", ZxWatchlist.splitCodeAndMarket("300006.1"))
        assertEquals("600000" to "2", ZxWatchlist.splitCodeAndMarket("600000.2"))
    }

    @Test
    fun splitsOnFirstDotOnly() {
        // 宿主目前不会给出这种形状，但拆法必须是确定的（否则换了形状就静默错）
        assertEquals("300006" to "1.2", ZxWatchlist.splitCodeAndMarket("300006.1.2"))
    }

    @Test
    fun unsplittableIsNull() {
        assertNull(ZxWatchlist.splitCodeAndMarket(null))
        assertNull(ZxWatchlist.splitCodeAndMarket(""))
        assertNull(ZxWatchlist.splitCodeAndMarket("300006"))
        assertNull(ZxWatchlist.splitCodeAndMarket(".300006"), "点在开头 → 缺市场段")
        assertNull(ZxWatchlist.splitCodeAndMarket("300006."), "点在结尾 → 缺代码段")
    }
}