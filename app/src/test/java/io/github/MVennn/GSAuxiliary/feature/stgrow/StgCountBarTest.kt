package io.github.MVennn.GSAuxiliary.feature.stgrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计数文案口径（[StgCountBar]）。
 *
 * 这些断言的作用是**钉死两处写点共用的那一份格式**：筛选与聚合都往 `#tv_stg_res_num` 写，
 * 一边改了格式而另一边没跟上，真机表现就是「筛完数字不跟着动」或者数字来回闪。
 */
class StgCountBarTest {

    @Test
    fun totalUsesHostShape() {
        assertEquals("策略个股47只", StgCountBar.total(47))
        assertEquals("策略个股0只", StgCountBar.total(0))
    }

    @Test
    fun keptUsesSlashShapeWithSpace() {
        assertEquals("策略个股 3/15", StgCountBar.kept(3, 15))
        assertEquals("策略个股 0/212", StgCountBar.kept(0, 212))
    }

    @Test
    fun keptOutputIsRecognisedAsOurs() {
        // 拼出来的文案必须能被「是不是我们写的」这条判据认出来，否则取消筛选时还原不掉
        assertTrue(StgCountBar.isOurs(StgCountBar.kept(36, 212)))
    }

    @Test
    fun hostTextIsNotOurs() {
        assertFalse(StgCountBar.isOurs("策略个股212只"))
        assertFalse(StgCountBar.isOurs("策略个股47只(11-11 19:40:22)"))
        assertFalse(StgCountBar.isOurs(""))
        assertFalse(StgCountBar.isOurs(null))
    }

    @Test
    fun hostTailKeepsParenthesisedTime() {
        assertEquals("(11-11 19:40:22)", StgCountBar.hostTail("策略个股47只(11-11 19:40:22)"))
    }

    @Test
    fun hostTailIsEmptyWithoutSuffix() {
        assertEquals("", StgCountBar.hostTail("策略个股15只"))
        assertEquals("", StgCountBar.hostTail("策略个股 3/15"))
        assertEquals("", StgCountBar.hostTail(null))
    }
}
